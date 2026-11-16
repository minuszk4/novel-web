package com.novelweb.library.publication;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reads a content-free publication event timeline for the active manuscript owner. */
@RestController
@RequestMapping("/api/v1/authoring/manuscripts/{workId}/publication/requests/history")
@Profile("postgres")
public class PublicationRequestHistoryController {

  private static final int DEFAULT_PAGE_SIZE = 20;

  private final PublicationRequestHistoryService historyService;

  public PublicationRequestHistoryController(PublicationRequestHistoryService historyService) {
    this.historyService = historyService;
  }

  @GetMapping
  public ResponseEntity<?> history(
      Authentication authentication,
      @PathVariable UUID workId,
      @RequestParam(name = "limit", required = false) String rawLimit,
      @RequestParam(name = "cursor", required = false) String cursor,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view publication review history for this manuscript.",
          correlationId);
    }

    final int limit;
    try {
      limit = rawLimit == null ? DEFAULT_PAGE_SIZE : Integer.parseInt(rawLimit);
    } catch (NumberFormatException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_HISTORY_LIMIT_INVALID",
          "limit must be an integer between 1 and 50.",
          correlationId);
    }

    try {
      PublicationRequestHistoryService.HistoryResult result =
          historyService.history(principal.userId(), workId, cursor, limit);
      return switch (result.outcome()) {
        case AVAILABLE -> noStore(HttpStatus.OK).body(result.page());
        case NOT_FOUND ->
            problem(
                HttpStatus.NOT_FOUND,
                "MANUSCRIPT_NOT_FOUND",
                "The authored manuscript could not be found.",
                correlationId);
        case INVALID_LIMIT ->
            problem(
                HttpStatus.BAD_REQUEST,
                "PUBLICATION_HISTORY_LIMIT_INVALID",
                "limit must be an integer between 1 and 50.",
                correlationId);
        case INVALID_CURSOR ->
            problem(
                HttpStatus.BAD_REQUEST,
                "PUBLICATION_HISTORY_CURSOR_INVALID",
                "The cursor is malformed, tampered or belongs to another manuscript history.",
                correlationId);
        case CURSOR_UNAVAILABLE ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PUBLICATION_HISTORY_CURSOR_UNAVAILABLE",
                "Publication history pagination is temporarily unavailable.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_HISTORY_UNAVAILABLE",
          "Publication request history could not be loaded.",
          correlationId);
    }
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId.toString());
    return noStore(status).body(problem);
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
        // Use a fallback correlation ID when normal request filters are not present.
      }
    }
    return UUID.randomUUID();
  }
}
