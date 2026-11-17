package com.novelweb.library;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.library.WorkSubscriptionRepository.NotFollowingException;
import com.novelweb.library.WorkSubscriptionRepository.StaleSubscriptionException;
import com.novelweb.library.WorkSubscriptionRepository.SubscriptionNotFoundException;
import com.novelweb.library.WorkSubscriptionRepository.SubscriptionPreconditionRequiredException;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Actor-owned follow and per-work in-app notification preference. */
@RestController
@RequestMapping("/api/v1/works/{workId}/subscription")
@Profile("postgres")
public class WorkSubscriptionController {

  private final WorkSubscriptionService service;
  private final PolicyService policyService;

  public WorkSubscriptionController(WorkSubscriptionService service, PolicyService policyService) {
    this.service = service;
    this.policyService = policyService;
  }

  @GetMapping
  public ResponseEntity<?> get(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to follow this work.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    return state(service.get(actor.orElseThrow(), workId));
  }

  @PutMapping
  public ResponseEntity<?> put(
      Authentication authentication,
      @PathVariable UUID workId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) SubscriptionRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to follow this work.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null || body.hasUnknownFields()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_SUBSCRIPTION_REQUEST",
          "Provide only notificationsEnabled as a boolean.",
          request);
    }
    return state(
        service.setNotifications(
            actor.orElseThrow(), workId, body.notificationsEnabled(), parseEtag(ifMatch)));
  }

  @DeleteMapping
  public ResponseEntity<?> delete(
      Authentication authentication,
      @PathVariable UUID workId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to unfollow this work.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    WorkSubscriptionRepository.SubscriptionState state =
        service.unfollow(actor.orElseThrow(), workId, parseOptionalEtag(ifMatch));
    return ResponseEntity.noContent()
        .header(HttpHeaders.ETAG, etag(state.version()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .build();
  }

  @ExceptionHandler(SubscriptionNotFoundException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "PUBLIC_WORK_UNAVAILABLE",
        "This work is not available for following.",
        request);
  }

  @ExceptionHandler(NotFollowingException.class)
  public ResponseEntity<ProblemDetail> notFollowing(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "NOT_FOLLOWING",
        "Follow the public work before changing its notification setting.",
        request);
  }

  @ExceptionHandler(StaleSubscriptionException.class)
  public ResponseEntity<ProblemDetail> stale(HttpServletRequest request) {
    return problem(
        HttpStatus.PRECONDITION_FAILED,
        "SUBSCRIPTION_CHANGED",
        "Reload the current follow state before trying again.",
        request);
  }

  @ExceptionHandler(SubscriptionPreconditionRequiredException.class)
  public ResponseEntity<ProblemDetail> preconditionRequired(HttpServletRequest request) {
    return problem(
        HttpStatus.PRECONDITION_REQUIRED,
        "SUBSCRIPTION_VERSION_REQUIRED",
        "Read the current subscription and provide its ETag in If-Match.",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_SUBSCRIPTION_REQUEST",
        "The subscription request or If-Match value is invalid.",
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
                "Review and accept the current Terms and Privacy Notice before following works.",
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

  private static ResponseEntity<?> state(WorkSubscriptionRepository.SubscriptionState state) {
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, etag(state.version()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(new SubscriptionView(state.following(), state.notificationsEnabled()));
  }

  private static long parseEtag(String value) {
    if (value == null || value.isBlank()) throw new SubscriptionPreconditionRequiredException();
    if ("\"subscription-absent\"".equals(value)) return 0;
    if (!value.startsWith("\"subscription-") || !value.endsWith("\"")) {
      throw new IllegalArgumentException("If-Match must contain the subscription ETag");
    }
    try {
      long version = Long.parseLong(value.substring(14, value.length() - 1));
      if (version < 1) throw new NumberFormatException();
      return version;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("If-Match must contain the subscription ETag");
    }
  }

  private static Long parseOptionalEtag(String value) {
    return value == null || value.isBlank() ? null : parseEtag(value);
  }

  private static String etag(long version) {
    return version == 0 ? "\"subscription-absent\"" : "\"subscription-" + version + "\"";
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
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }

  public record SubscriptionView(boolean following, boolean notificationsEnabled) {}

  public static final class SubscriptionRequest {
    private Boolean notificationsEnabled;
    private boolean unknownFields;

    public Boolean notificationsEnabled() {
      return notificationsEnabled;
    }

    public void setNotificationsEnabled(Boolean notificationsEnabled) {
      this.notificationsEnabled = notificationsEnabled;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }
}
