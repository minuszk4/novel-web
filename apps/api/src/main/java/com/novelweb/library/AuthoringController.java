package com.novelweb.library;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private-first creation of author-owned work. Publishing is a separate, unimplemented workflow.
 */
@RestController
@RequestMapping("/api/v1/authoring/manuscripts")
@Profile("postgres")
public class AuthoringController {

  private final LibraryService libraryService;
  private final PolicyService policyService;

  public AuthoringController(LibraryService libraryService, PolicyService policyService) {
    this.libraryService = libraryService;
    this.policyService = policyService;
  }

  @PostMapping
  public ResponseEntity<?> createManuscript(
      Authentication authentication,
      @RequestBody CreateManuscriptRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to create a manuscript.", request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null || body.hasUnknownFields())
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_MANUSCRIPT",
          "Request body is missing or contains unsupported fields.",
          request);
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{16,128}"))
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_IDEMPOTENCY_KEY",
          "Provide a 16 to 128 character Idempotency-Key for this create attempt.",
          request);

    LibraryRepository.WorkRecord work =
        libraryService.createPrivateAuthoredWork(
            actor.orElseThrow(), body.title(), body.author(), body.language(), idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(
            new CreatedManuscriptResponse(
                work.id(),
                work.title(),
                work.author(),
                work.language(),
                "draft",
                work.visibility(),
                work.createdAt()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_MANUSCRIPT", exception.getMessage(), request);
  }

  @ExceptionHandler(LibraryRepository.IdempotencyKeyReusedException.class)
  public ResponseEntity<ProblemDetail> idempotencyKeyReused(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "This Idempotency-Key belongs to a different manuscript request. Use a new key.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTHORING_UNAVAILABLE",
        "The manuscript could not be created. Please try again.",
        request);
  }

  private ResponseEntity<?> policyGate(UUID actorUserId, HttpServletRequest request) {
    try {
      return switch (policyService.requiredAcceptance(actorUserId)) {
        case COMPLETE -> null;
        case REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Review and accept the current Terms and Privacy Notice before creating a manuscript.",
                request);
        case NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_NOT_CONFIGURED",
                "Current approved policy documents are not available.",
                request);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "POLICY_STORE_UNAVAILABLE",
          "Current policy state could not be checked. Please try again.",
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

  public static final class CreateManuscriptRequest {
    private String title;
    private String author;
    private String language;
    private boolean unknownFields;

    public String title() {
      return title;
    }

    public String author() {
      return author;
    }

    public String language() {
      return language;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public void setAuthor(String author) {
      this.author = author;
    }

    public void setLanguage(String language) {
      this.language = language;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public record CreatedManuscriptResponse(
      UUID id,
      String title,
      String author,
      String language,
      String publicationState,
      String visibility,
      Instant createdAt) {}
}
