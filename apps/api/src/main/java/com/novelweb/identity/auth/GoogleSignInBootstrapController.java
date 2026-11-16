package com.novelweb.identity.auth;

import com.novelweb.platform.security.TrustedProxyClientAddressResolver;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/google")
@Profile("postgres")
public class GoogleSignInBootstrapController {

  private final GoogleAuthAttemptService authAttemptService;
  private final GoogleAuthBootstrapRateLimiter rateLimiter;
  private final GoogleSignInConfiguration googleSignInConfiguration;
  private final TrustedProxyClientAddressResolver clientAddressResolver;

  public GoogleSignInBootstrapController(
      GoogleAuthAttemptService authAttemptService,
      GoogleAuthBootstrapRateLimiter rateLimiter,
      GoogleSignInConfiguration googleSignInConfiguration,
      @Value("${novel.auth.google.trusted-proxies:}") String trustedProxyCidrs) {
    this.authAttemptService = authAttemptService;
    this.rateLimiter = rateLimiter;
    this.googleSignInConfiguration = googleSignInConfiguration;
    this.clientAddressResolver = new TrustedProxyClientAddressResolver(trustedProxyCidrs);
  }

  @GetMapping("/bootstrap")
  public ResponseEntity<?> bootstrap(
      @RequestParam(required = false) String returnTo,
      @RequestParam(required = false, defaultValue = "vi") String locale,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    if (!googleSignInConfiguration.isReadyForSignIn()) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AUTH_PROVIDER_UNAVAILABLE",
          "Google sign-in is not configured for this environment.",
          correlationId);
    }

    final byte[] clientAddress;
    final byte[] clientKeyHash;
    try {
      clientAddress = clientAddressResolver.resolve(request);
      clientKeyHash =
          GoogleAuthRateLimitKeyHasher.hash(
              clientAddress, googleSignInConfiguration.rateLimitHmacKey());
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AUTH_PROVIDER_UNAVAILABLE",
          "Google sign-in is not available right now.",
          correlationId);
    }

    final boolean acquired;
    try {
      acquired = rateLimiter.tryAcquire(clientKeyHash);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AUTH_PROVIDER_UNAVAILABLE",
          "Google sign-in is not available right now.",
          correlationId);
    }
    if (!acquired) {
      return problem(
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMITED",
          "Please wait before starting another sign-in attempt.",
          correlationId);
    }

    final GoogleAuthAttemptService.CreatedAttempt attempt;
    try {
      attempt = authAttemptService.create(returnTo, correlationId);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AUTH_PROVIDER_UNAVAILABLE",
          "Google sign-in is not available right now.",
          correlationId);
    }
    String safeLocale = "en".equalsIgnoreCase(locale) ? "en" : "vi";
    GoogleSignInBootstrap response =
        new GoogleSignInBootstrap(
            googleSignInConfiguration.webClientId(),
            googleSignInConfiguration.credentialCallbackUri().toASCIIString(),
            attempt.nonce(),
            safeLocale,
            attempt.expiresAt());
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(response);
  }

  private ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId.toString());
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(problem);
  }

  private static UUID correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    if (value instanceof String id) {
      try {
        return UUID.fromString(id);
      } catch (IllegalArgumentException ignored) {
        // Fall through to a server-generated identifier for controller-only tests or alternate
        // filters.
      }
    }
    return UUID.randomUUID();
  }

  public record GoogleSignInBootstrap(
      String clientId, String loginUri, String nonce, String locale, Instant attemptExpiresAt) {}
}
