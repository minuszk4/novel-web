package com.novelweb.translation.jobs;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP endpoints for readiness and submission of complete human-edited snapshots. */
@RestController
@RequestMapping("/api/v1/translation/jobs/{jobId}/candidate")
@Profile("postgres & translation-fake")
@ConditionalOnProperty(prefix = "novel.translation.review", name = "enabled", havingValue = "true")
public class TranslationCandidateReviewController {

  private static final String IDEMPOTENCY_KEY = "[A-Za-z0-9._~-]{16,128}";
  private static final String SNAPSHOT_ETAG = "\"translation-review-[a-f0-9]{64}\"";
  private static final int MAX_EMPTY_BODY_PROBE_BYTES = 1;

  private final TranslationCandidateReviewService reviewService;
  private final PolicyService policyService;

  public TranslationCandidateReviewController(
      TranslationCandidateReviewService reviewService, PolicyService policyService) {
    this.reviewService = reviewService;
    this.policyService = policyService;
  }

  @GetMapping("/review-readiness")
  public ResponseEntity<?> readiness(
      Authentication authentication, @PathVariable String jobId, HttpServletRequest request) {
    UUID actorUserId = actorUserId(authentication);
    if (actorUserId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in before checking manual translation readiness.",
          request);
    }
    ResponseEntity<ProblemDetail> policyFailure = policyGate(actorUserId, request);
    if (policyFailure != null) return policyFailure;

    final UUID parsedJobId;
    try {
      parsedJobId = parseUuid(jobId);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST, "TRANSLATION_JOB_ID_INVALID", "jobId must be a UUID.", request);
    }

    try {
      TranslationCandidateReviewRepository.Readiness result =
          reviewService.readiness(actorUserId, parsedJobId);
      return switch (result.outcome()) {
        case AVAILABLE -> readinessResponse(result);
        case NOT_FOUND ->
            problem(
                HttpStatus.NOT_FOUND,
                "TRANSLATION_CANDIDATE_NOT_FOUND",
                "This translation candidate is unavailable.",
                request);
        case STALE_SOURCE ->
            problem(
                HttpStatus.CONFLICT,
                "TRANSLATION_SOURCE_STALE",
                "The source changed after this candidate was created. Refresh the current source.",
                request);
        case CANDIDATE_NOT_READY ->
            problem(
                HttpStatus.CONFLICT,
                "TRANSLATION_CANDIDATE_NOT_READY",
                "This candidate is not complete and cannot be submitted.",
                request);
        default -> throw new IllegalStateException("Unexpected manual review readiness outcome");
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATION_REVIEW_READINESS_UNAVAILABLE",
          "Manual translation readiness could not be checked. Please try again.",
          request);
    }
  }

  @PostMapping("/review-submissions")
  public ResponseEntity<?> submit(
      Authentication authentication, @PathVariable String jobId, HttpServletRequest request) {
    UUID actorUserId = actorUserId(authentication);
    if (actorUserId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in before submitting a manual translation.",
          request);
    }
    ResponseEntity<ProblemDetail> policyFailure = policyGate(actorUserId, request);
    if (policyFailure != null) return policyFailure;

    final UUID parsedJobId;
    try {
      parsedJobId = parseUuid(jobId);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST, "TRANSLATION_JOB_ID_INVALID", "jobId must be a UUID.", request);
    }

    if (hasRequestBody(request)) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "TRANSLATION_REVIEW_BODY_NOT_ALLOWED",
          "Submit the exact snapshot with headers only; do not send chapter or translation text.",
          request);
    }

    String idempotencyKey = singleHeader(request, "Idempotency-Key");
    if (idempotencyKey == null || !idempotencyKey.matches(IDEMPOTENCY_KEY)) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_INVALID",
          "Provide a 16 to 128 character Idempotency-Key for this submission attempt.",
          request);
    }
    String ifMatch = singleHeader(request, HttpHeaders.IF_MATCH);
    if (ifMatch == null) {
      return problem(
          HttpStatus.valueOf(428),
          "IF_MATCH_REQUIRED",
          "Reload readiness and send its snapshot ETag in If-Match.",
          request);
    }
    if (!ifMatch.matches(SNAPSHOT_ETAG)) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong manual-snapshot ETag from readiness.",
          request);
    }

    try {
      return submissionResponse(
          reviewService.submit(actorUserId, parsedJobId, ifMatch, idempotencyKey), request);
    } catch (TranslationCandidateReviewService.InvalidSnapshotEtagException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong manual-snapshot ETag from readiness.",
          request);
    } catch (TranslationCandidateReviewService.InvalidIdempotencyKeyException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_INVALID",
          "Provide a 16 to 128 character Idempotency-Key for this submission attempt.",
          request);
    } catch (TranslationCandidateReviewRepository.IdempotencyKeyReusedException exception) {
      return problem(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "This Idempotency-Key was already used for a different snapshot request.",
          request);
    } catch (TranslationCandidateReviewRepository.SubmissionConflictException exception) {
      return problem(
          HttpStatus.CONFLICT,
          "TRANSLATION_REVIEW_SUBMISSION_CONFLICT",
          "The candidate changed while the submission was being recorded. Reload readiness.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATION_REVIEW_SUBMISSION_UNAVAILABLE",
          "The manual translation could not be safely submitted. Retry with the same key.",
          request);
    }
  }

  private static ResponseEntity<?> readinessResponse(
      TranslationCandidateReviewRepository.Readiness readiness) {
    List<String> blockers = new ArrayList<>();
    if (!readiness.missingSegmentOrdinals().isEmpty()) blockers.add("INCOMPLETE_MANUAL_EDITS");
    if (readiness.pendingReview()) blockers.add("PENDING_REVIEW");
    ReadinessResponse response =
        new ReadinessResponse(
            readiness.jobId(),
            readiness.candidateId(),
            readiness.sourceRevisionId(),
            true,
            readiness.sourceLanguage(),
            readiness.targetLanguage(),
            readiness.editedSegments(),
            readiness.totalSegments(),
            readiness.missingSegmentOrdinals(),
            readiness.pendingReview(),
            readiness.snapshotDigest() == null
                ? null
                : TranslationReviewSnapshotHasher.digestHex(readiness.snapshotDigest()),
            blockers.isEmpty() && readiness.snapshotDigest() != null,
            blockers);
    ResponseEntity.BodyBuilder builder = noStore(HttpStatus.OK);
    if (readiness.snapshotDigest() != null) {
      builder.header(
          HttpHeaders.ETAG, TranslationReviewSnapshotHasher.etag(readiness.snapshotDigest()));
    }
    return builder.body(response);
  }

  private static ResponseEntity<?> submissionResponse(
      TranslationCandidateReviewRepository.SubmissionResult result, HttpServletRequest request) {
    return switch (result.outcome()) {
      case CREATED -> receipt(HttpStatus.CREATED, result.receipt());
      case REPLAYED -> receipt(HttpStatus.OK, result.receipt());
      case NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "TRANSLATION_CANDIDATE_NOT_FOUND",
              "This translation candidate is unavailable.",
              request);
      case STALE_SOURCE ->
          problem(
              HttpStatus.CONFLICT,
              "TRANSLATION_SOURCE_STALE",
              "The source changed after this candidate was created. Refresh the current source.",
              request);
      case CANDIDATE_NOT_READY ->
          problem(
              HttpStatus.CONFLICT,
              "TRANSLATION_CANDIDATE_NOT_READY",
              "This candidate is not complete and cannot be submitted.",
              request);
      case INCOMPLETE -> {
        ProblemDetail body =
            baseProblem(
                HttpStatus.CONFLICT,
                "TRANSLATION_REVIEW_INCOMPLETE",
                "Save a nonblank manual edit for every source segment before submission.",
                request);
        body.setProperty("editedSegments", result.editedSegments());
        body.setProperty("totalSegments", result.totalSegments());
        yield noStore(HttpStatus.CONFLICT).body(body);
      }
      case STALE_ETAG -> {
        ProblemDetail body =
            baseProblem(
                HttpStatus.valueOf(412),
                "TRANSLATION_REVIEW_SNAPSHOT_STALE",
                "Manual edits changed after readiness was read. Reload readiness before submitting.",
                request);
        body.setProperty("currentEtag", result.currentEtag());
        yield noStore(HttpStatus.valueOf(412))
            .header(HttpHeaders.ETAG, result.currentEtag())
            .body(body);
      }
      case PENDING_CONFLICT ->
          problem(
              HttpStatus.CONFLICT,
              "TRANSLATION_REVIEW_ALREADY_PENDING",
              "This candidate already has a pending reviewer submission.",
              request);
      default -> throw new IllegalStateException("Unexpected translation submission outcome");
    };
  }

  private static ResponseEntity<?> receipt(
      HttpStatus status, TranslationCandidateReviewRepository.SubmissionReceipt receipt) {
    String etag = "\"translation-review-" + receipt.snapshotDigest() + "\"";
    return noStore(status)
        .header(HttpHeaders.ETAG, etag)
        .body(
            new SubmissionResponse(
                receipt.submissionId(),
                receipt.candidateId(),
                receipt.status(),
                receipt.snapshotDigest(),
                receipt.segmentCount(),
                receipt.submittedAt(),
                receipt.replayed()));
  }

  private ResponseEntity<ProblemDetail> policyGate(UUID actorUserId, HttpServletRequest request) {
    try {
      return switch (policyService.requiredAcceptance(actorUserId)) {
        case COMPLETE -> null;
        case REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Accept the current Terms and Privacy Notice before using translation review.",
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

  private static UUID parseUuid(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("jobId is required");
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("jobId must be a valid UUID");
    }
  }

  private static String singleHeader(HttpServletRequest request, String name) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) return null;
    String value = values.nextElement();
    return values.hasMoreElements() ? null : value;
  }

  private static boolean hasRequestBody(HttpServletRequest request) {
    if (request.getContentLengthLong() > 0) return true;
    try {
      return request.getInputStream().readNBytes(MAX_EMPTY_BODY_PROBE_BYTES).length > 0;
    } catch (IOException exception) {
      return true;
    }
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return noStore(status).body(baseProblem(status, code, detail, request));
  }

  private static ProblemDetail baseProblem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", correlationId instanceof String id ? id : "unavailable");
    return problem;
  }

  public record ReadinessResponse(
      UUID jobId,
      UUID candidateId,
      UUID sourceRevisionId,
      boolean sourceRevisionCurrent,
      String sourceLanguage,
      String targetLanguage,
      int editedSegments,
      int totalSegments,
      List<Integer> missingSegmentOrdinals,
      boolean pendingReview,
      String snapshotDigest,
      boolean readyForSubmission,
      List<String> blockers) {
    public ReadinessResponse {
      missingSegmentOrdinals = List.copyOf(missingSegmentOrdinals);
      blockers = List.copyOf(blockers);
    }
  }

  public record SubmissionResponse(
      UUID submissionId,
      UUID candidateId,
      String status,
      String snapshotDigest,
      int segmentCount,
      java.time.Instant submittedAt,
      boolean replayed) {}
}
