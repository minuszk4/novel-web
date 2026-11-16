package com.novelweb.library.publication;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
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

/** Serves assigned reviewers a private, content-redacted publication review queue. */
@RestController
@RequestMapping("/api/v1/review/publications")
@Profile("postgres")
public class PublicationReviewQueryController {

  private static final int DEFAULT_PAGE_SIZE = 20;

  private final PublicationReviewQueryService reviewService;

  public PublicationReviewQueryController(PublicationReviewQueryService reviewService) {
    this.reviewService = reviewService;
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
          "Sign in with an assigned reviewer account to view publication requests.",
          correlationId);
    }

    final int limit;
    try {
      limit = rawLimit == null ? DEFAULT_PAGE_SIZE : Integer.parseInt(rawLimit);
    } catch (NumberFormatException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_REVIEW_LIMIT_INVALID",
          "limit must be an integer between 1 and 50.",
          correlationId);
    }

    try {
      PublicationReviewQueryService.QueueResult result =
          reviewService.queue(principal.userId(), cursor, limit);
      return switch (result.outcome()) {
        case AVAILABLE -> ok(result.page());
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
        case INVALID_LIMIT ->
            problem(
                HttpStatus.BAD_REQUEST,
                "PUBLICATION_REVIEW_LIMIT_INVALID",
                "limit must be an integer between 1 and 50.",
                correlationId);
        case INVALID_CURSOR ->
            problem(
                HttpStatus.BAD_REQUEST,
                "PUBLICATION_REVIEW_CURSOR_INVALID",
                "The cursor is malformed, tampered or belongs to another reviewer queue.",
                correlationId);
        case CURSOR_UNAVAILABLE ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PUBLICATION_REVIEW_CURSOR_UNAVAILABLE",
                "Review queue pagination is temporarily unavailable.",
                correlationId);
        case NOT_FOUND ->
            problem(
                HttpStatus.NOT_FOUND,
                "PUBLICATION_REVIEW_REQUEST_NOT_FOUND",
                "The publication request is not available.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_REVIEW_QUEUE_UNAVAILABLE",
          "The publication review queue could not be loaded.",
          correlationId);
    }
  }

  @GetMapping("/{requestId}")
  public ResponseEntity<?> detail(
      Authentication authentication, @PathVariable String requestId, HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in with an assigned reviewer account to view this request.",
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

    try {
      PublicationReviewQueryService.DetailResult result =
          reviewService.detail(principal.userId(), parsedRequestId);
      return switch (result.outcome()) {
        case AVAILABLE -> ok(result.detail(), result.etag());
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
        default ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PUBLICATION_REVIEW_REQUEST_UNAVAILABLE",
                "The publication request could not be loaded.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_REVIEW_REQUEST_UNAVAILABLE",
          "The publication request could not be loaded.",
          correlationId);
    }
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
