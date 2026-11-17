package com.novelweb.library;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.library.NotificationService.InvalidLimitException;
import com.novelweb.library.NotificationService.NotificationNotFoundException;
import com.novelweb.library.NotificationService.NotificationStoreUnavailableException;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Actor-owned in-app inbox routes. */
@RestController
@RequestMapping("/api/v1/me/notifications")
@Profile("postgres")
public class NotificationController {

  private final NotificationService notificationService;
  private final PolicyService policyService;

  public NotificationController(
      NotificationService notificationService, PolicyService policyService) {
    this.notificationService = notificationService;
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
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to view notifications.", request);
    }
    ResponseEntity<?> policyFailure = policyGate(actor.orElseThrow(), request);
    if (policyFailure != null) return policyFailure;

    int pageSize;
    try {
      pageSize = Integer.parseInt(limit);
    } catch (NumberFormatException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_NOTIFICATION_LIMIT",
          "Limit must be a whole number between 1 and 50.",
          request);
    }
    NotificationService.Page page = notificationService.list(actor.orElseThrow(), cursor, pageSize);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(page);
  }

  @PostMapping("/{notificationId}/read")
  public ResponseEntity<?> markRead(
      Authentication authentication,
      @PathVariable String notificationId,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to update notifications.", request);
    }
    ResponseEntity<?> policyFailure = policyGate(actor.orElseThrow(), request);
    if (policyFailure != null) return policyFailure;

    UUID id = parseUuid(notificationId);
    if (id == null) {
      return problem(
          HttpStatus.NOT_FOUND,
          "NOTIFICATION_NOT_FOUND",
          "This notification is unavailable.",
          request);
    }
    NotificationService.ReadReceipt receipt = notificationService.markRead(actor.orElseThrow(), id);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(receipt);
  }

  @PostMapping("/read-all")
  public ResponseEntity<?> markAllRead(Authentication authentication, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to update notifications.", request);
    }
    ResponseEntity<?> policyFailure = policyGate(actor.orElseThrow(), request);
    if (policyFailure != null) return policyFailure;

    NotificationService.ReadAllReceipt receipt =
        notificationService.markAllRead(actor.orElseThrow());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(receipt);
  }

  @ExceptionHandler(InvalidLimitException.class)
  public ResponseEntity<ProblemDetail> invalidLimit(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_NOTIFICATION_LIMIT",
        "Limit must be a whole number between 1 and 50.",
        request);
  }

  @ExceptionHandler(LibraryCursorCodec.InvalidCursorException.class)
  public ResponseEntity<ProblemDetail> invalidCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_NOTIFICATION_CURSOR",
        "Reload the notification list and try again.",
        request);
  }

  @ExceptionHandler(LibraryCursorCodec.SigningUnavailableException.class)
  public ResponseEntity<ProblemDetail> cursorSigningUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "NOTIFICATIONS_UNAVAILABLE",
        "Notifications could not be loaded safely. Please try again.",
        request);
  }

  @ExceptionHandler(NotificationNotFoundException.class)
  public ResponseEntity<ProblemDetail> notificationNotFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "NOTIFICATION_NOT_FOUND",
        "This notification is unavailable.",
        request);
  }

  @ExceptionHandler(NotificationStoreUnavailableException.class)
  public ResponseEntity<ProblemDetail> storeUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "NOTIFICATIONS_UNAVAILABLE",
        "Notifications could not be updated safely. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before viewing notifications.",
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

  private static UUID parseUuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      return null;
    }
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
}
