package com.novelweb.identity.auth;

import java.time.Duration;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/auth/policies")
@Profile("postgres")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping("/current")
    public ResponseEntity<?> currentPolicies(HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        try {
            return policyService.currentPolicies()
                    .<ResponseEntity<?>>map(policies -> ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .header(HttpHeaders.PRAGMA, "no-cache")
                            .body(policies))
                    .orElseGet(() -> problem(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_NOT_CONFIGURED",
                            "Current approved policy documents are not available.", correlationId));
        } catch (IllegalStateException exception) {
            return policyStoreUnavailable(correlationId);
        }
    }

    @GetMapping("/terms/{version}")
    public ResponseEntity<?> termsDocument(@PathVariable String version, HttpServletRequest request) {
        return document(PolicyService.PolicyType.TERMS, version, request);
    }

    @GetMapping("/privacy-notice/{version}")
    public ResponseEntity<?> privacyNoticeDocument(@PathVariable String version, HttpServletRequest request) {
        return document(PolicyService.PolicyType.PRIVACY_NOTICE, version, request);
    }

    @PostMapping("/accept")
    public ResponseEntity<?> acceptPolicies(
            Authentication authentication,
            @RequestBody(required = false) PolicyAcceptanceRequest body,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        SessionService.SessionPrincipal principal = principal(authentication);
        if (principal == null) {
            return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Sign in before recording required policy decisions.", correlationId);
        }
        if (!validRequest(body)) {
            return problem(HttpStatus.UNPROCESSABLE_CONTENT, "POLICY_ACCEPTANCE_REQUIRED",
                    "Accept the current Terms and acknowledge the current Privacy Notice explicitly.", correlationId);
        }

        try {
            PolicyService.AcceptanceResult result = policyService.accept(principal.userId(),
                    new PolicyService.AcceptanceCommand(
                            body.termsVersion(), body.privacyNoticeVersion(),
                            body.termsAccepted(), body.privacyNoticeAcknowledged()));
            return switch (result.outcome()) {
                case ACCEPTED -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.PRAGMA, "no-cache")
                        .body(result.acceptance());
                case STALE -> staleVersion(result.currentPolicies(), correlationId);
                case NOT_CONFIGURED -> problem(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_NOT_CONFIGURED",
                        "Current approved policy documents are not available.", correlationId);
            };
        } catch (IllegalStateException exception) {
            return policyStoreUnavailable(correlationId);
        }
    }

    private ResponseEntity<?> document(
            PolicyService.PolicyType type, String version, HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        try {
            return policyService.document(type, version)
                    .<ResponseEntity<?>>map(document -> ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                            .header("X-Content-Type-Options", "nosniff")
                            .body(document.documentMarkdown()))
                    .orElseGet(() -> problem(HttpStatus.NOT_FOUND, "POLICY_DOCUMENT_NOT_FOUND",
                            "This policy document version is not available.", correlationId));
        } catch (IllegalStateException exception) {
            return policyStoreUnavailable(correlationId);
        }
    }

    private static boolean validRequest(PolicyAcceptanceRequest body) {
        return body != null
                && PolicyService.isValidVersion(body.termsVersion())
                && PolicyService.isValidVersion(body.privacyNoticeVersion())
                && Boolean.TRUE.equals(body.termsAccepted())
                && Boolean.TRUE.equals(body.privacyNoticeAcknowledged());
    }

    private static ResponseEntity<ProblemDetail> staleVersion(
            PolicyService.CurrentPolicies current, UUID correlationId) {
        ProblemDetail problem = baseProblem(HttpStatus.CONFLICT,
                "One or both policy versions changed. Review the current documents and submit again.",
                "POLICY_VERSION_STALE", correlationId);
        problem.setProperty("currentTermsVersion", current.terms().version());
        problem.setProperty("currentPrivacyNoticeVersion", current.privacyNotice().version());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(problem);
    }

    private static ResponseEntity<ProblemDetail> policyStoreUnavailable(UUID correlationId) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_STORE_UNAVAILABLE",
                "Policy documents could not be loaded. Please try again.", correlationId);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String detail, UUID correlationId) {
        ProblemDetail problem = baseProblem(status, detail, code, correlationId);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(problem);
    }

    private static ProblemDetail baseProblem(HttpStatus status, String detail, String code, UUID correlationId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId.toString());
        return problem;
    }

    private static SessionService.SessionPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
            return null;
        }
        return principal;
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

    public record PolicyAcceptanceRequest(
            String termsVersion,
            String privacyNoticeVersion,
            Boolean termsAccepted,
            Boolean privacyNoticeAcknowledged) {
    }
}
