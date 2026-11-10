package com.novelweb.identity.auth;

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

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("postgres")
public class CurrentUserController {

    private final CurrentUserService currentUserService;

    public CurrentUserController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    public ResponseEntity<?> currentUser(Authentication authentication, HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
            return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Sign in to view the current account.", correlationId);
        }

        try {
            return currentUserService.findActiveUser(principal.userId())
                    .<ResponseEntity<?>>map(user -> ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .header(HttpHeaders.PRAGMA, "no-cache")
                            .body(user))
                    .orElseGet(() -> problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                            "The application session is no longer active.", correlationId));
        } catch (IllegalStateException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_STORE_UNAVAILABLE",
                    "The current account could not be loaded. Please try again.", correlationId);
        }
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
}
