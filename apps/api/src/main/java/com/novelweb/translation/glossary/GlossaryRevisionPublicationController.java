package com.novelweb.translation.glossary;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Previews and publishes the latest immutable glossary revision. */
@RestController
@RequestMapping("/api/v1/glossaries/{glossaryId}/revisions/{revisionId}")
@Profile("postgres")
public class GlossaryRevisionPublicationController {

  private static final int MAX_RETURNED_LINT_ISSUES = 50;
  private static final int MAX_RETURNED_CHANGES = 100;
  private static final int MAX_RETURNED_MATCHES = 100;
  private static final int MAX_RETURNED_CONFLICTS = 50;
  public static final int MAX_SAMPLE_SOURCE_CODE_POINTS = 4_000;

  private final GlossaryRevisionPublicationService publicationService;
  private final PolicyService policyService;

  public GlossaryRevisionPublicationController(
      GlossaryRevisionPublicationService publicationService, PolicyService policyService) {
    this.publicationService = publicationService;
    this.policyService = policyService;
  }

  @PostMapping("/preview")
  public ResponseEntity<?> preview(
      Authentication authentication,
      @PathVariable UUID glossaryId,
      @PathVariable UUID revisionId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) PreviewRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to preview a glossary revision.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (ifMatch == null) {
      return problem(
          HttpStatus.PRECONDITION_REQUIRED,
          "IF_MATCH_REQUIRED",
          "Reload the glossary before previewing a revision.",
          request);
    }

    long expectedVersion = parseEtagVersion(ifMatch);
    String sampleSource = body == null || body.sampleSource() == null ? "" : body.sampleSource();
    if (sampleSource.codePointCount(0, sampleSource.length()) > MAX_SAMPLE_SOURCE_CODE_POINTS) {
      return problem(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "GLOSSARY_PREVIEW_SAMPLE_TOO_LARGE",
          "The glossary match sample may contain at most 4,000 characters.",
          request);
    }
    GlossaryRevisionPreviewService.Preview preview =
        publicationService.preview(
            actor.orElseThrow(), glossaryId, revisionId, expectedVersion, sampleSource);
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, etag(expectedVersion))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(PreviewResponse.from(preview, expectedVersion));
  }

  @PostMapping("/publish")
  public ResponseEntity<?> publish(
      Authentication authentication,
      @PathVariable UUID glossaryId,
      @PathVariable UUID revisionId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to publish a glossary revision.",
          request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (ifMatch == null) {
      return problem(
          HttpStatus.PRECONDITION_REQUIRED,
          "IF_MATCH_REQUIRED",
          "Reload the glossary before publishing a revision.",
          request);
    }

    long expectedVersion = parseEtagVersion(ifMatch);
    GlossaryRevisionPublicationRepository.Published published =
        publicationService.publish(actor.orElseThrow(), glossaryId, revisionId, expectedVersion);
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, etag(published.glossaryVersion()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(published);
  }

  @ExceptionHandler(GlossaryRevisionPublicationRepository.GlossaryNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "NOT_FOUND",
        "The glossary revision is unavailable or you cannot publish it.",
        request);
  }

  @ExceptionHandler(GlossaryRevisionPublicationRepository.VersionConflictException.class)
  public ResponseEntity<ProblemDetail> staleVersion(
      GlossaryRevisionPublicationRepository.VersionConflictException exception,
      HttpServletRequest request) {
    return conflict(
        exception.currentVersion(),
        "GLOSSARY_VERSION_STALE",
        "The glossary changed elsewhere. Reload it before publishing this revision.",
        request);
  }

  @ExceptionHandler(GlossaryRevisionPublicationRepository.RevisionNotLatestException.class)
  public ResponseEntity<ProblemDetail> notLatest(
      GlossaryRevisionPublicationRepository.RevisionNotLatestException exception,
      HttpServletRequest request) {
    return conflict(
        exception.currentVersion(),
        "GLOSSARY_REVISION_NOT_LATEST",
        "Only the latest immutable draft revision can be published.",
        request);
  }

  @ExceptionHandler(GlossaryRevisionPublicationService.RevisionAlreadyActiveException.class)
  public ResponseEntity<ProblemDetail> alreadyActive(
      GlossaryRevisionPublicationService.RevisionAlreadyActiveException exception,
      HttpServletRequest request) {
    return conflict(
        exception.currentVersion(),
        "GLOSSARY_REVISION_ALREADY_ACTIVE",
        "Only an unpublished latest draft revision can be previewed.",
        request);
  }

  @ExceptionHandler(GlossaryRevisionPublicationService.NotPublishableException.class)
  public ResponseEntity<ProblemDetail> notPublishable(
      GlossaryRevisionPublicationService.NotPublishableException exception,
      HttpServletRequest request) {
    GlossaryRevisionPreviewService.LintReport lint = exception.preview().lint();
    ProblemDetail problem =
        problemDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "GLOSSARY_REVISION_NOT_PUBLISHABLE",
            "Resolve glossary lint errors and matching conflicts before publishing.",
            request);
    problem.setProperty("errorCount", lint.errorCount());
    problem.setProperty("warningCount", lint.warningCount());
    problem.setProperty(
        "lintIssues", lint.issues().stream().limit(MAX_RETURNED_LINT_ISSUES).toList());
    problem.setProperty("lintIssuesTruncated", lint.issues().size() > MAX_RETURNED_LINT_ISSUES);
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, problem);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_GLOSSARY_PUBLISH_REQUEST",
        "If-Match must contain the strong glossary version ETag.",
        request);
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> unreadablePreviewRequest(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_GLOSSARY_PREVIEW_REQUEST",
        "Provide a valid JSON object with an optional sampleSource string.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "GLOSSARY_PUBLICATION_UNAVAILABLE",
        "The glossary revision could not be published. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before editing or publishing.",
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

  private static ResponseEntity<ProblemDetail> conflict(
      long currentVersion, String code, String detail, HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .header(HttpHeaders.ETAG, etag(currentVersion))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problemDetail(HttpStatus.CONFLICT, code, detail, request));
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return problem(status, problemDetail(status, code, detail, request));
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, ProblemDetail problem) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
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

  public record PreviewRequest(String sampleSource) {}

  public record PreviewResponse(
      UUID glossaryId,
      UUID baseRevisionId,
      UUID proposedRevisionId,
      long glossaryVersion,
      boolean canPublish,
      int changeCount,
      List<GlossaryRevisionPreviewService.Change> changes,
      boolean changesTruncated,
      long errorCount,
      long warningCount,
      List<GlossaryRevisionPreviewService.LintIssue> lintIssues,
      boolean lintIssuesTruncated,
      int matchCount,
      List<GlossaryMatcher.Match> matches,
      boolean matchesTruncated,
      int conflictCount,
      List<GlossaryMatcher.Conflict> conflicts,
      boolean conflictsTruncated) {

    private static PreviewResponse from(
        GlossaryRevisionPreviewService.Preview preview, long glossaryVersion) {
      List<GlossaryRevisionPreviewService.Change> changes = preview.changes();
      List<GlossaryRevisionPreviewService.LintIssue> lintIssues = preview.lint().issues();
      List<GlossaryMatcher.Match> matches = preview.sourceMatches().matches();
      List<GlossaryMatcher.Conflict> conflicts = preview.sourceMatches().conflicts();
      return new PreviewResponse(
          preview.glossaryId(),
          preview.baseRevisionId(),
          preview.proposedRevisionId(),
          glossaryVersion,
          preview.lint().canPublish(),
          changes.size(),
          changes.stream().limit(MAX_RETURNED_CHANGES).toList(),
          changes.size() > MAX_RETURNED_CHANGES,
          preview.lint().errorCount(),
          preview.lint().warningCount(),
          lintIssues.stream().limit(MAX_RETURNED_LINT_ISSUES).toList(),
          lintIssues.size() > MAX_RETURNED_LINT_ISSUES,
          matches.size(),
          matches.stream().limit(MAX_RETURNED_MATCHES).toList(),
          matches.size() > MAX_RETURNED_MATCHES,
          conflicts.size(),
          conflicts.stream().limit(MAX_RETURNED_CONFLICTS).toList(),
          conflicts.size() > MAX_RETURNED_CONFLICTS);
    }
  }
}
