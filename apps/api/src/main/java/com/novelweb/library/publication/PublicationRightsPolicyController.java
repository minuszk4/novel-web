package com.novelweb.library.publication;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authoring/manuscripts/{workId}/publication/rights-policy")
@Profile("postgres")
public class PublicationRightsPolicyController {

  private final PublicationRightsPolicyService policyService;

  public PublicationRightsPolicyController(PublicationRightsPolicyService policyService) {
    this.policyService = policyService;
  }

  @GetMapping
  public ResponseEntity<?> current(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view the publication-rights policy for this manuscript.",
          correlationId(request));
    }

    try {
      PublicationRightsPolicyService.PolicyReadResult result =
          policyService.current(principal.userId(), workId);
      return switch (result.outcome()) {
        case AVAILABLE -> noStore(HttpStatus.OK).body(result.policy());
        case WORK_NOT_FOUND ->
            problem(
                HttpStatus.NOT_FOUND,
                "MANUSCRIPT_NOT_FOUND",
                "The private authored manuscript could not be found.",
                correlationId(request));
        case POLICY_REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Accept the current Terms and acknowledge the Privacy Notice first.",
                correlationId(request));
        case POLICY_NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_NOT_CONFIGURED",
                "Current approved policy documents are not available.",
                correlationId(request));
        default ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED",
                "An approved, effective publication-rights policy is not available.",
                correlationId(request));
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_RIGHTS_POLICY_STORE_UNAVAILABLE",
          "The publication-rights policy could not be loaded.",
          correlationId(request));
    }
  }

  @PostMapping("/acceptance")
  public ResponseEntity<?> accept(
      Authentication authentication,
      @PathVariable UUID workId,
      @RequestBody(required = false) PublicationRightsAcceptanceRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String rawIdempotencyKey,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to record publication-rights policy acceptance.",
          correlationId);
    }
    if (body == null
        || body.hasUnknownFields()
        || !PublicationRightsPolicyService.isValidVersion(body.policyVersion())
        || !Boolean.TRUE.equals(body.confirmed())) {
      return problem(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "PUBLICATION_RIGHTS_CONFIRMATION_REQUIRED",
          "Review the current policy and explicitly confirm it before continuing.",
          correlationId);
    }
    UUID idempotencyKey = parseIdempotencyKey(rawIdempotencyKey);
    if (idempotencyKey == null) {
      return problem(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "IDEMPOTENCY_KEY_REQUIRED",
          "Provide a canonical UUID Idempotency-Key for this confirmation attempt.",
          correlationId);
    }

    try {
      PublicationRightsPolicyService.AcceptanceResult result =
          policyService.accept(principal.userId(), workId, body.policyVersion(), idempotencyKey);
      return switch (result.outcome()) {
        case ACCEPTED -> noStore(HttpStatus.OK).body(result.acceptance());
        case STALE -> stale(result.currentVersion(), correlationId);
        case KEY_REUSED ->
            problem(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "Use a new Idempotency-Key for a different policy confirmation.",
                correlationId);
        case WORK_NOT_FOUND ->
            problem(
                HttpStatus.NOT_FOUND,
                "MANUSCRIPT_NOT_FOUND",
                "The private authored manuscript could not be found.",
                correlationId);
        case POLICY_REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Accept the current Terms and acknowledge the Privacy Notice first.",
                correlationId);
        case POLICY_NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_NOT_CONFIGURED",
                "Current approved policy documents are not available.",
                correlationId);
        default ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED",
                "An approved, effective publication-rights policy is not available.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_RIGHTS_POLICY_STORE_UNAVAILABLE",
          "The publication-rights policy confirmation could not be recorded.",
          correlationId);
    }
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> stale(String currentVersion, UUID correlationId) {
    ProblemDetail problem =
        baseProblem(
            HttpStatus.CONFLICT,
            "Review the current publication-rights policy before confirming it.",
            "PUBLICATION_RIGHTS_POLICY_STALE",
            correlationId);
    problem.setProperty("currentPolicyVersion", currentVersion);
    return noStore(HttpStatus.CONFLICT).body(problem);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    return noStore(status).body(baseProblem(status, detail, code, correlationId));
  }

  private static ProblemDetail baseProblem(
      HttpStatus status, String detail, String code, UUID correlationId) {
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

  private static UUID parseIdempotencyKey(String value) {
    if (value == null
        || !value.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      return null;
    }
    return UUID.fromString(value);
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

  public static final class PublicationRightsAcceptanceRequest {
    private String policyVersion;
    private Boolean confirmed;
    private boolean unknownFields;

    public String policyVersion() {
      return policyVersion;
    }

    public Boolean confirmed() {
      return confirmed;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setPolicyVersion(String policyVersion) {
      this.policyVersion = policyVersion;
    }

    public void setConfirmed(Boolean confirmed) {
      this.confirmed = confirmed;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }
}
