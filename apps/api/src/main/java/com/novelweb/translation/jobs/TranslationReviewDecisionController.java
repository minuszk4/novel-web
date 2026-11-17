package com.novelweb.translation.jobs;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

/** Appends a reviewer accept/reject event without publishing a translation. */
@RestController
@RequestMapping("/api/v1/review/translations")
@Profile("postgres & translation-fake")
@ConditionalOnProperty(prefix = "novel.translation.review", name = "enabled", havingValue = "true")
public class TranslationReviewDecisionController {

  private static final int MAX_BODY_BYTES = 2_048;
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final Pattern REVIEW_ETAG = Pattern.compile("\"translation-review-[a-f0-9]{64}\"");
  private static final Set<String> BODY_FIELDS = Set.of("actionCode", "reasonCode");
  private static final Set<String> ACCEPT_REASONS = Set.of("ACCURATE", "MINOR_TERMINOLOGY_ISSUE");
  private static final Set<String> REJECT_REASONS =
      Set.of("MISSING_CONTENT", "MEANING_ERROR", "TERMINOLOGY_ERROR", "FORMATTING_ERROR", "OTHER");

  private final TranslationReviewDecisionService decisionService;
  private final ObjectMapper objectMapper;

  public TranslationReviewDecisionController(
      TranslationReviewDecisionService decisionService, ObjectMapper objectMapper) {
    this.decisionService = decisionService;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/{submissionId}/decisions")
  public ResponseEntity<?> decide(
      Authentication authentication,
      @PathVariable String submissionId,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in with an assigned reviewer account to decide this translation.",
          correlationId);
    }
    if (!isJson(request.getContentType())) {
      return problem(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "UNSUPPORTED_MEDIA_TYPE",
          "Send the reviewer decision as JSON.",
          correlationId);
    }

    final UUID parsedSubmissionId;
    try {
      parsedSubmissionId = UUID.fromString(submissionId);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "TRANSLATION_REVIEW_SUBMISSION_ID_INVALID",
          "submissionId must be a UUID.",
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
          "Reload the snapshot detail and send its ETag in If-Match.",
          correlationId);
    }
    if (!REVIEW_ETAG.matcher(ifMatch).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong translation-review ETag.",
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
          "Provide an actionCode and, for rejection, a reasonCode.",
          correlationId);
    }
    if (body.length > MAX_BODY_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "REQUEST_TOO_LARGE",
          "The reviewer decision body may not exceed 2 KiB.",
          correlationId);
    }

    TranslationReviewDecisionService.DecisionCommand command = parseDecision(body);
    if (command == null) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "TRANSLATION_REVIEW_DECISION_INVALID",
          "Provide a supported actionCode and matching allow-listed reasonCode without extra fields.",
          correlationId);
    }

    try {
      TranslationReviewDecisionService.DecisionResult result =
          decisionService.decide(
              principal.userId(), parsedSubmissionId, ifMatch, idempotencyKey, command);
      return response(result, correlationId);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATION_REVIEW_DECISION_UNAVAILABLE",
          "The decision could not be safely recorded. Retry with the same Idempotency-Key.",
          correlationId);
    }
  }

  private TranslationReviewDecisionService.DecisionCommand parseDecision(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root == null || !root.isObject()) return null;
      Set<String> fields = new HashSet<>(root.propertyNames());
      if (fields.isEmpty() || !BODY_FIELDS.containsAll(fields) || !fields.contains("actionCode")) {
        return null;
      }
      JsonNode actionNode = root.get("actionCode");
      JsonNode reasonNode = root.get("reasonCode");
      if (actionNode == null || !actionNode.isTextual()) return null;
      String action = actionNode.textValue();
      if (reasonNode != null && !reasonNode.isNull() && !reasonNode.isTextual()) return null;
      String reason = reasonNode == null || reasonNode.isNull() ? null : reasonNode.textValue();
      boolean valid =
          switch (action) {
            case "ACCEPT" -> reason == null || ACCEPT_REASONS.contains(reason);
            case "REJECT" -> reason != null && REJECT_REASONS.contains(reason);
            default -> false;
          };
      return valid ? new TranslationReviewDecisionService.DecisionCommand(action, reason) : null;
    } catch (JacksonException exception) {
      return null;
    }
  }

  private static ResponseEntity<?> response(
      TranslationReviewDecisionService.DecisionResult result, UUID correlationId) {
    return switch (result.outcome()) {
      case CREATED ->
          noStore(HttpStatus.CREATED)
              .header(
                  HttpHeaders.ETAG,
                  TranslationReviewSnapshotHasher.etag(
                      java.util.HexFormat.of().parseHex(result.receipt().snapshotDigest())))
              .body(
                  new DecisionResponse(
                      result.receipt().decisionId(),
                      result.receipt().submissionId(),
                      result.receipt().actionCode(),
                      result.receipt().reasonCode(),
                      result.receipt().snapshotDigest(),
                      result.receipt().decidedAt(),
                      false));
      case REPLAYED ->
          noStore(HttpStatus.OK)
              .header(
                  HttpHeaders.ETAG,
                  TranslationReviewSnapshotHasher.etag(
                      java.util.HexFormat.of().parseHex(result.receipt().snapshotDigest())))
              .body(
                  new DecisionResponse(
                      result.receipt().decisionId(),
                      result.receipt().submissionId(),
                      result.receipt().actionCode(),
                      result.receipt().reasonCode(),
                      result.receipt().snapshotDigest(),
                      result.receipt().decidedAt(),
                      true));
      case NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "TRANSLATION_REVIEW_UNAVAILABLE",
              "This translation review is not available.",
              correlationId);
      case POLICY_REQUIRED ->
          problem(
              HttpStatus.FORBIDDEN,
              "POLICY_ACCEPTANCE_REQUIRED",
              "Accept the current Terms and Privacy Notice before reviewing translations.",
              correlationId);
      case POLICY_NOT_CONFIGURED ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "POLICY_NOT_CONFIGURED",
              "Current required policy documents are not available.",
              correlationId);
      case KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "This Idempotency-Key was already used for a different decision.",
              correlationId);
      case STALE_ETAG ->
          problemWithEtag(
              HttpStatus.valueOf(412),
              "TRANSLATION_REVIEW_ETAG_STALE",
              "The submitted snapshot changed. Reload the reviewer detail before deciding.",
              result.currentEtag(),
              correlationId);
      case INVALID_STATE ->
          problemWithEtag(
              HttpStatus.CONFLICT,
              "TRANSLATION_REVIEW_ALREADY_DECIDED",
              "This snapshot already has a final review decision.",
              result.currentEtag(),
              correlationId);
      case STALE_SOURCE ->
          problemWithEtag(
              HttpStatus.CONFLICT,
              "TRANSLATION_REVIEW_SOURCE_STALE",
              "The source changed after this snapshot was submitted. Return to the queue.",
              result.currentEtag(),
              correlationId);
      case STORE_UNAVAILABLE ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "TRANSLATION_REVIEW_SNAPSHOT_UNAVAILABLE",
              "The submitted translation snapshot could not be verified safely.",
              correlationId);
    };
  }

  private static ResponseEntity<?> problemWithEtag(
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

  private static ResponseEntity<?> problem(
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
    if (value instanceof String text) {
      try {
        return UUID.fromString(text);
      } catch (IllegalArgumentException exception) {
        return UUID.randomUUID();
      }
    }
    return UUID.randomUUID();
  }

  public record DecisionResponse(
      UUID decisionId,
      UUID submissionId,
      String actionCode,
      String reasonCode,
      String snapshotDigest,
      java.time.Instant decidedAt,
      boolean replayed) {}
}
