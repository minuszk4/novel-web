package com.novelweb.progression.cultivation;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Profile("postgres")
public class CultivationController {

  private static final Logger LOGGER = LoggerFactory.getLogger(CultivationController.class);

  private final CultivationService service;
  private final CultivationActivityService activityService;
  private final PolicyService policyService;
  private final boolean featureEnabled;

  public CultivationController(
      CultivationService service,
      CultivationActivityService activityService,
      PolicyService policyService,
      @Value("${novel.features.cultivation-enabled:false}") boolean featureEnabled) {
    this.service = service;
    this.activityService = activityService;
    this.policyService = policyService;
    this.featureEnabled = featureEnabled;
  }

  @GetMapping("/cultivation")
  public ResponseEntity<?> getProfile(Authentication authentication, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view your cultivation profile.",
          request);
    }
    ResponseEntity<?> unavailable = featureAvailability(request);
    if (unavailable != null) return unavailable;
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    return ok(service.get(actor.orElseThrow()));
  }

  @GetMapping("/cultivation/activity")
  public ResponseEntity<?> getActivity(
      Authentication authentication,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "30") String limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view your cultivation activity.",
          request);
    }
    ResponseEntity<?> unavailable = featureAvailability(request);
    if (unavailable != null) return unavailable;
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    Integer parsedLimit = parseActivityLimit(limit);
    if (parsedLimit == null) return invalidActivityLimit(request);
    return ok(activityService.list(actor.orElseThrow(), cursor, parsedLimit));
  }

  @PutMapping("/cultivation/settings")
  public ResponseEntity<?> saveSettings(
      Authentication authentication,
      @RequestBody(required = false) CultivationService.SettingsRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to change your cultivation settings.",
          request);
    }
    ResponseEntity<?> unavailable = featureAvailability(request);
    if (unavailable != null) return unavailable;
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    return ok(service.saveSettings(actor.orElseThrow(), body));
  }

  @ExceptionHandler(CultivationService.InvalidCultivationSettingsException.class)
  public ResponseEntity<ProblemDetail> invalidSettings(
      CultivationService.InvalidCultivationSettingsException exception,
      HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, "INVALID_CULTIVATION_SETTINGS", exception.getMessage(), request);
  }

  @ExceptionHandler(CultivationActivityCursorCodec.InvalidCursorException.class)
  public ResponseEntity<ProblemDetail> invalidActivityCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_CULTIVATION_ACTIVITY_CURSOR",
        "The activity cursor is malformed or belongs to another account.",
        request);
  }

  @ExceptionHandler(CultivationActivityCursorCodec.SigningUnavailableException.class)
  public ResponseEntity<ProblemDetail> unavailableActivityCursorKey(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CULTIVATION_ACTIVITY_UNAVAILABLE",
        "Cultivation activity pagination is not configured yet.",
        request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "The cultivation settings body is missing or malformed.",
        request);
  }

  @ExceptionHandler(CultivationRepository.CultivationProfileNotFoundException.class)
  public ResponseEntity<ProblemDetail> inactiveUser(HttpServletRequest request) {
    return problem(
        HttpStatus.UNAUTHORIZED,
        "UNAUTHENTICATED",
        "The application session is no longer active.",
        request);
  }

  @ExceptionHandler(CultivationRepository.RankCatalogUnavailableException.class)
  public ResponseEntity<ProblemDetail> unavailableCatalog(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CULTIVATION_CATALOG_UNAVAILABLE",
        "Cultivation rank rules could not be loaded. Please try again.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailableProfile(
      IllegalStateException exception, HttpServletRequest request) {
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    LOGGER.error(
        "Cultivation profile request failed (correlationId={})",
        correlationId instanceof String id ? id : "unavailable",
        exception);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CULTIVATION_PROFILE_UNAVAILABLE",
        "Cultivation settings could not be loaded. Please try again.",
        request);
  }

  private ResponseEntity<?> featureAvailability(HttpServletRequest request) {
    if (featureEnabled) return null;
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CULTIVATION_NOT_ENABLED",
        "Cultivation progression is not available yet.",
        request);
  }

  private static Integer parseActivityLimit(String value) {
    try {
      int limit = Integer.parseInt(value);
      return limit >= 1 && limit <= CultivationActivityService.MAX_PAGE_SIZE ? limit : null;
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private ResponseEntity<ProblemDetail> invalidActivityLimit(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_CULTIVATION_ACTIVITY_LIMIT",
        "The activity page limit must be between 1 and "
            + CultivationActivityService.MAX_PAGE_SIZE
            + ".",
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
                "Review and accept the current Terms and Privacy Notice before using your account.",
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

  private static ResponseEntity<?> ok(Object body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", correlationId instanceof String id ? id : "unavailable");
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }
}
