package com.novelweb.translation.glossary;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
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

/** Validates a glossary file and previews conflicts against the current writable revision. */
@RestController
@RequestMapping("/api/v1/glossaries/{glossaryId}/terms/import-preview")
@Profile("postgres")
public class GlossaryImportPreviewController {

  private static final int MAX_RETURNED_ISSUES = 100;

  private final GlossaryImportPreviewService previewService;
  private final PolicyService policyService;

  public GlossaryImportPreviewController(
      GlossaryImportPreviewService previewService, PolicyService policyService) {
    this.previewService = previewService;
    this.policyService = policyService;
  }

  @PostMapping
  public ResponseEntity<?> preview(
      Authentication authentication,
      @PathVariable UUID glossaryId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request)
      throws IOException {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to preview a glossary import.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (ifMatch == null) {
      return problem(
          HttpStatus.PRECONDITION_REQUIRED,
          "IF_MATCH_REQUIRED",
          "Reload the glossary before previewing an import.",
          request);
    }

    long expectedVersion = parseEtagVersion(ifMatch);
    GlossaryImportPreviewRepository.WritableSnapshot snapshot =
        previewService.loadCurrent(actor.orElseThrow(), glossaryId, expectedVersion);
    GlossaryImportParser.Format format = importFormat(request.getContentType());
    byte[] content = readBoundedBody(request);
    if (content == null) {
      return problem(
          HttpStatus.valueOf(413),
          "IMPORT_LIMIT_EXCEEDED",
          "Glossary import files may not exceed 1 MiB.",
          request);
    }
    GlossaryImportPreviewService.Preview preview =
        previewService.preview(snapshot, content, format);
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, etag(snapshot.version()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(preview);
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
  public ResponseEntity<ProblemDetail> staleVersion(
      GlossaryImportPreviewService.VersionConflictException exception, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .header(HttpHeaders.ETAG, etag(exception.currentVersion()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(
            problemDetail(
                HttpStatus.CONFLICT,
                "GLOSSARY_VERSION_STALE",
                "The glossary changed elsewhere. Reload it before previewing this import.",
                request));
  }

  @ExceptionHandler(GlossaryImportParser.ParseException.class)
  public ResponseEntity<ProblemDetail> invalidFile(
      GlossaryImportParser.ParseException exception, HttpServletRequest request) {
    ProblemDetail problem =
        problemDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "GLOSSARY_IMPORT_INVALID",
            "Correct the reported glossary file issues before previewing it again.",
            request);
    List<GlossaryImportParser.ParseIssue> issues = exception.issues();
    problem.setProperty("issueCount", issues.size());
    problem.setProperty("issues", issues.stream().limit(MAX_RETURNED_ISSUES).toList());
    problem.setProperty("issuesTruncated", issues.size() > MAX_RETURNED_ISSUES);
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
  }

  @ExceptionHandler(UnsupportedMediaTypeStatusException.class)
  public ResponseEntity<ProblemDetail> unsupportedFileType(HttpServletRequest request) {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "IMPORT_UNSUPPORTED_FORMAT",
        "Use an application/json, text/csv or text/tab-separated-values glossary file.",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_GLOSSARY_IMPORT_REQUEST",
        "If-Match must contain the strong glossary version ETag.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "GLOSSARY_IMPORT_PREVIEW_UNAVAILABLE",
        "The glossary import preview could not be prepared. Please try again.",
        request);
  }

  @ExceptionHandler(IOException.class)
  public ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "IMPORT_BODY_UNREADABLE",
        "The glossary import file could not be read.",
        request);
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

  private static GlossaryImportParser.Format importFormat(String contentType) {
    if (contentType == null)
      throw new UnsupportedMediaTypeStatusException("Content-Type is required");
    MediaType mediaType;
    try {
      mediaType = MediaType.parseMediaType(contentType);
    } catch (IllegalArgumentException exception) {
      throw new UnsupportedMediaTypeStatusException("A supported Content-Type is required");
    }
    if (mediaType.isCompatibleWith(MediaType.APPLICATION_JSON))
      return GlossaryImportParser.Format.JSON;
    if (mediaType.isCompatibleWith(MediaType.valueOf("text/csv")))
      return GlossaryImportParser.Format.CSV;
    if (mediaType.isCompatibleWith(MediaType.valueOf("text/tab-separated-values"))) {
      return GlossaryImportParser.Format.TSV;
    }
    throw new UnsupportedMediaTypeStatusException("Unsupported glossary file format");
  }

  private static byte[] readBoundedBody(HttpServletRequest request) throws IOException {
    long contentLength = request.getContentLengthLong();
    if (contentLength > GlossaryImportParser.MAX_IMPORT_BYTES) return null;
    byte[] content = request.getInputStream().readNBytes(GlossaryImportParser.MAX_IMPORT_BYTES + 1);
    return content.length > GlossaryImportParser.MAX_IMPORT_BYTES ? null : content;
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
}
