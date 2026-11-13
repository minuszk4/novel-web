package com.novelweb.translation.glossary;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP catalog operations for listing and creating scoped glossaries. */
@RestController
@RequestMapping("/api/v1/glossaries")
@Profile("postgres")
public class GlossaryCatalogController {

  private final GlossaryCatalogService glossaryService;
  private final PolicyService policyService;

  public GlossaryCatalogController(
      GlossaryCatalogService glossaryService, PolicyService policyService) {
    this.glossaryService = glossaryService;
    this.policyService = policyService;
  }

  @GetMapping
  public ResponseEntity<?> list(
      Authentication authentication,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String libraryId,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") String limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to view your glossaries.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;

    GlossaryCatalogService.Page page =
        glossaryService.list(
            actor.orElseThrow(),
            scope,
            parseUuid(libraryId, "libraryId"),
            q,
            cursor,
            parseLimit(limit));
    return ok(page);
  }

  @PostMapping
  public ResponseEntity<?> create(
      Authentication authentication,
      @RequestBody(required = false) CreateGlossaryRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to create a glossary.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null) throw new IllegalArgumentException("glossary body is required");

    GlossaryCatalogRepository.GlossaryRecord created =
        glossaryService.create(
            actor.orElseThrow(),
            new GlossaryCatalogService.CreateRequest(
                body.name(),
                body.scope(),
                body.sourceLanguage(),
                body.targetLanguage(),
                parseUuid(body.libraryId(), "libraryId"),
                parseUuid(body.workId(), "workId"),
                parseUuid(body.volumeId(), "volumeId"),
                parseUuid(body.chapterId(), "chapterId")));
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.ETAG, etag(created.version()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(created);
  }

  @ExceptionHandler(GlossaryCursorCodec.InvalidCursorException.class)
  public ResponseEntity<ProblemDetail> invalidCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_CURSOR",
        "The glossary cursor is malformed or belongs to a different actor, filter or access scope.",
        request);
  }

  @ExceptionHandler(GlossaryCursorCodec.SigningUnavailableException.class)
  public ResponseEntity<ProblemDetail> signingUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "GLOSSARY_CURSOR_UNAVAILABLE",
        "Glossary pagination is temporarily unavailable.",
        request);
  }

  @ExceptionHandler(GlossaryCatalogRepository.GlossaryNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "NOT_FOUND",
        "The glossary target is unavailable or you cannot edit it.",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_GLOSSARY_REQUEST",
        "The glossary request or filter is malformed or outside its supported limits.",
        request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "The glossary request body is missing or malformed.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "GLOSSARY_CATALOG_UNAVAILABLE",
        "Glossary data could not be loaded or saved. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before using glossaries.",
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

  private static int parseLimit(String rawLimit) {
    try {
      return Integer.parseInt(rawLimit);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("limit must be an integer between 1 and 100");
    }
  }

  private static UUID parseUuid(String value, String field) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a valid UUID");
    }
  }

  private static Optional<UUID> actorUserId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static ResponseEntity<?> ok(Object body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  private static String etag(long version) {
    return "\"v" + version + "\"";
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", correlationId instanceof String id ? id : "unavailable");
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }

  public record CreateGlossaryRequest(
      String name,
      GlossaryMatcher.Scope scope,
      String sourceLanguage,
      String targetLanguage,
      String libraryId,
      String workId,
      String volumeId,
      String chapterId) {}
}
