package com.novelweb.library.publication;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authoring/manuscripts/{workId}/publication/readiness")
@Profile("postgres")
public class PublicationReadinessController {

  private final PublicationRightsPolicyService publicationService;

  public PublicationReadinessController(PublicationRightsPolicyService publicationService) {
    this.publicationService = publicationService;
  }

  @GetMapping
  public ResponseEntity<?> readiness(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view publication readiness for this manuscript.",
          correlationId);
    }

    try {
      PublicationRightsPolicyService.ReadinessResult result =
          publicationService.readiness(principal.userId(), workId);
      return switch (result.outcome()) {
        case AVAILABLE -> {
          PublicationRightsPolicyService.PublicationReadiness body = result.readiness();
          yield noStore(HttpStatus.OK).header(HttpHeaders.ETAG, body.snapshotEtag()).body(body);
        }
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
                "PUBLICATION_READINESS_UNAVAILABLE",
                "Publication readiness could not be loaded.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_READINESS_UNAVAILABLE",
          "Publication readiness could not be loaded.",
          correlationId);
    }
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId.toString());
    return noStore(status).body(problem);
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
}
