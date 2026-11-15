package com.novelweb.payment.credit;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.payment.credit.CreditPackCatalog.CreditPackQuote;
import com.novelweb.payment.credit.CreditTopUpCheckoutService.CheckoutResult;
import com.novelweb.payment.credit.CreditTopUpOrderService.Outcome;
import com.novelweb.payment.credit.CreditTopUpOrderService.TopUpOrder;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Exposes MoMo TEST pack checkout only after the explicit server-side feature opt-in. */
@RestController
@RequestMapping("/api/v1")
@Profile("postgres")
@ConditionalOnProperty(prefix = "novel.payment.momo.test", name = "enabled", havingValue = "true")
public class CreditTopUpController {

  private static final Pattern PACK_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final CreditPackCatalog catalog;
  private final CreditTopUpCheckoutService checkoutService;
  private final CreditTopUpOrderService orderService;
  private final PolicyService policyService;

  public CreditTopUpController(
      CreditPackCatalog catalog,
      CreditTopUpCheckoutService checkoutService,
      CreditTopUpOrderService orderService,
      PolicyService policyService) {
    this.catalog = catalog;
    this.checkoutService = checkoutService;
    this.orderService = orderService;
    this.policyService = policyService;
  }

  @GetMapping("/credit-packs")
  public ResponseEntity<?> creditPacks(Authentication authentication, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view available credit packs.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    try {
      List<CreditPackView> offers =
          catalog.offers().stream().map(CreditTopUpController::view).toList();
      return ok(new CreditPackCatalogResponse("TEST", offers));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CREDIT_PACK_CATALOG_UNAVAILABLE",
          "Credit pack details could not be loaded. Please try again.",
          request);
    }
  }

  @PostMapping(value = "/momo/payment-orders", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> createPaymentOrder(
      Authentication authentication,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) CreateTopUpRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to start a credit top-up.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null
        || body.packId() == null
        || !PACK_ID.matcher(body.packId()).matches()
        || body.packVersion() == null
        || body.packVersion() < 1) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_CREDIT_PACK_REQUEST",
          "Choose a valid credit pack and the version shown in the catalog.",
          request);
    }

    try {
      CheckoutResult result =
          checkoutService.createOrReplay(
              actor.orElseThrow(), idempotencyKey, catalog, body.packId(), body.packVersion());
      if (result.order() == null) return creationProblem(result.creationOutcome(), request);

      HttpStatus status =
          result.creationOutcome() == Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK;
      ResponseEntity.BodyBuilder response =
          ResponseEntity.status(status)
              .location(URI.create("/api/v1/payment-orders/" + result.order().id()))
              .cacheControl(CacheControl.noStore())
              .header(HttpHeaders.PRAGMA, "no-cache");
      if (result.creationOutcome() == Outcome.REPLAYED) {
        response.header("Idempotency-Replayed", "true");
      }
      return response.body(
          new CreatePaymentOrderResponse(
              orderView(result.order()),
              result.checkoutOutcome() == null ? null : result.checkoutOutcome().name(),
              result.checkoutUrl()));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PAYMENT_ORDER_UNAVAILABLE",
          "The payment order could not be started. Check its status before trying again.",
          request);
    }
  }

  @GetMapping("/payment-orders/{orderId}")
  public ResponseEntity<?> paymentOrder(
      Authentication authentication, @PathVariable UUID orderId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to check this payment order.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    try {
      return orderService
          .findForActor(actor.orElseThrow(), orderId)
          .<ResponseEntity<?>>map(order -> ok(orderView(order)))
          .orElseGet(
              () ->
                  problem(
                      HttpStatus.NOT_FOUND,
                      "PAYMENT_ORDER_NOT_FOUND",
                      "The payment order is unavailable.",
                      request));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PAYMENT_ORDER_UNAVAILABLE",
          "Payment order status could not be loaded. Please try again.",
          request);
    }
  }

  private ResponseEntity<?> creationProblem(Outcome outcome, HttpServletRequest request) {
    return switch (outcome) {
      case INVALID_IDEMPOTENCY_KEY ->
          problem(
              HttpStatus.BAD_REQUEST,
              "INVALID_IDEMPOTENCY_KEY",
              "Provide a 16–128 character idempotency key and reuse it only for this request.",
              request);
      case PACK_NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "CREDIT_PACK_NOT_FOUND",
              "The selected credit pack is not available.",
              request);
      case PACK_VERSION_STALE ->
          problem(
              HttpStatus.CONFLICT,
              "CREDIT_PACK_VERSION_STALE",
              "The credit pack changed. Refresh the catalog and review the current price.",
              request);
      case IDEMPOTENCY_KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "This idempotency key belongs to another credit pack request.",
              request);
      case ACCOUNT_NOT_FOUND, ACCOUNT_INACTIVE ->
          problem(
              HttpStatus.UNAUTHORIZED,
              "UNAUTHENTICATED",
              "The application account is no longer active.",
              request);
      case CREATED, REPLAYED ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "PAYMENT_ORDER_UNAVAILABLE",
              "The payment order could not be loaded. Check its status before trying again.",
              request);
    };
  }

  private ResponseEntity<?> policyGate(UUID actorUserId, HttpServletRequest request) {
    try {
      return switch (policyService.requiredAcceptance(actorUserId)) {
        case COMPLETE -> null;
        case REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Review and accept the current Terms and Privacy Notice before using credit packs.",
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

  private static CreditPackView view(CreditPackQuote quote) {
    return new CreditPackView(
        quote.packId(),
        quote.version(),
        quote.environment().name(),
        quote.currency(),
        BigInteger.valueOf(quote.amountVnd()),
        BigInteger.valueOf(quote.paidCredits()),
        BigInteger.valueOf(quote.bonusCredits()),
        quote.paidExpiresAt(),
        quote.bonusExpiresAt());
  }

  private static PaymentOrderView orderView(TopUpOrder order) {
    CreditPackQuote quote = order.quote();
    return new PaymentOrderView(
        order.id(),
        quote.packId(),
        quote.version(),
        quote.environment().name(),
        quote.currency(),
        BigInteger.valueOf(quote.amountVnd()),
        BigInteger.valueOf(quote.paidCredits()),
        BigInteger.valueOf(quote.bonusCredits()),
        quote.paidExpiresAt(),
        quote.bonusExpiresAt(),
        order.status().name(),
        order.createdAt());
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

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_CREDIT_PACK_REQUEST",
        "The credit pack request body is missing or malformed.",
        request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> unsupportedMediaType(HttpServletRequest request) {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "Use application/json for the credit pack request.",
        request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> invalidBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_CREDIT_PACK_REQUEST",
        "Choose a valid credit pack and the version shown in the catalog.",
        request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> invalidIdentifier(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_PAYMENT_ORDER_ID",
        "The payment order identifier is invalid.",
        request);
  }

  public record CreditPackCatalogResponse(String environment, List<CreditPackView> offers) {
    public CreditPackCatalogResponse {
      offers = List.copyOf(offers);
    }
  }

  public record CreditPackView(
      String packId,
      int packVersion,
      String environment,
      String currency,
      BigInteger amountVnd,
      BigInteger paidCredits,
      BigInteger bonusCredits,
      Instant paidExpiresAt,
      Instant bonusExpiresAt) {}

  public record CreateTopUpRequest(String packId, Integer packVersion) {}

  public record PaymentOrderView(
      UUID id,
      String packId,
      int packVersion,
      String environment,
      String currency,
      BigInteger amountVnd,
      BigInteger paidCredits,
      BigInteger bonusCredits,
      Instant paidExpiresAt,
      Instant bonusExpiresAt,
      String status,
      Instant createdAt) {}

  public record CreatePaymentOrderResponse(
      PaymentOrderView order, String checkoutOutcome, String checkoutUrl) {}
}
