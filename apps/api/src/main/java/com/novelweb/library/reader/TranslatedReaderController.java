package com.novelweb.library.reader;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated, no-store reads of a currently released human translation. */
@RestController
@RequestMapping("/api/v1/chapters")
@Profile("postgres & translation-fake")
@ConditionalOnExpression(
    "'${novel.translation.review.enabled:false}' == 'true' and "
        + "'${novel.translation.publication.enabled:false}' == 'true'")
public class TranslatedReaderController {

  private final TranslatedReaderService translatedReaderService;
  private final PolicyService policyService;

  public TranslatedReaderController(
      TranslatedReaderService translatedReaderService, PolicyService policyService) {
    this.translatedReaderService = translatedReaderService;
    this.policyService = policyService;
  }

  @GetMapping("/{chapterId}/translations")
  public ResponseEntity<?> languages(
      Authentication authentication, @PathVariable UUID chapterId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to read this chapter.", request);
    }
    PolicyService.RequiredAcceptance acceptance =
        policyService.requiredAcceptance(actor.orElseThrow());
    if (acceptance != PolicyService.RequiredAcceptance.COMPLETE) {
      return problem(
          acceptance == PolicyService.RequiredAcceptance.REQUIRED
              ? HttpStatus.FORBIDDEN
              : HttpStatus.SERVICE_UNAVAILABLE,
          acceptance == PolicyService.RequiredAcceptance.REQUIRED
              ? "POLICY_ACCEPTANCE_REQUIRED"
              : "POLICY_NOT_CONFIGURED",
          acceptance == PolicyService.RequiredAcceptance.REQUIRED
              ? "Accept the current Terms and Privacy Notice before reading."
              : "Current approved policy documents are not available.",
          request);
    }
    try {
      return ok(
          new AvailableLanguages(
              translatedReaderService.availableLanguages(actor.orElseThrow(), chapterId)));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATED_READER_UNAVAILABLE",
          "Released translations could not be loaded safely.",
          request);
    }
  }

  @GetMapping("/{chapterId}/translations/{targetLanguage}/content")
  public ResponseEntity<?> content(
      Authentication authentication,
      @PathVariable UUID chapterId,
      @PathVariable String targetLanguage,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "250") String limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to read this chapter.", request);
    }
    PolicyService.RequiredAcceptance acceptance =
        policyService.requiredAcceptance(actor.orElseThrow());
    if (acceptance != PolicyService.RequiredAcceptance.COMPLETE) {
      return problem(
          acceptance == PolicyService.RequiredAcceptance.REQUIRED
              ? HttpStatus.FORBIDDEN
              : HttpStatus.SERVICE_UNAVAILABLE,
          acceptance == PolicyService.RequiredAcceptance.REQUIRED
              ? "POLICY_ACCEPTANCE_REQUIRED"
              : "POLICY_NOT_CONFIGURED",
          acceptance == PolicyService.RequiredAcceptance.REQUIRED
              ? "Accept the current Terms and Privacy Notice before reading."
              : "Current approved policy documents are not available.",
          request);
    }
    int parsedLimit;
    try {
      parsedLimit = Integer.parseInt(limit);
    } catch (NumberFormatException exception) {
      parsedLimit = 0;
    }
    try {
      return ok(
          translatedReaderService.content(
              actor.orElseThrow(), chapterId, targetLanguage, cursor, parsedLimit));
    } catch (ReaderCursorCodec.InvalidCursorException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "TRANSLATED_READER_CURSOR_INVALID",
          "Reload the current translation and continue from its current page.",
          request);
    } catch (ReaderCursorCodec.SigningUnavailableException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "READER_CURSOR_UNAVAILABLE",
          "The translated Reader is temporarily unavailable.",
          request);
    } catch (ReaderService.ReaderNotFoundException exception) {
      return problem(
          HttpStatus.NOT_FOUND,
          "TRANSLATION_NOT_AVAILABLE",
          "A released translation is not available for this chapter and language.",
          request);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_TRANSLATED_READER_QUERY",
          "The language or page limit is invalid.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATED_READER_UNAVAILABLE",
          "The released translation could not be loaded safely.",
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

  public record AvailableLanguages(List<String> languages) {
    public AvailableLanguages {
      languages = List.copyOf(languages);
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
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }
}
