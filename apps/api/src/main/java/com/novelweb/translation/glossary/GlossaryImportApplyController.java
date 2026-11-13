package com.novelweb.translation.glossary;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Revalidates a resolved import and writes it as one immutable draft revision. */
@RestController
@RequestMapping("/api/v1/glossaries/{glossaryId}/terms/import-apply")
@Profile("postgres")
public class GlossaryImportApplyController {

  private static final int MAX_REQUEST_BYTES = 2_097_152;
  private static final int MAX_BASE64_FILE_LENGTH = 1_398_104;

  private final GlossaryImportApplyService applyService;
  private final PolicyService policyService;
  private final ObjectMapper objectMapper;

  public GlossaryImportApplyController(
      GlossaryImportApplyService applyService,
      PolicyService policyService,
      ObjectMapper objectMapper) {
    this.applyService = applyService;
    this.policyService = policyService;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ResponseEntity<?> apply(
      Authentication authentication,
      @PathVariable UUID glossaryId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request)
      throws IOException {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to apply a glossary import.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (ifMatch == null) {
      return problem(
          HttpStatus.PRECONDITION_REQUIRED,
          "IF_MATCH_REQUIRED",
          "Reload the glossary before applying this import.",
          request);
    }
    long expectedVersion = parseEtagVersion(ifMatch);
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{16,128}")) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_INVALID",
          "Provide a 16 to 128 character Idempotency-Key for this apply attempt.",
          request);
    }
    GlossaryImportPreviewRepository.WritableSnapshot snapshot =
        applyService.loadSnapshot(actor.orElseThrow(), glossaryId);
    requireJson(request.getContentType());
    byte[] requestBody = readBoundedBody(request);
    if (requestBody == null) {
      return problem(
          HttpStatus.valueOf(413),
          "IMPORT_APPLY_LIMIT_EXCEEDED",
          "The resolved import request exceeds the 2 MiB limit.",
          request);
    }

    ImportApplyRequest body;
    try {
      body = objectMapper.readValue(requestBody, ImportApplyRequest.class);
    } catch (JacksonException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_IMPORT_APPLY_REQUEST",
          "The resolved import request must be valid JSON.",
          request);
    }
    if (body == null
        || body.format() == null
        || body.fileContentBase64() == null
        || body.fileContentBase64().length() > MAX_BASE64_FILE_LENGTH
        || body.resolutions() == null) {
      return problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "IMPORT_APPLY_FIELDS_INVALID",
          "Provide the original file, its format and one decision for each imported row.",
          request);
    }

    byte[] fileContent;
    GlossaryImportParser.Format format;
    try {
      fileContent = Base64.getDecoder().decode(body.fileContentBase64());
      format = GlossaryImportParser.Format.valueOf(body.format());
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "IMPORT_APPLY_FILE_ENCODING_INVALID",
          "The file encoding or format is not supported.",
          request);
    }
    if (fileContent.length > GlossaryImportParser.MAX_IMPORT_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "IMPORT_LIMIT_EXCEEDED",
          "Glossary import files may not exceed 1 MiB.",
          request);
    }

    GlossaryImportApplyService.AppliedDraft applied =
        applyService.apply(
            actor.orElseThrow(),
            snapshot,
            expectedVersion,
            fileContent,
            format,
            body.resolutions(),
            idempotencyKey,
            sha256(requestBody));
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.ETAG, etag(applied.draft().glossaryVersion()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(applied);
  }

  @ExceptionHandler(GlossaryImportPreviewRepository.GlossaryNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "NOT_FOUND",
        "The glossary is unavailable or you cannot edit it.",
        request);
  }

  @ExceptionHandler(GlossaryImportPreviewService.VersionConflictException.class)
  public ResponseEntity<ProblemDetail> stalePreview(
      GlossaryImportPreviewService.VersionConflictException exception, HttpServletRequest request) {
    return staleVersion(exception.currentVersion(), request);
  }

  @ExceptionHandler(GlossaryRevisionWriteRepository.VersionConflictException.class)
  public ResponseEntity<ProblemDetail> staleWrite(
      GlossaryRevisionWriteRepository.VersionConflictException exception,
      HttpServletRequest request) {
    return staleVersion(exception.currentVersion(), request);
  }

  @ExceptionHandler(GlossaryRevisionWriteRepository.IdempotencyKeyReusedException.class)
  public ResponseEntity<ProblemDetail> idempotencyKeyReused(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "This Idempotency-Key belongs to a different glossary import request. Use a new key.",
        request);
  }

  @ExceptionHandler(GlossaryImportParser.ParseException.class)
  public ResponseEntity<ProblemDetail> invalidFile(
      GlossaryImportParser.ParseException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problemDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "GLOSSARY_IMPORT_INVALID",
            "Correct the reported glossary file issues before applying it.",
            request);
    var issues = exception.issues();
    problem.setProperty("issueCount", issues.size());
    problem.setProperty("issues", issues.stream().limit(100).toList());
    problem.setProperty("issuesTruncated", issues.size() > 100);
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
  }

  @ExceptionHandler(GlossaryImportApplyService.ApplyException.class)
  public ResponseEntity<ProblemDetail> invalidResolution(
      GlossaryImportApplyService.ApplyException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problemDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            exception.code(),
            "Resolve every import row and remove any remaining pattern conflict before saving.",
            request);
    if (exception.rowNumber() > 0) problem.setProperty("rowNumber", exception.rowNumber());
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
  }

  @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
  public ResponseEntity<ProblemDetail> unsupportedType(HttpServletRequest request) {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "IMPORT_APPLY_UNSUPPORTED_FORMAT",
        "Submit the resolved import as application/json.",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_IMPORT_APPLY_REQUEST",
        "If-Match must contain the strong glossary version ETag.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "GLOSSARY_IMPORT_APPLY_UNAVAILABLE",
        "The glossary draft could not be stored. Please try again.",
        request);
  }

  @ExceptionHandler(IOException.class)
  public ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "IMPORT_APPLY_BODY_UNREADABLE",
        "The resolved import request could not be read.",
        request);
  }

  private ResponseEntity<ProblemDetail> staleVersion(
      long currentVersion, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .header(HttpHeaders.ETAG, etag(currentVersion))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(
            problemDetail(
                HttpStatus.CONFLICT,
                "GLOSSARY_VERSION_STALE",
                "The glossary changed before the import was saved. Preview and resolve it again.",
                request));
  }

  private ResponseEntity<?> policyGate(UUID actorUserId, HttpServletRequest request) {
    try {
      return switch (policyService.requiredAcceptance(actorUserId)) {
        case COMPLETE -> null;
        case REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Review and accept current Terms and Privacy documents before editing a glossary.",
                request);
        case NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_NOT_CONFIGURED",
                "Current approved policy documents are not available.",
                request);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "POLICY_STORE_UNAVAILABLE",
          "Current policy state could not be checked. Please try again.",
          request);
    }
  }

  private static void requireJson(String contentType) {
    if (contentType == null)
      throw new UnsupportedMediaTypeStatusException("Content-Type is required");
    MediaType mediaType;
    try {
      mediaType = MediaType.parseMediaType(contentType);
    } catch (IllegalArgumentException exception) {
      throw new UnsupportedMediaTypeStatusException("application/json is required");
    }
    if (!mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
      throw new UnsupportedMediaTypeStatusException("application/json is required");
    }
  }

  private static byte[] readBoundedBody(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > MAX_REQUEST_BYTES) return null;
    byte[] content = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
    return content.length > MAX_REQUEST_BYTES ? null : content;
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Optional<UUID> actorUserId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static long parseEtagVersion(String ifMatch) {
    if (!ifMatch.matches("\"v[1-9][0-9]{0,18}\"")) {
      throw new IllegalArgumentException("If-Match must contain a strong glossary version ETag");
    }
    try {
      return Long.parseLong(ifMatch.substring(2, ifMatch.length() - 1));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("If-Match must contain a strong glossary version ETag");
    }
  }

  private static String etag(long version) {
    return "\"v" + version + "\"";
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return problem(status, problemDetail(status, code, detail, request));
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, ProblemDetail problem) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }

  private static ProblemDetail problemDetail(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", correlationId instanceof String id ? id : "unavailable");
    return problem;
  }

  public record ImportApplyRequest(
      String format,
      String fileContentBase64,
      java.util.List<GlossaryImportApplyService.RowResolution> resolutions) {}
}
