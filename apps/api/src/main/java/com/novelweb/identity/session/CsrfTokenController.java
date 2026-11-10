package com.novelweb.identity.session;

import java.time.Instant;
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

import com.novelweb.platform.web.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("postgres")
public class CsrfTokenController {

    @GetMapping("/csrf")
    public ResponseEntity<?> csrf(Authentication authentication, HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
            return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Sign in before requesting a CSRF token.", correlationId);
        }
        Object tokenValue = request.getAttribute(SessionAuthenticationFilter.CSRF_TOKEN_ATTRIBUTE);
        if (!(tokenValue instanceof String token) || token.isBlank()) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "CSRF_TOKEN_UNAVAILABLE",
                    "The security token is not available right now.", correlationId);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new CsrfTokenResponse(token, principal.expiresAt()));
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
                .body(problem);
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        if (value instanceof String id) {
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
                // Use a generated identifier when a test or alternate filter omits the normal correlation ID.
            }
        }
        return UUID.randomUUID();
    }

    public record CsrfTokenResponse(String token, Instant expiresAt) {
    }
}
