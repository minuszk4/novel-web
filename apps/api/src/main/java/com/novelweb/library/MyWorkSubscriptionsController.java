package com.novelweb.library;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.library.WorkSubscriptionListService.InvalidLimitException;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Actor-owned subscription controls for Settings. */
@RestController
@RequestMapping("/api/v1/me/subscriptions")
@Profile("postgres")
public class MyWorkSubscriptionsController {

  private final WorkSubscriptionListService service;
  private final PolicyService policyService;

  public MyWorkSubscriptionsController(
      WorkSubscriptionListService service, PolicyService policyService) {
    this.service = service;
    this.policyService = policyService;
  }

  @GetMapping
  public ResponseEntity<?> list(
      Authentication authentication,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") String limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to manage followed works.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;

    int pageSize;
    try {
      pageSize = Integer.parseInt(limit);
    } catch (NumberFormatException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_SUBSCRIPTION_LIMIT",
          "Limit must be a whole number between 1 and 50.",
          request);
    }
    if (pageSize < 1 || pageSize > 50) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_SUBSCRIPTION_LIMIT",
          "Limit must be a whole number between 1 and 50.",
          request);
    }
    WorkSubscriptionListService.Page page = service.list(actor.orElseThrow(), cursor, pageSize);
    List<SubscriptionItemView> items =
        page.items().stream()
            .map(
                item ->
                    new SubscriptionItemView(
                        item.id(),
                        item.workId(),
                        item.available(),
                        item.notificationsEnabled(),
                        item.version(),
                        item.followedAt(),
                        item.title(),
                        item.author()))
            .toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(new SubscriptionPageView(items, page.nextCursor()));
  }

  @ExceptionHandler(InvalidLimitException.class)
  public ResponseEntity<ProblemDetail> invalidLimit(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_SUBSCRIPTION_LIMIT",
        "Limit must be a whole number between 1 and 50.",
        request);
  }

  @ExceptionHandler(LibraryCursorCodec.InvalidCursorException.class)
  public ResponseEntity<ProblemDetail> invalidCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_SUBSCRIPTION_CURSOR",
        "Reload the followed-work list and try again.",
        request);
  }

  @ExceptionHandler(LibraryCursorCodec.SigningUnavailableException.class)
  public ResponseEntity<ProblemDetail> cursorSigningUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "SUBSCRIPTION_LIST_UNAVAILABLE",
        "Followed works could not be loaded safely. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before managing followed works.",
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

  private static Optional<UUID> actorUserId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", value instanceof String id ? id : "unavailable");
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(problem);
  }

  public record SubscriptionPageView(List<SubscriptionItemView> items, String nextCursor) {
    public SubscriptionPageView {
      items = List.copyOf(items);
    }
  }

  public record SubscriptionItemView(
      UUID id,
      UUID workId,
      boolean available,
      boolean notificationsEnabled,
      long version,
      Instant followedAt,
      String title,
      String author) {}
}
