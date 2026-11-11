package com.novelweb.library.reader;

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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated source-only Reader endpoints. */
@RestController
@RequestMapping("/api/v1")
@Profile("postgres")
public class ReaderController {

  private final ReaderService readerService;
  private final ReaderProgressService progressService;
  private final PolicyService policyService;

  public ReaderController(
      ReaderService readerService,
      ReaderProgressService progressService,
      PolicyService policyService) {
    this.readerService = readerService;
    this.progressService = progressService;
    this.policyService = policyService;
  }

  @GetMapping("/works/{workId}/reader-manifest")
  public ResponseEntity<?> manifest(
      Authentication authentication,
      @PathVariable UUID workId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "50") String limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to open this Reader work.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    return ok(readerService.manifest(actor.orElseThrow(), workId, cursor, parseLimit(limit)));
  }

  @GetMapping("/chapters/{chapterId}/content")
  public ResponseEntity<?> content(
      Authentication authentication,
      @PathVariable UUID chapterId,
      @RequestParam(required = false) String revision,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "250") String limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to read this chapter.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    return ok(
        readerService.content(
            actor.orElseThrow(),
            chapterId,
            parseOptionalUuid(revision, "revision"),
            cursor,
            parseLimit(limit)));
  }

  @GetMapping("/works/{workId}/progress")
  public ResponseEntity<?> progress(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to load reading progress.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    return progressResponse(progressService.get(actor.orElseThrow(), workId));
  }

  @PutMapping("/works/{workId}/progress")
  public ResponseEntity<?> saveProgress(
      Authentication authentication,
      @PathVariable UUID workId,
      @RequestBody(required = false) ReaderProgressService.SaveProgressRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to save reading progress.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    return progressResponse(progressService.save(actor.orElseThrow(), workId, body));
  }

  @ExceptionHandler(ReaderService.ReaderNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "NOT_FOUND",
        "The requested work or readable chapter is unavailable.",
        request);
  }

  @ExceptionHandler(ReaderCursorCodec.InvalidCursorException.class)
  public ResponseEntity<ProblemDetail> invalidCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_READER_CURSOR",
        "The cursor is malformed or belongs to another Reader resource.",
        request);
  }

  @ExceptionHandler(ReaderProgressRepository.ProgressNotFoundException.class)
  public ResponseEntity<ProblemDetail> inaccessibleProgress(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "NOT_FOUND",
        "The requested work or reading progress is unavailable.",
        request);
  }

  @ExceptionHandler(ReaderProgressRepository.InvalidProgressException.class)
  public ResponseEntity<ProblemDetail> invalidProgress(HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "INVALID_READER_PROGRESS",
        "The chapter or stable anchor is not part of readable content in this work.",
        request);
  }

  @ExceptionHandler(ReaderProgressRepository.ProgressVersionConflictException.class)
  public ResponseEntity<ProblemDetail> staleProgress(
      ReaderProgressRepository.ProgressVersionConflictException exception,
      HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Reading progress changed elsewhere. Reconcile with the latest server snapshot.");
    problem.setTitle(HttpStatus.CONFLICT.getReasonPhrase());
    problem.setProperty("code", "READER_PROGRESS_VERSION_STALE");
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", value instanceof String id ? id : "unavailable");
    problem.setProperty("latestProgress", exception.latestProgress());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .header(HttpHeaders.ETAG, progressEtag(exception.latestProgress().version()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }

  @ExceptionHandler(ReaderCursorCodec.SigningUnavailableException.class)
  public ResponseEntity<ProblemDetail> signingUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "READER_CURSOR_UNAVAILABLE",
        "Reader pagination is temporarily unavailable.",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidQuery(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_READER_QUERY", exception.getMessage(), request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "READER_UNAVAILABLE",
        "Reader content could not be loaded. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before reading.",
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
      throw new IllegalArgumentException("limit must be an integer in the supported range");
    }
  }

  private static UUID parseOptionalUuid(String value, String field) {
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

  private static ResponseEntity<?> progressResponse(
      ReaderProgressRepository.ProgressRecord progress) {
    return ResponseEntity.ok()
        .header(HttpHeaders.ETAG, progressEtag(progress.version()))
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(progress);
  }

  private static String progressEtag(long version) {
    return "\"progress-" + version + "\"";
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
}
