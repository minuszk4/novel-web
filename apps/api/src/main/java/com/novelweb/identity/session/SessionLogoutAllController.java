package com.novelweb.identity.session;

import java.time.Instant;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.novelweb.platform.web.CorrelationIdFilter;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("postgres")
public class SessionLogoutAllController {

    private final LogoutAllConfirmationService confirmationService;

    public SessionLogoutAllController(LogoutAllConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @PostMapping("/logout-all/confirmation")
    public ResponseEntity<?> issueConfirmation(Authentication authentication, HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        SessionService.SessionPrincipal principal = principal(authentication);
        String sessionToken = sessionCookie(request);
        if (principal == null) {
            return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Sign in before confirming session revocation.", correlationId);
        }

        try {
            return confirmationService.issue(principal.userId(), sessionToken)
                    .<ResponseEntity<?>>map(confirmation -> ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .header(HttpHeaders.PRAGMA, "no-cache")
                            .body(new LogoutAllConfirmationResponse(confirmation.token(), confirmation.expiresAt())))
                    .orElseGet(() -> problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                            "The application session is no longer active.", correlationId));
        } catch (IllegalStateException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_STORE_UNAVAILABLE",
                    "A confirmation could not be issued. Please try again.", correlationId);
        }
    }

    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(
            Authentication authentication,
            @RequestBody LogoutAllRequest body,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        SessionService.SessionPrincipal principal = principal(authentication);
        if (principal == null) {
            return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Sign in before revoking application sessions.", correlationId);
        }

        try {
            if (!confirmationService.consumeAndRevokeAll(
                    principal.userId(), sessionCookie(request), body == null ? null : body.confirmationToken())) {
                return problem(HttpStatus.FORBIDDEN, "LOGOUT_ALL_CONFIRMATION_INVALID",
                        "The confirmation is invalid or expired. Request a new confirmation and retry.", correlationId);
            }
        } catch (IllegalStateException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_STORE_UNAVAILABLE",
                    "Application sessions could not be revoked. Please try again.", correlationId);
        }

        ResponseCookie clearedCookie = SessionCookieFactory.clear();
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedCookie.toString())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }

    private static SessionService.SessionPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
            return null;
        }
        return principal;
    }

    private static String sessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String token = null;
        for (Cookie cookie : cookies) {
            if (SessionCookieFactory.COOKIE_NAME.equals(cookie.getName())) {
                if (token != null) {
                    return null;
                }
                token = cookie.getValue();
            }
        }
        return token;
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String detail, UUID correlationId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId.toString());
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

    public record LogoutAllConfirmationResponse(String confirmationToken, Instant expiresAt) {
    }

    public record LogoutAllRequest(String confirmationToken) {
    }
}
