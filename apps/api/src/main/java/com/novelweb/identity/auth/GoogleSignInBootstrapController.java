package com.novelweb.identity.auth;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
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

import com.novelweb.platform.web.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth/google")
@Profile("postgres")
public class GoogleSignInBootstrapController {

    private static final String CREDENTIAL_CALLBACK_PATH = "/api/v1/auth/google/credential";

    private final GoogleAuthAttemptService authAttemptService;
    private final GoogleAuthBootstrapRateLimiter rateLimiter;
    private final String webClientId;
    private final String rateLimitHmacKey;
    private final URI credentialCallbackUri;

    public GoogleSignInBootstrapController(
            GoogleAuthAttemptService authAttemptService,
            GoogleAuthBootstrapRateLimiter rateLimiter,
            @Value("${novel.auth.google.web-client-id:}") String webClientId,
            @Value("${novel.auth.google.credential-callback-uri:}") String credentialCallbackUri,
            @Value("${novel.auth.google.rate-limit-hmac-key:}") String rateLimitHmacKey) {
        this.authAttemptService = authAttemptService;
        this.rateLimiter = rateLimiter;
        this.webClientId = webClientId == null ? "" : webClientId.trim();
        this.rateLimitHmacKey = rateLimitHmacKey == null ? "" : rateLimitHmacKey.trim();
        this.credentialCallbackUri = parseCredentialCallbackUri(credentialCallbackUri);
    }

    @GetMapping("/bootstrap")
    public ResponseEntity<?> bootstrap(
            @RequestParam(required = false) String returnTo,
            @RequestParam(required = false, defaultValue = "vi") String locale,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        if (webClientId.isBlank() || rateLimitHmacKey.isBlank() || credentialCallbackUri == null) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROVIDER_UNAVAILABLE",
                    "Google sign-in is not configured for this environment.", correlationId);
        }

        final byte[] clientAddress;
        final byte[] clientKeyHash;
        try {
            clientAddress = InetAddress.getByName(request.getRemoteAddr()).getAddress();
            clientKeyHash = GoogleAuthRateLimitKeyHasher.hash(clientAddress, rateLimitHmacKey);
        } catch (UnknownHostException | IllegalArgumentException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROVIDER_UNAVAILABLE",
                    "Google sign-in is not available right now.", correlationId);
        }

        final boolean acquired;
        try {
            acquired = rateLimiter.tryAcquire(clientKeyHash);
        } catch (IllegalStateException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROVIDER_UNAVAILABLE",
                    "Google sign-in is not available right now.", correlationId);
        }
        if (!acquired) {
            return problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Please wait before starting another sign-in attempt.", correlationId);
        }

        final GoogleAuthAttemptService.CreatedAttempt attempt;
        try {
            attempt = authAttemptService.create(returnTo, correlationId);
        } catch (IllegalStateException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROVIDER_UNAVAILABLE",
                    "Google sign-in is not available right now.", correlationId);
        }
        String safeLocale = "en".equalsIgnoreCase(locale) ? "en" : "vi";
        GoogleSignInBootstrap response = new GoogleSignInBootstrap(
                webClientId,
                credentialCallbackUri.toASCIIString(),
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
                // Fall through to a server-generated identifier for controller-only tests or alternate filters.
            }
        }
        return UUID.randomUUID();
    }

    private static URI parseCredentialCallbackUri(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(rawValue.trim());
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            boolean localDevelopment = "http".equalsIgnoreCase(uri.getScheme())
                    && "localhost".equalsIgnoreCase(uri.getHost());
            if ((!secure && !localDevelopment)
                    || !uri.isAbsolute()
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !CREDENTIAL_CALLBACK_PATH.equals(uri.getRawPath())) {
                return null;
            }
            return uri;
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    public record GoogleSignInBootstrap(
            String clientId,
            String loginUri,
            String nonce,
            String locale,
            Instant attemptExpiresAt) {
    }
}
