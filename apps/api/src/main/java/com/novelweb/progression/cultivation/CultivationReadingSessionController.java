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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/reading-sessions")
@Profile("postgres")
public class CultivationReadingSessionController {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(CultivationReadingSessionController.class);

  private final CultivationReadingSessionService service;
  private final PolicyService policyService;
  private final boolean featureEnabled;

  public CultivationReadingSessionController(
      CultivationReadingSessionService service,
      PolicyService policyService,
      @Value("\u0024{novel.features.cultivation-enabled:false}") boolean featureEnabled) {
    this.service = service;
    this.policyService = policyService;
    this.featureEnabled = featureEnabled;
  }

  @PostMapping
  public ResponseEntity<?> start(
      Authentication authentication,
      @RequestBody(required = false) CultivationReadingSessionService.StartRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in before starting a cultivation reading session.",
          request);
    }
    ResponseEntity<?> unavailable = featureAvailability(request);
    if (unavailable != null) return unavailable;
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    CultivationReadingSessionService.StartOutcome outcome =
        service.start(actor.orElseThrow(), body, idempotencyKey);
    return ok(
        outcome.session(),
        outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED,
        outcome.replayed());
  }

  @PostMapping("/{sessionId}:heartbeat")
  public ResponseEntity<?> heartbeat(
      Authentication authentication,
      @PathVariable UUID sessionId,
      @RequestBody(required = false) CultivationReadingSessionService.HeartbeatRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in before sending a reading heartbeat.",
          request);
    }
    ResponseEntity<?> unavailable = featureAvailability(request);
    if (unavailable != null) return unavailable;
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    CultivationReadingSessionService.HeartbeatOutcome outcome =
        service.heartbeat(actor.orElseThrow(), sessionId, body, idempotencyKey);
    String state = outcome.heartbeat().state();
    if ("closed".equals(state) || "expired".equals(state) || "revoked".equals(state)) {
      return problem(
          HttpStatus.GONE,
          "READING_SESSION_" + state.toUpperCase(),
          "This reading session is no longer active. Start a new session from an accessible chapter.",
          request);
    }
    return ok(outcome.heartbeat(), HttpStatus.OK, outcome.replayed());
  }

  @PostMapping("/{sessionId}:close")
  public ResponseEntity<?> close(
      Authentication authentication, @PathVariable UUID sessionId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in before closing a cultivation reading session.",
          request);
    }
    ResponseEntity<?> unavailable = featureAvailability(request);
    if (unavailable != null) return unavailable;
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    return ok(service.close(actor.orElseThrow(), sessionId), HttpStatus.OK, false);
  }

  @ExceptionHandler(CultivationReadingSessionService.InvalidReadingSessionRequestException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(
      CultivationReadingSessionService.InvalidReadingSessionRequestException exception,
      HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, "INVALID_READING_SESSION_REQUEST", exception.getMessage(), request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "The reading-session request body is missing or malformed.",
        request);
  }

  @ExceptionHandler(CultivationReadingSessionRepository.ActiveReadingSessionExistsException.class)
  public ResponseEntity<ProblemDetail> activeSessionExists(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "READING_SESSION_ALREADY_ACTIVE",
        "Close the current reading session before starting another one.",
        request);
  }

  @ExceptionHandler(CultivationReadingSessionRepository.IdempotencyKeyReusedException.class)
  public ResponseEntity<ProblemDetail> idempotencyConflict(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "The Idempotency-Key was already used for a different reading-session request.",
        request);
  }

  @ExceptionHandler(CultivationReadingSessionRepository.HeartbeatSequenceConflictException.class)
  public ResponseEntity<ProblemDetail> sequenceConflict(
      CultivationReadingSessionRepository.HeartbeatSequenceConflictException exception,
      HttpServletRequest request) {
    ProblemDetail problem =
        problemDetail(
            HttpStatus.CONFLICT,
            "HEARTBEAT_SEQUENCE_CONFLICT",
            "Heartbeat sequence is out of order. Retry from the expected sequence.",
            request);
    problem.setProperty("expectedSequence", exception.expectedSequence());
    return problemResponse(HttpStatus.CONFLICT, problem);
  }

  @ExceptionHandler(CultivationReadingSessionRepository.CultivationProfileNotEnabledException.class)
  public ResponseEntity<ProblemDetail> cultivationNotEnabled(HttpServletRequest request) {
    return problem(
        HttpStatus.FORBIDDEN,
        "CULTIVATION_OPT_IN_REQUIRED",
        "Enable private cultivation progression before starting a reading session.",
        request);
  }

  @ExceptionHandler(CultivationReadingSessionRepository.ReaderResourceUnavailableException.class)
  public ResponseEntity<ProblemDetail> readerResourceUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "READER_RESOURCE_UNAVAILABLE",
        "This chapter is not currently accessible in the Reader.",
        request);
  }

  @ExceptionHandler(CultivationReadingSessionRepository.ReadingSessionNotFoundException.class)
  public ResponseEntity<ProblemDetail> sessionNotFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "READING_SESSION_NOT_FOUND",
        "The reading session is unavailable.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(
      IllegalStateException exception, HttpServletRequest request) {
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    LOGGER.error(
        "Cultivation reading-session request failed (correlationId={})",
        correlationId instanceof String id ? id : "unavailable",
        exception);
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "READING_SESSION_UNAVAILABLE",
        "The reading session could not be processed. Please try again.",
        request);
  }

  private ResponseEntity<?> featureAvailability(HttpServletRequest request) {
    if (featureEnabled) return null;
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CULTIVATION_NOT_ENABLED",
        "Cultivation reading sessions are not available yet.",
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

  private static ResponseEntity<?> ok(Object body, HttpStatus status, boolean replayed) {
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(status)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache");
    if (replayed) builder.header("Idempotency-Replayed", "true");
    return builder.body(body);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return problemResponse(status, problemDetail(status, code, detail, request));
  }

  private static ProblemDetail problemDetail(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", correlationId instanceof String id ? id : "unavailable");
    return problem;
  }

  private static ResponseEntity<ProblemDetail> problemResponse(
      HttpStatus status, ProblemDetail body) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }
}
