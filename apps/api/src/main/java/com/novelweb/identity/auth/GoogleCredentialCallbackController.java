package com.novelweb.identity.auth;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.novelweb.identity.session.SessionCookieFactory;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.security.CorsProperties;
import com.novelweb.platform.web.CorrelationIdFilter;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth/google")
@Profile("postgres")
public class GoogleCredentialCallbackController {

    private static final int MAX_FORM_BODY_BYTES = 24 * 1024;
    private static final int MAX_FORM_FIELDS = 16;
    private static final String CREDENTIAL_FIELD = "credential";
    private static final String CSRF_FIELD = "g_csrf_token";
    private static final String SESSION_COOKIE = SessionCookieFactory.COOKIE_NAME;

    private final GoogleCredentialVerificationService verificationService;
    private final GoogleIdentityService identityService;
    private final SessionService sessionService;
    private final CorsProperties corsProperties;
    private final Duration absoluteSessionTtl;

    public GoogleCredentialCallbackController(
            GoogleCredentialVerificationService verificationService,
            GoogleIdentityService identityService,
            SessionService sessionService,
            CorsProperties corsProperties,
            @Value("${novel.identity.session.absolute-ttl:30d}") Duration absoluteSessionTtl) {
        this.verificationService = verificationService;
        this.identityService = identityService;
        this.sessionService = sessionService;
        this.corsProperties = corsProperties;
        this.absoluteSessionTtl = absoluteSessionTtl;
    }

    @PostMapping("/credential")
    public ResponseEntity<?> credential(HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        if (!hasExpectedOrigin(request)) {
            return problem(HttpStatus.FORBIDDEN, "INVALID_ORIGIN",
                    "The sign-in request could not be verified.", correlationId);
        }
        if (!isFormUrlEncoded(request.getContentType())) {
            return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "The sign-in request could not be processed.", correlationId);
        }
        if (request.getContentLengthLong() > MAX_FORM_BODY_BYTES) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE",
                    "The sign-in request could not be processed.", correlationId);
        }

        final byte[] body;
        try {
            body = request.getInputStream().readNBytes(MAX_FORM_BODY_BYTES + 1);
        } catch (IOException exception) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "The sign-in request could not be processed.", correlationId);
        }
        if (body.length > MAX_FORM_BODY_BYTES) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE",
                    "The sign-in request could not be processed.", correlationId);
        }

        final Map<String, String> form;
        try {
            form = parseForm(body);
        } catch (InvalidFormException exception) {
            return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "The sign-in request could not be processed.", correlationId);
        }

        try {
            GoogleCredentialVerificationService.VerifiedGoogleSignIn verified = verificationService.verify(
                    form.get(CREDENTIAL_FIELD), csrfCookie(request), form.get(CSRF_FIELD));
            GoogleIdentityService.IdentityAccount account = identityService.findOrCreate(verified.identity());
            SessionService.IssuedSession session = issueSession(request, account.userId());
            ResponseCookie cookie = SessionCookieFactory.issue(
                    session.cookieValue(), cookieLifetime(session.expiresAt()));

            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(java.net.URI.create(SafeReturnPath.sanitize(verified.returnTo())))
                    .header(HttpHeaders.SET_COOKIE, cookie.toString())
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.PRAGMA, "no-cache")
                    .header("Referrer-Policy", "no-referrer")
                    .build();
        } catch (InvalidGoogleCredentialException | GoogleAccountLinkRequiredException
                | UnavailableGoogleIdentityException exception) {
            return problem(HttpStatus.UNAUTHORIZED, "SIGN_IN_FAILED",
                    "The sign-in could not be completed. Start again and retry.", correlationId);
        } catch (IllegalStateException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_PROVIDER_UNAVAILABLE",
                    "Sign-in is not available right now. Please try again.", correlationId);
        }
    }

    private Duration cookieLifetime(Instant expiresAt) {
        Duration remainingAbsoluteLifetime = Duration.between(Instant.now(), expiresAt);
        if (remainingAbsoluteLifetime.isZero() || remainingAbsoluteLifetime.isNegative()) {
            throw new IllegalStateException("The new session is already expired");
        }
        return absoluteSessionTtl.compareTo(remainingAbsoluteLifetime) <= 0
                ? absoluteSessionTtl
                : remainingAbsoluteLifetime;
    }

    private SessionService.IssuedSession issueSession(HttpServletRequest request, UUID userId) {
        String existingToken = sessionCookie(request);
        Optional<SessionService.SessionPrincipal> existingPrincipal = existingToken == null
                ? Optional.empty()
                : sessionService.validateAndTouch(existingToken);
        if (existingPrincipal.isPresent() && existingPrincipal.orElseThrow().userId().equals(userId)) {
            return sessionService.rotate(existingToken)
                    .or(() -> sessionService.create(userId))
                    .orElseThrow(InvalidGoogleCredentialException::new);
        }
        if (existingPrincipal.isPresent()) {
            sessionService.revoke(existingToken);
        }
        return sessionService.create(userId).orElseThrow(InvalidGoogleCredentialException::new);
    }

    private boolean hasExpectedOrigin(HttpServletRequest request) {
        var origins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        return origins.size() == 1 && corsProperties.allowedOrigins() != null
                && corsProperties.allowedOrigins().contains(origins.getFirst());
    }

    private static boolean isFormUrlEncoded(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return parsed.isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED)
                    && (parsed.getCharset() == null || StandardCharsets.UTF_8.equals(parsed.getCharset()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static Map<String, String> parseForm(byte[] body) {
        final String encoded;
        try {
            encoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidFormException();
        }

        Map<String, String> values = new HashMap<>();
        if (encoded.isEmpty()) {
            return values;
        }
        String[] fields = encoded.split("&", -1);
        if (fields.length > MAX_FORM_FIELDS) {
            throw new InvalidFormException();
        }
        for (String field : fields) {
            int separator = field.indexOf('=');
            String key;
            String value;
            try {
                key = URLDecoder.decode(separator < 0 ? field : field.substring(0, separator), StandardCharsets.UTF_8);
                value = separator < 0 ? "" : URLDecoder.decode(field.substring(separator + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw new InvalidFormException();
            }
            if (CREDENTIAL_FIELD.equals(key) || CSRF_FIELD.equals(key)) {
                if (values.putIfAbsent(key, value) != null) {
                    throw new InvalidFormException();
                }
            }
        }
        return values;
    }

    private static String csrfCookie(HttpServletRequest request) {
        return cookieValue(request, CSRF_FIELD);
    }

    private static String sessionCookie(HttpServletRequest request) {
        return cookieValue(request, SESSION_COOKIE);
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String value = null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                if (value != null) {
                    return null;
                }
                value = cookie.getValue();
            }
        }
        return value;
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String detail, UUID correlationId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId.toString());
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Referrer-Policy", "no-referrer")
                .body(problem);
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        if (value instanceof String id) {
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
                // Use a generated identifier in controller-only tests or alternate filter configurations.
            }
        }
        return UUID.randomUUID();
    }

    private static final class InvalidFormException extends RuntimeException {
    }
}
