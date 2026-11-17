package com.novelweb.translation.jobs;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves assigned reviewers the private queue and exact submitted manual-edit snapshot. */
@RestController
@RequestMapping("/api/v1/review/translations")
@Profile("postgres & translation-fake")
@ConditionalOnProperty(prefix = "novel.translation.review", name = "enabled", havingValue = "true")
public class TranslationReviewQueryController {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private final TranslationReviewQueryService queryService;

  public TranslationReviewQueryController(TranslationReviewQueryService queryService) {
    this.queryService = queryService;
  }

  @GetMapping
  public ResponseEntity<?> queue(
      Authentication authentication,
      @RequestParam(name = "limit", required = false) String rawLimit,
      @RequestParam(name = "cursor", required = false) String cursor,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in with an assigned reviewer account to view translation reviews.",
          correlationId);
    }

    final int limit;
    try {
      limit = rawLimit == null ? DEFAULT_PAGE_SIZE : Integer.parseInt(rawLimit);
    } catch (NumberFormatException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "TRANSLATION_REVIEW_LIMIT_INVALID",
          "limit must be an integer between 1 and 50.",
          correlationId);
    }

    try {
      TranslationReviewQueryService.QueueResult result =
          queryService.queue(principal.userId(), cursor, limit);
      return switch (result.outcome()) {
        case AVAILABLE -> ok(result.page());
        case REVIEWER_REQUIRED -> unavailable(correlationId);
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
        case INVALID_LIMIT ->
            problem(
                HttpStatus.BAD_REQUEST,
                "TRANSLATION_REVIEW_LIMIT_INVALID",
                "limit must be an integer between 1 and 50.",
                correlationId);
        case INVALID_CURSOR ->
            problem(
                HttpStatus.BAD_REQUEST,
                "TRANSLATION_REVIEW_CURSOR_INVALID",
                "The cursor is malformed, tampered or belongs to another reviewer.",
                correlationId);
        case CURSOR_UNAVAILABLE ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TRANSLATION_REVIEW_CURSOR_UNAVAILABLE",
                "Review queue pagination is temporarily unavailable.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATION_REVIEW_QUEUE_UNAVAILABLE",
          "The translation review queue could not be loaded.",
          correlationId);
    }
  }

  @GetMapping("/{submissionId}")
  public ResponseEntity<?> detail(
      Authentication authentication,
      @PathVariable String submissionId,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in with an assigned reviewer account to open this translation review.",
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

    try {
      TranslationReviewQueryService.DetailResult result =
          queryService.detail(principal.userId(), parsedSubmissionId);
      return switch (result.outcome()) {
        case AVAILABLE -> ok(result.detail(), result.etag());
        case REVIEWER_REQUIRED, NOT_FOUND -> unavailable(correlationId);
        case STALE_SOURCE ->
            problem(
                HttpStatus.CONFLICT,
                "TRANSLATION_REVIEW_SOURCE_STALE",
                "The source changed after this snapshot was submitted. Return to the queue.",
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
        case UNAVAILABLE ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TRANSLATION_REVIEW_DETAIL_UNAVAILABLE",
                "The submitted translation snapshot could not be verified.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATION_REVIEW_DETAIL_UNAVAILABLE",
          "The translation review detail could not be loaded.",
          correlationId);
    }
  }

  private static ResponseEntity<?> unavailable(UUID correlationId) {
    return problem(
        HttpStatus.NOT_FOUND,
        "TRANSLATION_REVIEW_UNAVAILABLE",
        "This translation review is not available.",
        correlationId);
  }

  private static ResponseEntity<?> ok(Object body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  private static ResponseEntity<?> ok(Object body, String etag) {
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, etag)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(status.getReasonPhrase());
    body.setProperty("code", code);
    body.setProperty("correlationId", correlationId.toString());
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
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
    if (value instanceof UUID id) return id;
    if (value instanceof String text) {
      try {
        return UUID.fromString(text);
      } catch (IllegalArgumentException exception) {
        return UUID.randomUUID();
      }
    }
    return UUID.randomUUID();
  }
}
