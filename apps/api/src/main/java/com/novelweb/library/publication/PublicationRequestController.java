package com.novelweb.library.publication;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Accepts bounded owner declarations for an immutable publication-rights review request. */
@RestController
@RequestMapping("/api/v1/authoring/manuscripts/{workId}/publication/requests")
@Profile("postgres")
public class PublicationRequestController {

  private static final int MAX_BODY_BYTES = 4_096;
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final Pattern SNAPSHOT_ETAG =
      Pattern.compile("\"publication-snapshot-[a-f0-9]{64}\"");
  private static final Pattern POLICY_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final Set<String> BODY_FIELDS = Set.of("policyVersion", "scopeClaims");
  private static final Set<String> CLAIM_FIELDS = Set.of("scopeCode", "rightsBasisCode");
  private static final Set<String> REQUIRED_SCOPES = Set.of("PUBLIC_METADATA", "CHAPTER_TEXT");
  private static final Set<String> OPTIONAL_SCOPES =
      Set.of("COMMERCIAL_SALE", "TRANSLATION_ADAPTATION", "FREE_PREVIEW");
  private static final Set<String> RIGHTS_BASES =
      Set.of("ORIGINAL_WORK", "LICENSE_OR_PERMISSION", "PUBLIC_DOMAIN", "NEEDS_REVIEW");

  private final PublicationRequestService publicationService;
  private final ObjectMapper objectMapper;

  public PublicationRequestController(
      PublicationRequestService publicationService, ObjectMapper objectMapper) {
    this.publicationService = publicationService;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ResponseEntity<?> submit(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    return submitRequest(authentication, workId, null, request);
  }

  @PostMapping("/chapters/{chapterId}")
  public ResponseEntity<?> submitChapter(
      Authentication authentication,
      @PathVariable UUID workId,
      @PathVariable UUID chapterId,
      HttpServletRequest request) {
    return submitRequest(authentication, workId, chapterId, request);
  }

  private ResponseEntity<?> submitRequest(
      Authentication authentication, UUID workId, UUID chapterId, HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to submit this manuscript for a rights review.",
          correlationId);
    }
    if (!isJson(request.getContentType())) {
      return problem(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "UNSUPPORTED_MEDIA_TYPE",
          "Send the publication request as JSON.",
          correlationId);
    }

    String idempotencyKey = singleHeader(request, "Idempotency-Key");
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_INVALID",
          "Provide a 16 to 128 character Idempotency-Key and reuse it for an exact retry.",
          correlationId);
    }
    String ifMatch = singleHeader(request, HttpHeaders.IF_MATCH);
    if (ifMatch == null) {
      return problem(
          HttpStatus.valueOf(428),
          "IF_MATCH_REQUIRED",
          "Reload publication readiness and send its ETag in If-Match.",
          correlationId);
    }
    if (!SNAPSHOT_ETAG.matcher(ifMatch).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong publication-readiness ETag.",
          correlationId);
    }

    long contentLength = request.getContentLengthLong();
    if (contentLength > MAX_BODY_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "REQUEST_TOO_LARGE",
          "The publication request body may not exceed 4 KiB.",
          correlationId);
    }

    final byte[] body;
    try {
      body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    } catch (IOException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_INVALID",
          "The publication request body could not be read.",
          correlationId);
    }
    if (body.length == 0) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_REQUIRED",
          "Provide the policy version and scope declarations.",
          correlationId);
    }
    if (body.length > MAX_BODY_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "REQUEST_TOO_LARGE",
          "The publication request body may not exceed 4 KiB.",
          correlationId);
    }

    PublicationRequestService.Submission submission = parseSubmission(body);
    if (submission == null) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_REQUEST_INVALID",
          "Provide current policy and metadata/chapter-text rights claims. FREE_PREVIEW requires COMMERCIAL_SALE.",
          correlationId);
    }

    try {
      PublicationRequestService.SubmitResult result =
          chapterId == null
              ? publicationService.submit(
                  principal.userId(), workId, ifMatch, idempotencyKey, submission)
              : publicationService.submitChapter(
                  principal.userId(), workId, chapterId, ifMatch, idempotencyKey, submission);
      return response(result, correlationId);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_REQUEST_UNAVAILABLE",
          "The publication request could not be safely recorded. Retry with the same Idempotency-Key.",
          correlationId);
    }
  }

  private PublicationRequestService.Submission parseSubmission(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root == null || !root.isObject() || hasUnknownFields(root, BODY_FIELDS)) return null;
      JsonNode policyVersion = root.get("policyVersion");
      JsonNode scopeClaims = root.get("scopeClaims");
      if (policyVersion == null
          || !policyVersion.isTextual()
          || !POLICY_VERSION.matcher(policyVersion.textValue()).matches()
          || scopeClaims == null
          || !scopeClaims.isArray()
          || scopeClaims.size() < REQUIRED_SCOPES.size()
          || scopeClaims.size() > REQUIRED_SCOPES.size() + OPTIONAL_SCOPES.size()) {
        return null;
      }

      List<PublicationRequestService.ScopeClaim> claims = new ArrayList<>();
      Set<String> seen = new java.util.HashSet<>();
      for (JsonNode claim : scopeClaims) {
        if (claim == null || !claim.isObject() || hasUnknownFields(claim, CLAIM_FIELDS))
          return null;
        JsonNode scopeCode = claim.get("scopeCode");
        JsonNode rightsBasisCode = claim.get("rightsBasisCode");
        if (scopeCode == null
            || !scopeCode.isTextual()
            || rightsBasisCode == null
            || !rightsBasisCode.isTextual()) {
          return null;
        }
        String scope = scopeCode.textValue();
        String basis = rightsBasisCode.textValue();
        if ((!REQUIRED_SCOPES.contains(scope) && !OPTIONAL_SCOPES.contains(scope))
            || !RIGHTS_BASES.contains(basis)
            || !seen.add(scope)) {
          return null;
        }
        claims.add(new PublicationRequestService.ScopeClaim(scope, basis));
      }
      if (!seen.containsAll(REQUIRED_SCOPES)) return null;
      if (seen.contains("FREE_PREVIEW") && !seen.contains("COMMERCIAL_SALE")) return null;
      return new PublicationRequestService.Submission(policyVersion.textValue(), claims);
    } catch (JacksonException exception) {
      return null;
    }
  }

  private static boolean hasUnknownFields(JsonNode object, Set<String> allowedFields) {
    for (String name : object.propertyNames()) {
      if (!allowedFields.contains(name)) return true;
    }
    return false;
  }

  private static ResponseEntity<?> response(
      PublicationRequestService.SubmitResult result, UUID correlationId) {
    return switch (result.outcome()) {
      case CREATED, REPLAYED -> noStore(HttpStatus.CREATED).body(result.receipt());
      case WORK_NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "MANUSCRIPT_NOT_FOUND",
              "The private authored manuscript is not available.",
              correlationId);
      case POLICY_REQUIRED ->
          problem(
              HttpStatus.FORBIDDEN,
              "POLICY_ACCEPTANCE_REQUIRED",
              "Accept the current Terms and acknowledge the Privacy Notice first.",
              correlationId);
      case POLICY_NOT_CONFIGURED ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "POLICY_NOT_CONFIGURED",
              "Current required policy documents are not available.",
              correlationId);
      case RIGHTS_POLICY_NOT_CONFIGURED ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED",
              "An approved, effective publication-rights policy is not available.",
              correlationId);
      case RIGHTS_POLICY_ACCEPTANCE_REQUIRED ->
          problem(
              HttpStatus.FORBIDDEN,
              "PUBLICATION_RIGHTS_POLICY_ACCEPTANCE_REQUIRED",
              "Read and acknowledge the current publication-rights policy for this manuscript.",
              correlationId);
      case STALE_POLICY -> policyStale(result.currentPolicyVersion(), correlationId);
      case KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "This Idempotency-Key belongs to a different publication request. Use a new key.",
              correlationId);
      case STALE_SNAPSHOT -> snapshotStale(result.currentSnapshotEtag(), correlationId);
      case READY_CHAPTER_REQUIRED ->
          problem(
              HttpStatus.CONFLICT,
              "READY_CHAPTER_REQUIRED",
              "Save at least one ready chapter before submitting this work.",
              correlationId);
      case REQUEST_ALREADY_PENDING ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_REQUEST_ALREADY_PENDING",
              "This manuscript already has a request awaiting review.",
              correlationId);
      case RATE_LIMITED ->
          noStore(HttpStatus.TOO_MANY_REQUESTS)
              .header(HttpHeaders.RETRY_AFTER, Integer.toString(result.retryAfterSeconds()))
              .body(
                  baseProblem(
                      HttpStatus.TOO_MANY_REQUESTS,
                      "PUBLICATION_REQUEST_RATE_LIMITED",
                      "Wait before submitting another publication request.",
                      correlationId));
    };
  }

  private static ResponseEntity<ProblemDetail> policyStale(String version, UUID correlationId) {
    ProblemDetail problem =
        baseProblem(
            HttpStatus.CONFLICT,
            "PUBLICATION_RIGHTS_POLICY_STALE",
            "Review and acknowledge the current publication-rights policy before submitting.",
            correlationId);
    problem.setProperty("currentPolicyVersion", version);
    return noStore(HttpStatus.CONFLICT).body(problem);
  }

  private static ResponseEntity<ProblemDetail> snapshotStale(String etag, UUID correlationId) {
    return noStore(HttpStatus.valueOf(412))
        .header(HttpHeaders.ETAG, etag)
        .body(
            baseProblem(
                HttpStatus.valueOf(412),
                "PUBLICATION_SNAPSHOT_STALE",
                "The manuscript changed after you reviewed it. Reload readiness and confirm again.",
                correlationId));
  }

  private static boolean isJson(String contentType) {
    if (contentType == null) return false;
    try {
      return MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static String singleHeader(HttpServletRequest request, String name) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) return null;
    String value = values.nextElement();
    return values.hasMoreElements() ? null : value;
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    return noStore(status).body(baseProblem(status, code, detail, correlationId));
  }

  private static ProblemDetail baseProblem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId.toString());
    return problem;
  }

  private static SessionService.SessionPrincipal principal(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return null;
    }
    return principal;
  }

  private static UUID correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    if (value instanceof String id) {
      try {
        return UUID.fromString(id);
      } catch (IllegalArgumentException ignored) {
        // Generate a fallback correlation ID when normal request filters are not present.
      }
    }
    return UUID.randomUUID();
  }
}
