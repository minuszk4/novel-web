package com.novelweb.translation.jobs;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP lifecycle operations for the explicitly enabled fake-local translation pipeline. */
@RestController
@RequestMapping("/api/v1/translation/jobs")
@Profile("postgres & translation-fake")
@ConditionalOnProperty(
    prefix = "novel.translation.fake",
    name = {"enabled", "api-enabled"},
    havingValue = "true")
public class TranslationJobController {

  private final TranslationJobService jobService;
  private final TranslationJobStatusService statusService;
  private final TranslationJobRetryService retryService;
  private final TranslationJobCancelService cancelService;
  private final TranslationCandidatePreviewService candidatePreviewService;
  private final PolicyService policyService;

  public TranslationJobController(
      TranslationJobService jobService,
      TranslationJobStatusService statusService,
      TranslationJobRetryService retryService,
      TranslationJobCancelService cancelService,
      TranslationCandidatePreviewService candidatePreviewService,
      PolicyService policyService) {
    this.jobService = jobService;
    this.statusService = statusService;
    this.retryService = retryService;
    this.cancelService = cancelService;
    this.candidatePreviewService = candidatePreviewService;
    this.policyService = policyService;
  }

  @PostMapping
  public ResponseEntity<?> enqueue(
      Authentication authentication,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) EnqueueRequest body,
      HttpServletRequest request) {
    UUID actorUserId = actorUserId(authentication);
    if (actorUserId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in before requesting a translation.",
          request);
    }
    ResponseEntity<?> policyFailure = policyGate(actorUserId, request);
    if (policyFailure != null) return policyFailure;
    if (body == null) throw new IllegalArgumentException("translation request body is required");

    TranslationJobService.EnqueueResult result =
        jobService.enqueue(
            actorUserId,
            parseRequiredUuid(body.chapterId(), "chapterId"),
            parseRequiredUuid(body.sourceRevisionId(), "sourceRevisionId"),
            body.targetLanguage(),
            idempotencyKey);
    TranslationJobRepository.Job job = result.job();
    HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED;
    return noStore(status)
        .header(HttpHeaders.LOCATION, "/api/v1/translation/jobs/" + job.id())
        .header(HttpHeaders.RETRY_AFTER, "2")
        .body(
            new EnqueueResponse(
                job.id(),
                job.status(),
                job.chapterId(),
                job.sourceRevisionId(),
                job.sourceLanguage(),
                job.targetLanguage(),
                job.providerId(),
                job.totalUnits(),
                result.replayed()));
  }

  @GetMapping
  public ResponseEntity<?> listJobs(
      Authentication authentication,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") int limit,
      HttpServletRequest request) {
    UUID actorUserId = actorUserId(authentication);
    if (actorUserId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to view translation jobs.", request);
    }
    ResponseEntity<?> policyFailure = policyGate(actorUserId, request);
    if (policyFailure != null) return policyFailure;
    TranslationJobStatusService.JobPage page =
        statusService.listManageable(actorUserId, cursor, limit);
    return noStore(HttpStatus.OK).body(new JobListResponse(page.items(), page.nextCursor()));
  }

  @GetMapping("/{jobId}")
  public ResponseEntity<?> status(
      Authentication authentication, @PathVariable String jobId, HttpServletRequest request) {
    UUID actorUserId = actorUserId(authentication);
    if (actorUserId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view translation progress.",
          request);
    }
    ResponseEntity<?> policyFailure = policyGate(actorUserId, request);
    if (policyFailure != null) return policyFailure;
    Optional<TranslationJobStatusService.StatusResult> result =
        statusService.findVisible(actorUserId, parseRequiredUuid(jobId, "jobId"));
    if (result.isEmpty()) {
      return problem(
          HttpStatus.NOT_FOUND,
          "TRANSLATION_JOB_NOT_FOUND",
          "This translation job is unavailable.",
          request);
    }
    TranslationJobStatusService.StatusResult status = result.orElseThrow();
    ResponseEntity.BodyBuilder response = noStore(HttpStatus.OK);
    if (!terminal(status.job().status())) response.header(HttpHeaders.RETRY_AFTER, "2");
    return response.body(status);
  }

  @GetMapping("/{jobId}/candidate")
  public ResponseEntity<?> candidatePreview(
      Authentication authentication,
      @PathVariable String jobId,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "20") int limit,
      HttpServletRequest request) {
    UUID actorUserId = actorUserId(authentication);
    if (actorUserId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view the translation preview.",
          request);
    }
    ResponseEntity<?> policyFailure = policyGate(actorUserId, request);
    if (policyFailure != null) return policyFailure;
    if (offset < 0 || offset > 5000 || limit < 1 || limit > 25) {
      throw new IllegalArgumentException("candidate preview page is outside supported bounds");
    }
    Optional<TranslationCandidatePreviewService.CandidatePreview> preview =
        candidatePreviewService.findCurrentReadyForReview(
            actorUserId, parseRequiredUuid(jobId, "jobId"), offset, limit);
    if (preview.isEmpty()) {
      return problem(
          HttpStatus.NOT_FOUND,
          "TRANSLATION_CANDIDATE_NOT_FOUND",
          "This translation candidate is unavailable.",
          request);
    }
    return noStore(HttpStatus.OK).body(preview.orElseThrow());
  }

  @PostMapping("/{jobId}/retry")
  public ResponseEntity<?> retry(
      Authentication authentication,
      @PathVariable String jobId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    UUID actorUserId = actorUserId(authentication);
    if (actorUserId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in before retrying this translation.",
          request);
    }
    ResponseEntity<?> policyFailure = policyGate(actorUserId, request);
    if (policyFailure != null) return policyFailure;
    TranslationJobRetryRepository.RetryResult result =
        retryService.retryFailedUnits(
            actorUserId, parseRequiredUuid(jobId, "jobId"), idempotencyKey);
    return noStore(HttpStatus.ACCEPTED)
        .header(HttpHeaders.LOCATION, "/api/v1/translation/jobs/" + result.jobId())
        .header(HttpHeaders.RETRY_AFTER, "2")
        .body(result);
  }

  @PostMapping("/{jobId}/cancel")
  public ResponseEntity<?> cancel(
      Authentication authentication,
      @PathVariable String jobId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    UUID actorUserId = actorUserId(authentication);
    if (actorUserId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in before cancelling this translation.",
          request);
    }
    ResponseEntity<?> policyFailure = policyGate(actorUserId, request);
    if (policyFailure != null) return policyFailure;
    TranslationJobCancelRepository.CancelResult result =
        cancelService.cancel(actorUserId, parseRequiredUuid(jobId, "jobId"), idempotencyKey);
    return noStore(HttpStatus.OK).body(result);
  }

  @ExceptionHandler(TranslationJobRepository.ChapterNotFoundException.class)
  public ResponseEntity<ProblemDetail> unavailableSource(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "TRANSLATION_SOURCE_NOT_FOUND",
        "The chapter is unavailable or is not ready for translation.",
        request);
  }

  @ExceptionHandler(TranslationJobRepository.SourceRevisionStaleException.class)
  public ResponseEntity<ProblemDetail> staleSource(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "TRANSLATION_SOURCE_STALE",
        "The source chapter changed. Refresh it before requesting translation.",
        request);
  }

  @ExceptionHandler(TranslationJobRepository.IdempotencyKeyReusedException.class)
  public ResponseEntity<ProblemDetail> enqueueKeyConflict(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "This idempotency key was already used for a different translation request.",
        request);
  }

  @ExceptionHandler({
    TranslationJobRetryRepository.JobNotFoundException.class,
    TranslationJobCancelRepository.JobNotFoundException.class
  })
  public ResponseEntity<ProblemDetail> unavailableJob(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "TRANSLATION_JOB_NOT_FOUND",
        "This translation job is unavailable.",
        request);
  }

  @ExceptionHandler(TranslationCandidatePreviewRepository.SourceRevisionStaleException.class)
  public ResponseEntity<ProblemDetail> staleCandidateSource(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "TRANSLATION_SOURCE_STALE",
        "The chapter changed after this candidate was created. Refresh the current source before reviewing it.",
        request);
  }

  @ExceptionHandler(TranslationCandidatePreviewRepository.CandidateNotReadyException.class)
  public ResponseEntity<ProblemDetail> candidateNotReady(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "TRANSLATION_CANDIDATE_NOT_READY",
        "This candidate is not ready for preview.",
        request);
  }

  @ExceptionHandler({
    TranslationJobRetryRepository.SourceRevisionStaleException.class,
    TranslationJobRetryRepository.RetryNotAllowedException.class,
    TranslationJobRetryRepository.NoRetryableUnitsException.class,
    TranslationJobRetryRepository.RetryConflictException.class,
    TranslationJobCancelRepository.CancelNotAllowedException.class,
    TranslationJobCancelRepository.CancelConflictException.class,
    TranslationJobRepository.UnsupportedLanguagePairException.class,
    TranslationChunkPlanner.UnsupportedLanguagePairException.class
  })
  public ResponseEntity<ProblemDetail> actionConflict(
      RuntimeException exception, HttpServletRequest request) {
    String code =
        unsupportedLanguage(exception)
            ? "TRANSLATION_LANGUAGE_UNSUPPORTED"
            : exception instanceof TranslationJobRetryRepository.SourceRevisionStaleException
                ? "TRANSLATION_SOURCE_STALE"
                : "TRANSLATION_ACTION_NOT_AVAILABLE";
    HttpStatus status =
        unsupportedLanguage(exception) ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.CONFLICT;
    String detail =
        unsupportedLanguage(exception)
            ? "This source and target language pair is not supported by the local preview."
            : exception instanceof TranslationJobRetryRepository.SourceRevisionStaleException
                ? "The source chapter changed. Refresh it before retrying translation."
                : "This translation action is no longer available for the current job state.";
    return problem(status, code, detail, request);
  }

  private static boolean unsupportedLanguage(RuntimeException exception) {
    return exception instanceof TranslationJobRepository.UnsupportedLanguagePairException
        || exception instanceof TranslationChunkPlanner.UnsupportedLanguagePairException;
  }

  @ExceptionHandler(TranslationChunkPlanner.InputLimitExceededException.class)
  public ResponseEntity<ProblemDetail> inputLimit(HttpServletRequest request) {
    return problem(
        HttpStatus.CONTENT_TOO_LARGE,
        "TRANSLATION_INPUT_LIMIT_EXCEEDED",
        "The source chapter exceeds the supported offline-preview size limits.",
        request);
  }

  @ExceptionHandler({
    TranslationChunkPlanner.NoTranslatableSegmentsException.class,
    TranslationChunkPlanner.SegmentTooLargeException.class,
    TranslationChunkPlanner.InvalidSourceSegmentsException.class
  })
  public ResponseEntity<ProblemDetail> invalidSource(HttpServletRequest request) {
    return problem(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "TRANSLATION_SOURCE_INVALID",
        "The chapter does not contain a valid supported source snapshot for translation.",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_TRANSLATION_REQUEST",
        "The translation request, identifier or idempotency key is malformed.",
        request);
  }

  @ExceptionHandler(TranslationJobCursorCodec.SigningUnavailableException.class)
  public ResponseEntity<ProblemDetail> cursorSigningUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "TRANSLATION_PAGINATION_UNAVAILABLE",
        "Translation job pagination is temporarily unavailable.",
        request);
  }

  @ExceptionHandler(TranslationJobCursorCodec.InvalidCursorException.class)
  public ResponseEntity<ProblemDetail> invalidCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "TRANSLATION_CURSOR_INVALID",
        "Refresh the translation job list and try again.",
        request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_TRANSLATION_REQUEST",
        "The translation request body is missing or malformed.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "TRANSLATION_UNAVAILABLE",
        "Translation data or policy state is temporarily unavailable. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before using translation.",
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

  private static UUID actorUserId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return null;
    }
    return principal.userId();
  }

  private static UUID parseRequiredUuid(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " is required");
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a valid UUID");
    }
  }

  private static boolean terminal(String status) {
    return "review_required".equals(status)
        || "completed".equals(status)
        || "failed".equals(status)
        || "cancelled".equals(status);
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", correlationId instanceof String id ? id : "unavailable");
    return noStore(status).body(problem);
  }

  public record EnqueueRequest(String chapterId, String sourceRevisionId, String targetLanguage) {}

  public record EnqueueResponse(
      UUID jobId,
      String status,
      UUID chapterId,
      UUID sourceRevisionId,
      String sourceLanguage,
      String targetLanguage,
      String providerId,
      int totalUnits,
      boolean replayed) {}

  public record JobListResponse(
      java.util.List<TranslationJobStatusService.StatusResult> items, String nextCursor) {

    public JobListResponse {
      items = java.util.List.copyOf(items);
    }
  }
}
