package com.novelweb.translation.glossary;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/glossaries")
@Profile("postgres")
public class GlossaryRevisionController {

  private final GlossaryRevisionWriteRepository repository;
  private final PolicyService policyService;

  public GlossaryRevisionController(
      GlossaryRevisionWriteRepository repository, PolicyService policyService) {
    this.repository = repository;
    this.policyService = policyService;
  }

  @PostMapping("/{glossaryId}/revisions")
  public ResponseEntity<?> createDraftRevision(
      Authentication authentication,
      @PathVariable UUID glossaryId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) DraftRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to create a glossary draft.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (ifMatch == null) {
      return problem(
          HttpStatus.PRECONDITION_REQUIRED,
          "IF_MATCH_REQUIRED",
          "Reload the glossary before creating a draft revision.",
          request);
    }

    long expectedVersion = parseEtagVersion(ifMatch);
    List<GlossaryRevisionWriteRepository.TermDraft> terms = terms(body);
    GlossaryRevisionWriteRepository.DraftCreated created =
        repository.createDraftRevision(actor.orElseThrow(), glossaryId, expectedVersion, terms);
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.ETAG, etag(created.glossaryVersion()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(created);
  }

  @ExceptionHandler(GlossaryRevisionWriteRepository.GlossaryNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "The glossary is unavailable.", request);
  }

  @ExceptionHandler(GlossaryRevisionWriteRepository.VersionConflictException.class)
  public ResponseEntity<ProblemDetail> staleVersion(
      GlossaryRevisionWriteRepository.VersionConflictException exception,
      HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .header(HttpHeaders.ETAG, etag(exception.currentVersion()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(
            problemDetail(
                HttpStatus.CONFLICT,
                "GLOSSARY_VERSION_STALE",
                "The glossary changed elsewhere. Reload it before creating a draft.",
                request));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_GLOSSARY_DRAFT",
        "The glossary draft contains a missing or invalid field.",
        request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_REQUEST",
        "The glossary draft body is missing or malformed.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "GLOSSARY_UNAVAILABLE",
        "The glossary draft could not be stored. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before editing a glossary.",
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

  private static List<GlossaryRevisionWriteRepository.TermDraft> terms(DraftRequest body) {
    if (body == null || body.terms() == null) {
      throw new IllegalArgumentException("terms are required");
    }
    if (body.terms().size() > GlossaryRevisionWriteRepository.MAX_DRAFT_TERMS) {
      throw new IllegalArgumentException("too many terms");
    }
    List<GlossaryRevisionWriteRepository.TermDraft> terms = new ArrayList<>(body.terms().size());
    for (TermRequest term : body.terms()) {
      if (term == null
          || term.id() == null
          || term.casePolicy() == null
          || term.matchMode() == null) {
        throw new IllegalArgumentException("term id, case policy and match mode are required");
      }
      List<String> aliases = term.aliases() == null ? List.of() : term.aliases();
      if (aliases.size() > GlossaryRevisionWriteRepository.MAX_ALIASES_PER_TERM) {
        throw new IllegalArgumentException("too many aliases for a term");
      }
      if (aliases.stream().anyMatch(alias -> alias == null)) {
        throw new IllegalArgumentException("aliases must not contain null values");
      }
      terms.add(
          new GlossaryRevisionWriteRepository.TermDraft(
              term.id(),
              term.sourceTerm(),
              term.targetTerm(),
              term.priority(),
              term.casePolicy(),
              term.matchMode(),
              term.doNotTranslate(),
              term.contextNote(),
              aliases));
    }
    return List.copyOf(terms);
  }

  private static Optional<UUID> actorUserId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static long parseEtagVersion(String ifMatch) {
    if (!ifMatch.matches("\"v[1-9][0-9]{0,18}\"")) {
      throw new IllegalArgumentException("If-Match must contain a strong glossary version ETag");
    }
    try {
      return Long.parseLong(ifMatch.substring(2, ifMatch.length() - 1));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("If-Match must contain a strong glossary version ETag");
    }
  }

  private static String etag(long version) {
    return "\"v" + version + "\"";
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problemDetail(status, code, detail, request));
  }

  private static ProblemDetail problemDetail(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", correlationId instanceof String id ? id : "unavailable");
    return problem;
  }

  public record DraftRequest(List<TermRequest> terms) {}

  public record TermRequest(
      UUID id,
      String sourceTerm,
      String targetTerm,
      int priority,
      GlossaryMatcher.CasePolicy casePolicy,
      GlossaryMatcher.MatchMode matchMode,
      boolean doNotTranslate,
      String contextNote,
      List<String> aliases) {}
}
