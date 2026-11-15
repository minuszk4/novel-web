package com.novelweb.payment.credit;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.payment.credit.CommerceHistoryRepository.ChapterPurchase;
import com.novelweb.payment.credit.CommerceHistoryRepository.PaymentOrder;
import com.novelweb.payment.credit.CreditTopUpOrderService.OrderStatus;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Exposes separately paged, read-only account commerce history. */
@RestController
@RequestMapping("/api/v1/me")
@Profile("postgres")
public class CommerceHistoryController {

  private final CommerceHistoryService historyService;
  private final PolicyService policyService;

  public CommerceHistoryController(
      CommerceHistoryService historyService, PolicyService policyService) {
    this.historyService = historyService;
    this.policyService = policyService;
  }

  @GetMapping("/payment-orders")
  public ResponseEntity<?> paymentOrders(
      Authentication authentication,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String state,
      @RequestParam(defaultValue = "25") int limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to view payment history.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (!validLimit(limit)) return invalidLimit(request);
    String normalizedState = normalizeState(state);
    if (state != null && normalizedState == null) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_PAYMENT_ORDER_STATE",
          "Choose a supported payment-order state.",
          request);
    }
    try {
      Optional<CommerceHistoryService.HistoryPage<PaymentOrder>> result =
          historyService.paymentOrders(actor.orElseThrow(), normalizedState, cursor, limit);
      if (result.isEmpty()) return inactiveAccount(request);
      CommerceHistoryService.HistoryPage<PaymentOrder> page = result.orElseThrow();
      return ok(new PaymentOrdersResponse(page.items(), page.nextCursor()));
    } catch (CommerceHistoryCursorCodec.InvalidCursorException exception) {
      return invalidCursor(request);
    } catch (CommerceHistoryCursorCodec.SigningUnavailableException exception) {
      return cursorUnavailable(request);
    } catch (IllegalStateException exception) {
      return historyUnavailable(request);
    }
  }

  @GetMapping("/purchases")
  public ResponseEntity<?> purchases(
      Authentication authentication,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") int limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view chapter purchases.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (!validLimit(limit)) return invalidLimit(request);
    try {
      Optional<CommerceHistoryService.HistoryPage<ChapterPurchase>> result =
          historyService.purchases(actor.orElseThrow(), cursor, limit);
      if (result.isEmpty()) return inactiveAccount(request);
      CommerceHistoryService.HistoryPage<ChapterPurchase> page = result.orElseThrow();
      return ok(new PurchasesResponse(page.items(), page.nextCursor()));
    } catch (CommerceHistoryCursorCodec.InvalidCursorException exception) {
      return invalidCursor(request);
    } catch (CommerceHistoryCursorCodec.SigningUnavailableException exception) {
      return cursorUnavailable(request);
    } catch (IllegalStateException exception) {
      return historyUnavailable(request);
    }
  }

  private ResponseEntity<?> policyGate(UUID actorId, HttpServletRequest request) {
    try {
      return switch (policyService.requiredAcceptance(actorId)) {
        case COMPLETE -> null;
        case REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Review and accept the current Terms and Privacy Notice before viewing purchase history.",
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

  private static String normalizeState(String state) {
    if (state == null) return null;
    if (state.length() > 16) return null;
    try {
      return OrderStatus.valueOf(state.toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static boolean validLimit(int limit) {
    return limit >= 1 && limit <= 50;
  }

  private static ResponseEntity<?> invalidLimit(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_PAGE_LIMIT",
        "Page limit must be between 1 and 50.",
        request);
  }

  private static ResponseEntity<?> inactiveAccount(HttpServletRequest request) {
    return problem(
        HttpStatus.UNAUTHORIZED,
        "UNAUTHENTICATED",
        "The application account is no longer active.",
        request);
  }

  private static ResponseEntity<?> invalidCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_CURSOR",
        "This commerce history cursor is invalid or no longer usable.",
        request);
  }

  private static ResponseEntity<?> cursorUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMMERCE_HISTORY_CURSOR_UNAVAILABLE",
        "Purchase history pagination is temporarily unavailable.",
        request);
  }

  private static ResponseEntity<?> historyUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMMERCE_HISTORY_UNAVAILABLE",
        "Purchase history could not be loaded. Please try again.",
        request);
  }

  private static ResponseEntity<?> ok(Object body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
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
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(status.getReasonPhrase());
    body.setProperty("code", code);
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    body.setProperty("correlationId", value instanceof String id ? id : "unavailable");
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> invalidParameter(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_PAGE_LIMIT",
        "Page parameters must be valid integers.",
        request);
  }

  public record PaymentOrdersResponse(List<PaymentOrder> items, String nextCursor) {
    public PaymentOrdersResponse {
      items = List.copyOf(items);
    }
  }

  public record PurchasesResponse(List<ChapterPurchase> items, String nextCursor) {
    public PurchasesResponse {
      items = List.copyOf(items);
    }
  }
}
