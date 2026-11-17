package com.novelweb.library.publication;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owner-confirmed release of one reviewed draft chapter on a public work. */
@RestController
@RequestMapping("/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}/publication-release")
@Profile("postgres")
@ConditionalOnProperty(prefix = "novel.publication", name = "release-enabled", havingValue = "true")
public class PublicationChapterReleaseController {

  private static final int MAX_BODY_BYTES = 1_024;
  private static final JsonFactory STRICT_JSON_FACTORY =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final Pattern READINESS_ETAG =
      Pattern.compile("\"publication-snapshot-[a-f0-9]{64}\"");
  private static final Set<String> BODY_FIELDS = Set.of("actionCode");

  private final PublicationReleaseService releaseService;
  private final ObjectMapper objectMapper;

  public PublicationChapterReleaseController(
      PublicationReleaseService releaseService, ObjectMapper objectMapper) {
    this.releaseService = releaseService;
    this.objectMapper = objectMapper;
  }

  @PostMapping
  public ResponseEntity<?> release(
      Authentication authentication,
      @PathVariable UUID workId,
      @PathVariable UUID chapterId,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in as the work owner to publish this approved chapter.",
          correlationId);
    }
    if (!isJson(request.getContentType())) {
      return problem(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "UNSUPPORTED_MEDIA_TYPE",
          "Confirm the chapter release as JSON.",
          correlationId);
    }
    String key = singleHeader(request, "Idempotency-Key");
    if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_INVALID",
          "Provide a 16 to 128 character Idempotency-Key and reuse it for an exact retry.",
          correlationId);
    }
    String ifMatch = singleHeader(request, HttpHeaders.IF_MATCH);
    if (ifMatch == null) {
      return problem(
          HttpStatus.valueOf(428),
          "IF_MATCH_REQUIRED",
          "Reload chapter readiness and send its ETag in If-Match.",
          correlationId);
    }
    if (!READINESS_ETAG.matcher(ifMatch).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong publication-readiness ETag.",
          correlationId);
    }
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "REQUEST_TOO_LARGE",
          "The release confirmation may not exceed 1 KiB.",
          correlationId);
    }
    final byte[] body;
    try {
      body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    } catch (IOException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_INVALID",
          "The release confirmation could not be read.",
          correlationId);
    }
    if (body.length > MAX_BODY_BYTES || !isPublishNow(body)) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_ACTION_INVALID",
          "Confirm only the explicit PUBLISH_NOW action.",
          correlationId);
    }

    try {
      return response(
          releaseService.releaseChapter(principal.userId(), workId, chapterId, ifMatch, key),
          correlationId);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_RELEASE_UNAVAILABLE",
          "The approved chapter could not be safely published. Retry with the same Idempotency-Key.",
          correlationId);
    }
  }

  private boolean isPublishNow(byte[] body) {
    try (JsonParser parser = STRICT_JSON_FACTORY.createParser(body)) {
      JsonNode root = objectMapper.readTree(parser);
      if (root == null || !root.isObject() || parser.nextToken() != null) return false;
      Set<String> fields = new HashSet<>();
      fields.addAll(root.propertyNames());
      JsonNode actionCode = root.get("actionCode");
      return fields.equals(BODY_FIELDS)
          && actionCode != null
          && actionCode.isTextual()
          && "PUBLISH_NOW".equals(actionCode.textValue());
    } catch (JacksonException exception) {
      return false;
    }
  }

  private static ResponseEntity<?> response(
      PublicationReleaseService.ChapterReleaseResult result, UUID correlationId) {
    return switch (result.outcome()) {
      case CREATED -> noStore(HttpStatus.CREATED).body(body(result.receipt()));
      case REPLAYED -> noStore(HttpStatus.OK).body(body(result.receipt()));
      case KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "Use a new key for a different chapter release action.",
              correlationId);
      case CHAPTER_NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "DRAFT_CHAPTER_NOT_FOUND",
              "The current public-work draft chapter is not available.",
              correlationId);
      case APPROVAL_REQUIRED ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_APPROVAL_REQUIRED",
              "A current reviewer approval for this chapter and its required scopes is required.",
              correlationId);
      case POLICY_REQUIRED ->
          problem(
              HttpStatus.FORBIDDEN,
              "POLICY_ACCEPTANCE_REQUIRED",
              "Accept the current Terms and acknowledge the Privacy Notice first.",
              correlationId);
      case POLICY_NOT_CONFIGURED, RIGHTS_POLICY_NOT_CONFIGURED ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "POLICY_NOT_CONFIGURED",
              "Current approved policy documents are not available.",
              correlationId);
      case RIGHTS_POLICY_NOT_ACCEPTED ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_RIGHTS_POLICY_NOT_ACCEPTED",
              "Accept the current publication-rights policy before release.",
              correlationId);
      case RIGHTS_POLICY_STALE ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_RIGHTS_POLICY_STALE",
              "The approved rights policy changed. Refresh readiness and request a new review.",
              correlationId);
      case APPROVAL_CHANGED ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_APPROVAL_CHANGED",
              "The approved chapter or rights snapshot is no longer current.",
              correlationId);
      case STALE ->
          noStore(HttpStatus.PRECONDITION_FAILED)
              .header(HttpHeaders.ETAG, result.currentEtag())
              .body(
                  problemBody(
                      HttpStatus.PRECONDITION_FAILED,
                      "PUBLICATION_SNAPSHOT_STALE",
                      "Reload chapter readiness before publishing.",
                      correlationId));
    };
  }

  private static ChapterReleaseResponse body(
      PublicationReleaseService.ChapterReleaseReceipt receipt) {
    return new ChapterReleaseResponse(
        receipt.eventId(),
        receipt.workId(),
        receipt.chapterId(),
        receipt.requestId(),
        "PUBLISHED",
        receipt.publishedAt(),
        receipt.publicationContentVersion());
  }

  private static String singleHeader(HttpServletRequest request, String name) {
    Enumeration<String> values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) return null;
    String value = values.nextElement();
    if (values.hasMoreElements() || value == null) return null;
    value = value.trim();
    return value.isEmpty() ? null : value;
  }

  private static boolean isJson(String contentType) {
    if (contentType == null) return false;
    try {
      return MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    return noStore(status).body(problemBody(status, code, detail, correlationId));
  }

  private static ProblemDetail problemBody(
      HttpStatus status, String code, String detail, UUID correlationId) {
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

  public record ChapterReleaseResponse(
      UUID eventId,
      UUID workId,
      UUID chapterId,
      UUID requestId,
      String state,
      java.time.Instant publishedAt,
      long publicationContentVersion) {}
}
