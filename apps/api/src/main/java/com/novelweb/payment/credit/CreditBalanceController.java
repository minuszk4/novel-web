package com.novelweb.payment.credit;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/credits")
@Profile("postgres")
public class CreditBalanceController {

  private final CreditBalanceRepository creditBalanceRepository;
  private final PolicyService policyService;

  public CreditBalanceController(
      CreditBalanceRepository creditBalanceRepository, PolicyService policyService) {
    this.creditBalanceRepository = creditBalanceRepository;
    this.policyService = policyService;
  }

  @GetMapping("/balance")
  public ResponseEntity<?> balance(Authentication authentication, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view your credit balance.",
          request);
    }
    final ResponseEntity<?> policyGate;
    try {
      policyGate = policyGate(actor.orElseThrow(), request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "POLICY_STORE_UNAVAILABLE",
          "Current policy state could not be checked. Please try again.",
          request);
    }
    if (policyGate != null) return policyGate;
    try {
      return creditBalanceRepository
          .findAvailable(actor.orElseThrow())
          .<ResponseEntity<?>>map(
              balance ->
                  ResponseEntity.ok()
                      .cacheControl(CacheControl.noStore())
                      .header(HttpHeaders.PRAGMA, "no-cache")
                      .body(
                          new CreditBalanceResponse(
                              balance.paidCredits(),
                              balance.bonusCredits(),
                              balance.totalCredits(),
                              balance.asOf())))
          .orElseGet(
              () ->
                  problem(
                      HttpStatus.UNAUTHORIZED,
                      "UNAUTHENTICATED",
                      "The application account is no longer active.",
                      request));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CREDIT_BALANCE_UNAVAILABLE",
          "The credit balance could not be loaded. Please try again.",
          request);
    }
  }

  private ResponseEntity<?> policyGate(UUID actorUserId, HttpServletRequest request) {
    return switch (policyService.requiredAcceptance(actorUserId)) {
      case COMPLETE -> null;
      case REQUIRED ->
          problem(
              HttpStatus.FORBIDDEN,
              "POLICY_ACCEPTANCE_REQUIRED",
              "Review and accept the current Terms and Privacy Notice before viewing credits.",
              request);
      case NOT_CONFIGURED ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "POLICY_NOT_CONFIGURED",
              "Current approved policy documents are not available.",
              request);
    };
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
    problem.setProperty("correlationId", correlationId(request).toString());
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
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

  public record CreditBalanceResponse(
      BigInteger paidCredits, BigInteger bonusCredits, BigInteger totalCredits, Instant asOf) {}
}
