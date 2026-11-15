package com.novelweb.payment.credit;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exposes read-only credit ledger history for the current account. */
@RestController
@RequestMapping("/api/v1/me/credits")
@Profile("postgres")
public class CreditHistoryController {

  private final CreditHistoryService historyService;
  private final PolicyService policyService;

  public CreditHistoryController(CreditHistoryService historyService, PolicyService policyService) {
    this.historyService = historyService;
    this.policyService = policyService;
  }

  @GetMapping("/transactions")
  public ResponseEntity<?> transactions(
      Authentication authentication,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") int limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view credit transactions.",
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
    if (limit < 1 || limit > 50) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_PAGE_LIMIT",
          "Page limit must be between 1 and 50.",
          request);
    }
    try {
      Optional<CreditHistoryService.HistoryPage> result =
          historyService.list(actor.orElseThrow(), cursor, limit);
      if (result.isEmpty()) {
        return problem(
            HttpStatus.UNAUTHORIZED,
            "UNAUTHENTICATED",
            "The application account is no longer active.",
            request);
      }
      CreditHistoryService.HistoryPage page = result.orElseThrow();
      List<CreditTransaction> items =
          page.items().stream()
              .map(
                  entry ->
                      new CreditTransaction(
                          entry.id(),
                          entry.entryType(),
                          entry.bucket(),
                          entry.amountDelta(),
                          entry.expiresAt(),
                          entry.sourceType(),
                          entry.occurredAt()))
              .toList();
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .header(HttpHeaders.PRAGMA, "no-cache")
          .body(new CreditHistoryResponse(items, page.nextCursor()));
    } catch (CreditHistoryCursorCodec.InvalidCursorException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_CURSOR",
          "This credit history cursor is invalid or no longer usable.",
          request);
    } catch (CreditHistoryCursorCodec.SigningUnavailableException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CREDIT_HISTORY_CURSOR_UNAVAILABLE",
          "Credit history pagination is temporarily unavailable.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CREDIT_HISTORY_UNAVAILABLE",
          "Credit transaction history could not be loaded. Please try again.",
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

  public record CreditHistoryResponse(List<CreditTransaction> items, String nextCursor) {
    public CreditHistoryResponse {
      items = List.copyOf(items);
    }
  }

  public record CreditTransaction(
      UUID id,
      String entryType,
      String bucket,
      java.math.BigInteger amountDelta,
      java.time.Instant expiresAt,
      String sourceType,
      java.time.Instant occurredAt) {}
}
