package com.novelweb.payment.credit;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.payment.credit.ChapterUnlockService.Entitlement;
import com.novelweb.payment.credit.ChapterUnlockService.Outcome;
import com.novelweb.payment.credit.ChapterUnlockService.UnlockQuote;
import com.novelweb.payment.credit.ChapterUnlockService.UnlockResult;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Gated chapter unlock API for the MoMo TEST credit fixture. */
@RestController
@RequestMapping("/api/v1/chapters/{chapterId}")
@Profile("postgres")
@ConditionalOnProperty(prefix = "novel.payment.momo.test", name = "enabled", havingValue = "true")
public class ChapterUnlockController {

  private final ChapterUnlockService unlockService;
  private final PolicyService policyService;
  private final boolean commerceSandboxEnabled;

  public ChapterUnlockController(
      ChapterUnlockService unlockService,
      PolicyService policyService,
      @Value("${novel.features.commerce-sandbox-enabled:false}") boolean commerceSandboxEnabled) {
    this.unlockService = unlockService;
    this.policyService = policyService;
    this.commerceSandboxEnabled = commerceSandboxEnabled;
  }

  @GetMapping("/unlock-quote")
  public ResponseEntity<?> quote(
      Authentication authentication, @PathVariable UUID chapterId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view this chapter's unlock price.",
          request);
    }
    if (!commerceSandboxEnabled) return commerceSandboxDisabled(request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    try {
      return unlockService
          .quote(actor.orElseThrow(), chapterId)
          .<ResponseEntity<?>>map(quote -> ok(quoteView(quote)))
          .orElseGet(
              () ->
                  problem(
                      HttpStatus.NOT_FOUND,
                      "CHAPTER_NOT_UNLOCKABLE",
                      "This chapter is unavailable for credit unlock.",
                      request));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CHAPTER_UNLOCK_UNAVAILABLE",
          "The chapter price could not be loaded. Please try again.",
          request);
    }
  }

  @PostMapping(value = "/unlock", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> unlock(
      Authentication authentication,
      @PathVariable UUID chapterId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) UnlockRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to unlock this chapter.", request);
    }
    if (!commerceSandboxEnabled) return commerceSandboxDisabled(request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null || body.priceVersion() == null || body.priceVersion() < 1) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_CHAPTER_UNLOCK_REQUEST",
          "Refresh the chapter price and submit its current version.",
          request);
    }

    try {
      UnlockResult result =
          unlockService.unlock(actor.orElseThrow(), chapterId, body.priceVersion(), idempotencyKey);
      if (result.outcome() == Outcome.ALREADY_ACCESSIBLE) {
        return ok(
            new UnlockResponse(
                Outcome.ALREADY_ACCESSIBLE.name(), null, chapterId, body.priceVersion(), 0, null));
      }
      if (result.entitlement() == null) return unlockProblem(result.outcome(), request);
      HttpStatus status = result.outcome() == Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK;
      ResponseEntity.BodyBuilder response =
          ResponseEntity.status(status)
              .cacheControl(CacheControl.noStore())
              .header(HttpHeaders.PRAGMA, "no-cache");
      if (result.outcome() == Outcome.REPLAYED) {
        response.header("Idempotency-Replayed", "true");
      }
      return response.body(resultView(result.outcome(), result.entitlement()));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CHAPTER_UNLOCK_UNAVAILABLE",
          "The chapter could not be unlocked. Check its access status before trying again.",
          request);
    }
  }

  private ResponseEntity<?> unlockProblem(Outcome outcome, HttpServletRequest request) {
    return switch (outcome) {
      case INVALID_IDEMPOTENCY_KEY ->
          problem(
              HttpStatus.BAD_REQUEST,
              "INVALID_IDEMPOTENCY_KEY",
              "Provide a 16–128 character idempotency key and reuse it only for this chapter request.",
              request);
      case INVALID_PRICE_VERSION ->
          problem(
              HttpStatus.BAD_REQUEST,
              "INVALID_CHAPTER_UNLOCK_REQUEST",
              "Refresh the chapter price and submit its current version.",
              request);
      case PRICE_VERSION_STALE ->
          problem(
              HttpStatus.CONFLICT,
              "CHAPTER_PRICE_VERSION_STALE",
              "The chapter price changed. Review the current price before retrying.",
              request);
      case IDEMPOTENCY_KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "This idempotency key belongs to another chapter unlock request.",
              request);
      case INSUFFICIENT_PAID_CREDITS ->
          problem(
              HttpStatus.CONFLICT,
              "INSUFFICIENT_CREDITS",
              "There are not enough paid credits to unlock this chapter.",
              request);
      case CHAPTER_NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "CHAPTER_NOT_UNLOCKABLE",
              "This chapter is unavailable for credit unlock.",
              request);
      case ACCOUNT_NOT_FOUND, ACCOUNT_INACTIVE ->
          problem(
              HttpStatus.UNAUTHORIZED,
              "UNAUTHENTICATED",
              "The application account is no longer active.",
              request);
      case CREATED, REPLAYED, ALREADY_OWNED, ALREADY_ACCESSIBLE ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "CHAPTER_UNLOCK_UNAVAILABLE",
              "The chapter entitlement could not be loaded. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before using chapter unlock.",
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

  private ResponseEntity<?> commerceSandboxDisabled(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "COMMERCE_SANDBOX_DISABLED",
        "Chapter unlock is unavailable while the commerce sandbox is disabled.",
        request);
  }

  private static UnlockQuoteView quoteView(UnlockQuote quote) {
    return new UnlockQuoteView(
        quote.chapterId(),
        quote.workId(),
        quote.chapterTitle(),
        quote.workTitle(),
        quote.priceVersion(),
        quote.priceCredits(),
        quote.accessAlreadyGranted());
  }

  private static UnlockResponse resultView(Outcome outcome, Entitlement entitlement) {
    return new UnlockResponse(
        outcome.name(),
        entitlement.id(),
        entitlement.chapterId(),
        entitlement.priceVersion(),
        entitlement.creditsSpent(),
        entitlement.grantedAt());
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
        "INVALID_CHAPTER_UNLOCK_REQUEST",
        "The chapter unlock request body is missing or malformed.",
        request);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> unsupportedMediaType(HttpServletRequest request) {
    return problem(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        "UNSUPPORTED_MEDIA_TYPE",
        "Use application/json for a chapter unlock request.",
        request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> invalidIdentifier(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_CHAPTER_ID",
        "The chapter identifier is invalid.",
        request);
  }

  public record UnlockRequest(Integer priceVersion) {}

  public record UnlockQuoteView(
      UUID chapterId,
      UUID workId,
      String chapterTitle,
      String workTitle,
      int priceVersion,
      long priceCredits,
      boolean accessAlreadyGranted) {}

  public record UnlockResponse(
      String outcome,
      UUID entitlementId,
      UUID chapterId,
      int priceVersion,
      long creditsSpent,
      Instant grantedAt) {}
}
