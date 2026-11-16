package com.novelweb.library.publication;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
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

/** Appends an assigned reviewer's reason-coded decision without publishing the work. */
@RestController
@RequestMapping("/api/v1/review/publications")
@Profile("postgres")
public class PublicationReviewDecisionController {

  private static final int MAX_BODY_BYTES = 2_048;
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final Pattern DECISION_ETAG =
      Pattern.compile("\"publication-review-[a-f0-9]{64}\"");
  private static final Set<String> BODY_FIELDS = Set.of("actionCode", "reasonCode");
  private static final Set<String> REASONS_NEEDS_INFORMATION =
      Set.of("INCOMPLETE_CLAIM", "MORE_INFORMATION", "SCOPE_UNCLEAR");
  private static final Set<String> REASONS_APPROVED = Set.of("RIGHTS_REVIEWED", "SCOPE_CONFIRMED");
  private static final Set<String> REASONS_REJECTED =
      Set.of("RIGHTS_NOT_VERIFIED", "OUT_OF_SCOPE", "POLICY_MISMATCH");

  private final PublicationReviewDecisionService decisionService;
  private final ObjectMapper objectMapper;

  public PublicationReviewDecisionController(
      PublicationReviewDecisionService decisionService, ObjectMapper objectMapper) {
    this.decisionService = decisionService;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/{requestId}/decisions")
  public ResponseEntity<?> decide(
      Authentication authentication, @PathVariable String requestId, HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in with an assigned reviewer account to decide this request.",
          correlationId);
    }
    if (!isJson(request.getContentType())) {
      return problem(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "UNSUPPORTED_MEDIA_TYPE",
          "Send the reviewer decision as JSON.",
          correlationId);
    }

    final UUID parsedRequestId;
    try {
      parsedRequestId = UUID.fromString(requestId);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_REVIEW_REQUEST_ID_INVALID",
          "requestId must be a UUID.",
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
          "Reload the request detail and send its ETag in If-Match.",
          correlationId);
    }
    if (!DECISION_ETAG.matcher(ifMatch).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong publication-review ETag.",
          correlationId);
    }

    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "REQUEST_TOO_LARGE",
          "The reviewer decision body may not exceed 2 KiB.",
          correlationId);
    }
    final byte[] body;
    try {
      body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    } catch (IOException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_INVALID",
          "The reviewer decision body could not be read.",
          correlationId);
    }
    if (body.length == 0) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_REQUIRED",
          "Provide an actionCode and reasonCode.",
          correlationId);
    }
    if (body.length > MAX_BODY_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "REQUEST_TOO_LARGE",
          "The reviewer decision body may not exceed 2 KiB.",
          correlationId);
    }

    PublicationReviewDecisionService.DecisionCommand command = parseDecision(body);
    if (command == null) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_REVIEW_DECISION_INVALID",
          "Provide a supported actionCode and its matching reasonCode without extra fields.",
          correlationId);
    }

    try {
      PublicationReviewDecisionService.DecisionResult result =
          decisionService.decide(
              principal.userId(), parsedRequestId, ifMatch, idempotencyKey, command);
      return response(result, correlationId);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_REVIEW_DECISION_UNAVAILABLE",
          "The reviewer decision could not be safely recorded. Retry with the same Idempotency-Key.",
          correlationId);
    }
  }

  private PublicationReviewDecisionService.DecisionCommand parseDecision(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root == null || !root.isObject()) return null;
      Set<String> fields = new HashSet<>(root.propertyNames());
      if (!BODY_FIELDS.containsAll(fields) || fields.size() != BODY_FIELDS.size()) return null;
      JsonNode actionNode = root.get("actionCode");
      JsonNode reasonNode = root.get("reasonCode");
      if (actionNode == null
          || !actionNode.isTextual()
          || reasonNode == null
          || !reasonNode.isTextual()) {
        return null;
      }
      String action = actionNode.textValue();
      String reason = reasonNode.textValue();
      boolean validReason =
          switch (action) {
            case "NEEDS_INFORMATION" -> REASONS_NEEDS_INFORMATION.contains(reason);
            case "APPROVED" -> REASONS_APPROVED.contains(reason);
            case "REJECTED" -> REASONS_REJECTED.contains(reason);
            default -> false;
          };
      return validReason
          ? new PublicationReviewDecisionService.DecisionCommand(action, reason)
          : null;
    } catch (JacksonException exception) {
      return null;
    }
  }

  private static ResponseEntity<?> response(
      PublicationReviewDecisionService.DecisionResult result, UUID correlationId) {
    return switch (result.outcome()) {
      case CREATED, REPLAYED ->
          ResponseEntity.status(HttpStatus.CREATED)
              .header(HttpHeaders.ETAG, result.etag())
              .cacheControl(CacheControl.noStore())
              .header(HttpHeaders.PRAGMA, "no-cache")
              .body(result.receipt());
      case REVIEWER_REQUIRED ->
          problem(
              HttpStatus.FORBIDDEN,
              "PUBLICATION_REVIEWER_REQUIRED",
              "An active publication-reviewer assignment is required.",
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
      case NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "PUBLICATION_REVIEW_REQUEST_NOT_FOUND",
              "The publication request is not available.",
              correlationId);
      case KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "This Idempotency-Key was already used for another decision.",
              correlationId);
      case STALE_ETAG ->
          problemWithEtag(
              HttpStatus.valueOf(412),
              "PUBLICATION_REVIEW_ETAG_STALE",
              "The request or one of its decision gates changed. Reload the request before deciding.",
              result.etag(),
              correlationId);
      case INVALID_STATE ->
          problemWithEtag(
              HttpStatus.CONFLICT,
              "PUBLICATION_REVIEW_STATE_CONFLICT",
              "The request is no longer pending review.",
              result.etag(),
              correlationId);
      case STALE_SNAPSHOT ->
          problemWithEtag(
              HttpStatus.valueOf(412),
              "PUBLICATION_REVIEW_SNAPSHOT_STALE",
              "The manuscript changed after submission. Reload the request; approval is blocked.",
              result.etag(),
              correlationId);
      case RIGHTS_POLICY_NOT_CONFIGURED ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED",
              "No current approved publication-rights policy is available.",
              correlationId);
      case RIGHTS_POLICY_STALE -> {
        ProblemDetail body =
            baseProblem(
                HttpStatus.CONFLICT,
                "PUBLICATION_RIGHTS_POLICY_STALE",
                "The request uses an older rights policy and cannot be approved.",
                correlationId);
        body.setProperty("currentPolicyVersion", result.policyVersion());
        yield noStore(HttpStatus.CONFLICT).header(HttpHeaders.ETAG, result.etag()).body(body);
      }
      case UNRESOLVED_CLAIM ->
          problemWithEtag(
              HttpStatus.CONFLICT,
              "PUBLICATION_REVIEW_CLAIM_UNRESOLVED",
              "A request with a NEEDS_REVIEW rights declaration cannot be approved.",
              result.etag(),
              correlationId);
      case STORE_UNAVAILABLE ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "PUBLICATION_REVIEW_STORAGE_UNAVAILABLE",
              "The submitted review snapshot could not be verified safely.",
              correlationId);
    };
  }

  private static ResponseEntity<ProblemDetail> problemWithEtag(
      HttpStatus status, String code, String detail, String etag, UUID correlationId) {
    return noStore(status)
        .header(HttpHeaders.ETAG, etag)
        .body(baseProblem(status, code, detail, correlationId));
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
