package com.novelweb.translation.jobs;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Explicit owner release endpoints for accepted manual translations. */
@RestController
@Profile("postgres & translation-fake")
@ConditionalOnExpression(
    "'${novel.translation.review.enabled:false}' == 'true' and "
        + "'${novel.translation.publication.enabled:false}' == 'true'")
public class TranslationPublicationController {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final Pattern SNAPSHOT_ETAG =
      Pattern.compile("\"translation-review-[a-f0-9]{64}\"");
  private static final Pattern VERSION_ETAG =
      Pattern.compile("\"translation-version-[0-9a-fA-F-]{36}-[1-9][0-9]*\"");

  private final TranslationPublicationService publicationService;

  public TranslationPublicationController(TranslationPublicationService publicationService) {
    this.publicationService = publicationService;
  }

  @GetMapping("/api/v1/authoring/manuscripts/{workId}/translations/accepted")
  public ResponseEntity<?> accepted(
      Authentication authentication,
      @PathVariable String workId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") String limit,
      HttpServletRequest request) {
    UUID actorId = actorId(authentication);
    if (actorId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in as the work owner to load accepted translations.",
          request);
    }
    UUID parsedWorkId = parseId(workId);
    if (parsedWorkId == null) {
      return problem(HttpStatus.BAD_REQUEST, "WORK_ID_INVALID", "workId must be a UUID.", request);
    }
    try {
      TranslationPublicationService.AcceptedQueue queue =
          publicationService.accepted(actorId, parsedWorkId, cursor, parseLimit(limit));
      return switch (queue.outcome()) {
        case AVAILABLE ->
            noStore(HttpStatus.OK)
                .body(
                    new AcceptedResponse(
                        queue.items().stream().map(AcceptedTranslation::from).toList(),
                        queue.nextCursor(),
                        queue.hasMore()));
        case POLICY_REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Accept the current Terms and Privacy Notice before releasing translations.",
                request);
        case POLICY_NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_NOT_CONFIGURED",
                "Current approved policy documents are not available.",
                request);
        case INVALID_LIMIT ->
            problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_TRANSLATION_PUBLICATION_LIMIT",
                "limit must be between 1 and 50.",
                request);
        case INVALID_CURSOR ->
            problem(
                HttpStatus.BAD_REQUEST,
                "TRANSLATION_PUBLICATION_CURSOR_INVALID",
                "Reload the accepted translation list and use its current cursor.",
                request);
        case CURSOR_UNAVAILABLE ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TRANSLATION_PUBLICATION_CURSOR_UNAVAILABLE",
                "The accepted translation list is temporarily unavailable.",
                request);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATION_PUBLICATION_QUEUE_UNAVAILABLE",
          "Accepted translations could not be loaded. Please try again.",
          request);
    }
  }

  @GetMapping("/api/v1/authoring/manuscripts/{workId}/translations/history")
  public ResponseEntity<?> history(
      Authentication authentication,
      @PathVariable String workId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") String limit,
      HttpServletRequest request) {
    UUID actorId = actorId(authentication);
    if (actorId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in as the work owner to load translation history.",
          request);
    }
    UUID parsedWorkId = parseId(workId);
    if (parsedWorkId == null) {
      return problem(HttpStatus.BAD_REQUEST, "WORK_ID_INVALID", "workId must be a UUID.", request);
    }
    try {
      TranslationPublicationService.ReleaseHistory history =
          publicationService.releaseHistory(actorId, parsedWorkId, cursor, parseLimit(limit));
      return switch (history.outcome()) {
        case AVAILABLE ->
            noStore(HttpStatus.OK)
                .body(
                    new HistoryResponse(
                        history.items().stream().map(VersionHistory::from).toList(),
                        history.nextCursor(),
                        history.hasMore()));
        case POLICY_REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Accept the current Terms and Privacy Notice before viewing translation history.",
                request);
        case POLICY_NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_NOT_CONFIGURED",
                "Current approved policy documents are not available.",
                request);
        case INVALID_LIMIT ->
            problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_TRANSLATION_HISTORY_LIMIT",
                "limit must be between 1 and 50.",
                request);
        case INVALID_CURSOR ->
            problem(
                HttpStatus.BAD_REQUEST,
                "TRANSLATION_HISTORY_CURSOR_INVALID",
                "Reload translation history and use its current cursor.",
                request);
        case CURSOR_UNAVAILABLE ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TRANSLATION_HISTORY_CURSOR_UNAVAILABLE",
                "Translation history is temporarily unavailable.",
                request);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATION_HISTORY_UNAVAILABLE",
          "Translation history could not be loaded. Please try again.",
          request);
    }
  }

  @PostMapping("/api/v1/authoring/translations/review-submissions/{submissionId}/release")
  public ResponseEntity<?> release(
      Authentication authentication,
      @PathVariable String submissionId,
      HttpServletRequest request) {
    UUID actorId = actorId(authentication);
    if (actorId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in as the work owner to release an accepted translation.",
          request);
    }
    UUID parsedSubmissionId = parseId(submissionId);
    if (parsedSubmissionId == null) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "TRANSLATION_REVIEW_SUBMISSION_ID_INVALID",
          "submissionId must be a UUID.",
          request);
    }
    if (hasBody(request)) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "TRANSLATION_RELEASE_BODY_NOT_ALLOWED",
          "The release uses the accepted snapshot only; do not send translation text.",
          request);
    }
    String idempotencyKey = singleHeader(request, "Idempotency-Key");
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_INVALID",
          "Provide a 16 to 128 character Idempotency-Key for this release.",
          request);
    }
    String ifMatch = singleHeader(request, HttpHeaders.IF_MATCH);
    if (ifMatch == null) {
      return problem(
          HttpStatus.valueOf(428),
          "IF_MATCH_REQUIRED",
          "Reload the accepted snapshot and send its ETag in If-Match.",
          request);
    }
    if (!SNAPSHOT_ETAG.matcher(ifMatch).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong accepted-snapshot ETag.",
          request);
    }

    try {
      TranslationPublicationService.ReleaseResult result =
          publicationService.publish(actorId, parsedSubmissionId, ifMatch, idempotencyKey);
      return releaseResponse(result, request);
    } catch (TranslationPublicationService.IdempotencyConflictException exception) {
      return problem(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "This Idempotency-Key was already used for a different release request.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATION_RELEASE_UNAVAILABLE",
          "The accepted translation could not be safely released. Retry with the same key.",
          request);
    }
  }

  @PostMapping("/api/v1/authoring/translations/versions/{versionId}/rollback")
  public ResponseEntity<?> rollback(
      Authentication authentication, @PathVariable String versionId, HttpServletRequest request) {
    UUID actorId = actorId(authentication);
    if (actorId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in as the work owner to restore a translation version.",
          request);
    }
    UUID parsedVersionId = parseId(versionId);
    if (parsedVersionId == null) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "TRANSLATION_VERSION_ID_INVALID",
          "versionId must be a UUID.",
          request);
    }
    if (hasBody(request)) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "TRANSLATION_ROLLBACK_BODY_NOT_ALLOWED",
          "A rollback restores a retained version; do not send translation text.",
          request);
    }
    String idempotencyKey = singleHeader(request, "Idempotency-Key");
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_INVALID",
          "Provide a 16 to 128 character Idempotency-Key for this rollback.",
          request);
    }
    String ifMatch = singleHeader(request, HttpHeaders.IF_MATCH);
    if (ifMatch == null) {
      return problem(
          HttpStatus.valueOf(428),
          "IF_MATCH_REQUIRED",
          "Reload translation history and send the current version ETag in If-Match.",
          request);
    }
    if (!VERSION_ETAG.matcher(ifMatch).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong ETag of the current translation version.",
          request);
    }
    try {
      return releaseResponse(
          publicationService.rollbackToVersion(actorId, parsedVersionId, ifMatch, idempotencyKey),
          request);
    } catch (TranslationPublicationService.IdempotencyConflictException exception) {
      return problem(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "This Idempotency-Key was already used for a different release request.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSLATION_ROLLBACK_UNAVAILABLE",
          "The retained version could not be restored safely. Retry with the same key.",
          request);
    }
  }

  private static ResponseEntity<?> releaseResponse(
      TranslationPublicationService.ReleaseResult result, HttpServletRequest request) {
    return switch (result.outcome()) {
      case CREATED -> receipt(HttpStatus.CREATED, result.receipt());
      case ROLLED_BACK -> receipt(HttpStatus.CREATED, result.receipt());
      case REPLAYED -> receipt(HttpStatus.OK, result.receipt());
      case NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "TRANSLATION_RELEASE_UNAVAILABLE",
              "This accepted translation is not available to this work owner.",
              request);
      case NOT_ACCEPTED ->
          problem(
              HttpStatus.CONFLICT,
              "TRANSLATION_REVIEW_NOT_ACCEPTED",
              "Only an accepted manual translation snapshot can be released.",
              request);
      case ALREADY_RELEASED ->
          problem(
              HttpStatus.CONFLICT,
              "TRANSLATION_SNAPSHOT_ALREADY_RELEASED",
              "This accepted snapshot was already released. Use a new reviewed snapshot for another publication.",
              request);
      case NO_CURRENT_VERSION ->
          problem(
              HttpStatus.CONFLICT,
              "TRANSLATION_CURRENT_VERSION_MISSING",
              "The current translation changed. Reload history before retrying.",
              request);
      case ROLLBACK_TARGET_UNAVAILABLE ->
          problem(
              HttpStatus.CONFLICT,
              "TRANSLATION_ROLLBACK_TARGET_UNAVAILABLE",
              "This retained version is not eligible for rollback to the current source.",
              request);
      case STALE_SOURCE ->
          problem(
              HttpStatus.CONFLICT,
              "TRANSLATION_SOURCE_STALE",
              "The chapter changed after review. Create and review a translation for the current source.",
              request);
      case STALE_ETAG ->
          problemWithEtag(
              HttpStatus.valueOf(412),
              "TRANSLATION_RELEASE_SNAPSHOT_STALE",
              "The accepted snapshot changed. Reload the owner release list before retrying.",
              result.currentEtag(),
              request);
      case POLICY_REQUIRED ->
          problem(
              HttpStatus.FORBIDDEN,
              "POLICY_ACCEPTANCE_REQUIRED",
              "Accept the current Terms and Privacy Notice before releasing translations.",
              request);
      case POLICY_NOT_CONFIGURED ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "POLICY_NOT_CONFIGURED",
              "Current approved policy documents are not available.",
              request);
      case RIGHTS_REQUIRED ->
          problem(
              HttpStatus.CONFLICT,
              "TRANSLATION_ADAPTATION_RIGHTS_REQUIRED",
              "Current translation adaptation rights approval is required before release.",
              request);
      case SNAPSHOT_UNAVAILABLE ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "TRANSLATION_RELEASE_SNAPSHOT_UNAVAILABLE",
              "The accepted manual snapshot could not be verified safely.",
              request);
      case KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "This Idempotency-Key was already used for a different release request.",
              request);
    };
  }

  private static ResponseEntity<?> receipt(
      HttpStatus status, TranslationPublicationService.ReleaseReceipt receipt) {
    String etag = "\"translation-review-" + receipt.snapshotDigest() + "\"";
    return noStore(status)
        .header(HttpHeaders.ETAG, etag)
        .body(
            new ReleaseReceipt(
                receipt.versionId(),
                receipt.versionNo(),
                receipt.snapshotDigest(),
                receipt.releasedAt(),
                receipt.replayed()));
  }

  private static ResponseEntity<?> problemWithEtag(
      HttpStatus status, String code, String detail, String etag, HttpServletRequest request) {
    return noStore(status)
        .header(HttpHeaders.ETAG, etag)
        .body(problemBody(status, code, detail, request));
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    return noStore(status).body(problemBody(status, code, detail, request));
  }

  private static ProblemDetail problemBody(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty(
        "correlationId", correlationId instanceof String value ? value : "unavailable");
    return problem;
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static UUID actorId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return null;
    }
    return principal.userId();
  }

  private static UUID parseId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static int parseLimit(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  private static String singleHeader(HttpServletRequest request, String name) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) return null;
    String value = values.nextElement();
    return values.hasMoreElements() ? null : value;
  }

  private static boolean hasBody(HttpServletRequest request) {
    try {
      return request.getInputStream().readNBytes(1).length != 0;
    } catch (IOException exception) {
      return true;
    }
  }

  public record AcceptedResponse(
      List<AcceptedTranslation> items, String nextCursor, boolean hasMore) {
    public AcceptedResponse {
      items = List.copyOf(items);
    }
  }

  public record AcceptedTranslation(
      UUID submissionId,
      UUID chapterId,
      String workTitle,
      String chapterTitle,
      String targetLanguage,
      String snapshotDigest,
      int segmentCount,
      java.time.Instant submittedAt,
      boolean rightsCurrent,
      UUID currentVersionId,
      Integer currentVersionNo) {
    private static AcceptedTranslation from(TranslationPublicationService.AcceptedItem item) {
      return new AcceptedTranslation(
          item.submissionId(),
          item.chapterId(),
          item.workTitle(),
          item.chapterTitle(),
          item.targetLanguage(),
          item.snapshotDigest(),
          item.segmentCount(),
          item.submittedAt(),
          item.rightsCurrent(),
          item.currentVersionId(),
          item.currentVersionNo());
    }
  }

  public record ReleaseReceipt(
      UUID versionId,
      int versionNo,
      String snapshotDigest,
      java.time.Instant releasedAt,
      boolean replayed) {}

  public record HistoryResponse(List<VersionHistory> items, String nextCursor, boolean hasMore) {
    public HistoryResponse {
      items = List.copyOf(items);
    }
  }

  public record VersionHistory(
      UUID eventId,
      UUID versionId,
      UUID chapterId,
      String chapterTitle,
      String targetLanguage,
      int versionNo,
      String actionCode,
      UUID rollbackOfVersionId,
      java.time.Instant releasedAt,
      boolean current,
      UUID currentVersionId,
      Integer currentVersionNo,
      String currentVersionEtag,
      boolean rollbackAllowed) {
    private static VersionHistory from(TranslationPublicationService.VersionHistoryItem item) {
      return new VersionHistory(
          item.eventId(),
          item.versionId(),
          item.chapterId(),
          item.chapterTitle(),
          item.targetLanguage(),
          item.versionNo(),
          item.actionCode(),
          item.rollbackOfVersionId(),
          item.releasedAt(),
          item.current(),
          item.currentVersionId(),
          item.currentVersionNo(),
          item.currentVersionEtag(),
          item.rollbackAllowed());
    }
  }
}
