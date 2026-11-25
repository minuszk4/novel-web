package com.novelweb.library;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.library.NotificationPreferencesRepository.StalePreferencesException;
import com.novelweb.library.NotificationPreferencesRepository.StoreUnavailableException;
import com.novelweb.library.NotificationPreferencesService.EmailDigestUnavailableException;
import com.novelweb.library.NotificationPreferencesService.InvalidPreferencesException;
import com.novelweb.library.NotificationPreferencesService.Update;
import com.novelweb.library.NotificationPreferencesService.View;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Actor-owned email digest schedule preferences; email delivery is deliberately unavailable. */
@RestController
@RequestMapping("/api/v1/me/notification-preferences")
@Profile("postgres")
public class NotificationPreferencesController {

  private final NotificationPreferencesService service;
  private final PolicyService policyService;

  public NotificationPreferencesController(
      NotificationPreferencesService service, PolicyService policyService) {
    this.service = service;
    this.policyService = policyService;
  }

  @GetMapping
  public ResponseEntity<?> get(Authentication authentication, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view notification preferences.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    return state(service.get(actor.orElseThrow()));
  }

  @PutMapping
  public ResponseEntity<?> put(
      Authentication authentication,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) NotificationPreferencesRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to change notification preferences.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null || body.hasUnknownFields()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_NOTIFICATION_PREFERENCES",
          "Provide only emailDigestEnabled, frequency, timeZone, sendLocalTime and consentVersion.",
          request);
    }
    long expectedVersion = parseEtag(ifMatch);
    Update update =
        new Update(
            body.emailDigestEnabled(),
            body.frequency(),
            body.timeZone(),
            body.sendLocalTime(),
            body.consentVersion());
    return state(service.update(actor.orElseThrow(), expectedVersion, update));
  }

  @ExceptionHandler(EmailDigestUnavailableException.class)
  public ResponseEntity<ProblemDetail> channelUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CHANNEL_UNAVAILABLE",
        "Email digest is not configured. Your preference was not enabled or saved.",
        request);
  }

  @ExceptionHandler(InvalidPreferencesException.class)
  public ResponseEntity<ProblemDetail> invalidPreferences(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_NOTIFICATION_PREFERENCES",
        "Choose a supported frequency, an IANA time zone and a local send time in HH:mm format.",
        request);
  }

  @ExceptionHandler(StalePreferencesException.class)
  public ResponseEntity<ProblemDetail> stale(HttpServletRequest request) {
    return problem(
        HttpStatus.PRECONDITION_FAILED,
        "NOTIFICATION_PREFERENCES_CHANGED",
        "Reload the current notification preferences before trying again.",
        request);
  }

  @ExceptionHandler(PreconditionRequiredException.class)
  public ResponseEntity<ProblemDetail> preconditionRequired(HttpServletRequest request) {
    return problem(
        HttpStatus.PRECONDITION_REQUIRED,
        "NOTIFICATION_PREFERENCES_VERSION_REQUIRED",
        "Read the current preferences and provide its ETag in If-Match.",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidEtag(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_NOTIFICATION_PREFERENCES_VERSION",
        "If-Match must contain the current notification-preferences ETag.",
        request);
  }

  @ExceptionHandler(StoreUnavailableException.class)
  public ResponseEntity<ProblemDetail> storeUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "NOTIFICATION_PREFERENCES_UNAVAILABLE",
        "Notification preferences could not be safely loaded or saved. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before changing notification preferences.",
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

  private static ResponseEntity<View> state(View view) {
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, etag(view.version()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(view);
  }

  private static long parseEtag(String value) {
    if (value == null || value.isBlank()) throw new PreconditionRequiredException();
    if ("\"notification-preferences-absent\"".equals(value)) return 0;
    String prefix = "\"notification-preferences-";
    if (!value.startsWith(prefix) || !value.endsWith("\"")) {
      throw new IllegalArgumentException("If-Match must contain the notification-preferences ETag");
    }
    try {
      long version = Long.parseLong(value.substring(prefix.length(), value.length() - 1));
      if (version < 1) throw new NumberFormatException();
      return version;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("If-Match must contain the notification-preferences ETag");
    }
  }

  private static String etag(long version) {
    return version == 0
        ? "\"notification-preferences-absent\""
        : "\"notification-preferences-" + version + "\"";
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

  public static final class NotificationPreferencesRequest {
    private Boolean emailDigestEnabled;
    private String frequency;
    private String timeZone;
    private String sendLocalTime;
    private String consentVersion;
    private boolean unknownFields;

    public Boolean emailDigestEnabled() {
      return emailDigestEnabled;
    }

    public void setEmailDigestEnabled(Boolean value) {
      emailDigestEnabled = value;
    }

    public String frequency() {
      return frequency;
    }

    public void setFrequency(String value) {
      frequency = value;
    }

    public String timeZone() {
      return timeZone;
    }

    public void setTimeZone(String value) {
      timeZone = value;
    }

    public String sendLocalTime() {
      return sendLocalTime;
    }

    public void setSendLocalTime(String value) {
      sendLocalTime = value;
    }

    public String consentVersion() {
      return consentVersion;
    }

    public void setConsentVersion(String value) {
      consentVersion = value;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  private static final class PreconditionRequiredException extends RuntimeException {}
}
