package com.novelweb.library.publication;

import com.novelweb.identity.session.SessionService;
import com.novelweb.library.publication.PublicationScheduleRepository.CancelOutcome;
import com.novelweb.library.publication.PublicationScheduleRepository.ScheduleView;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

/** Owner-only scheduling controls, independently disabled by default. */
@RestController
@RequestMapping("/api/v1/authoring/manuscripts/{workId}/publication-schedules")
@Profile("postgres")
@ConditionalOnProperty(prefix = "novel.publication", name = "release-enabled", havingValue = "true")
@ConditionalOnProperty(
    prefix = "novel.publication.scheduler",
    name = "api-enabled",
    havingValue = "true")
public class PublicationScheduleController {

  private static final int MAX_BODY_BYTES = 1_024;
  private static final JsonFactory STRICT_JSON_FACTORY =
      JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final Pattern READINESS_ETAG =
      Pattern.compile("\"publication-snapshot-[a-f0-9]{64}\"");
  private static final Set<String> BODY_FIELDS = Set.of("scheduledAt");

  private final PublicationScheduleService scheduleService;
  private final ObjectMapper objectMapper;

  public PublicationScheduleController(
      PublicationScheduleService scheduleService, ObjectMapper objectMapper) {
    this.scheduleService = scheduleService;
    this.objectMapper = objectMapper;
  }

  @GetMapping
  public ResponseEntity<?> getActive(
      Authentication authentication, @PathVariable String workId, HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view this publication schedule.",
          correlationId);
    }
    UUID parsedWorkId;
    try {
      parsedWorkId = UUID.fromString(workId);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.NOT_FOUND,
          "MANUSCRIPT_NOT_FOUND",
          "The private authored manuscript could not be found.",
          correlationId);
    }
    try {
      Optional<ScheduleView> active = scheduleService.getActive(principal.userId(), parsedWorkId);
      return active
          .<ResponseEntity<?>>map(value -> noStore(HttpStatus.OK).body(value))
          .orElseGet(() -> noStore(HttpStatus.NO_CONTENT).build());
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_SCHEDULE_UNAVAILABLE",
          "The publication schedule could not be loaded.",
          correlationId);
    }
  }

  @PostMapping
  public ResponseEntity<?> schedule(
      Authentication authentication, @PathVariable String workId, HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in as the manuscript owner to schedule publication.",
          correlationId);
    }
    if (!isJson(request.getContentType())) {
      return problem(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "UNSUPPORTED_MEDIA_TYPE",
          "Send the publication schedule as JSON.",
          correlationId);
    }
    UUID parsedWorkId;
    try {
      parsedWorkId = UUID.fromString(workId);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_WORK_ID_INVALID",
          "workId must be a UUID.",
          correlationId);
    }

    String idempotencyKey = singleHeader(request, "Idempotency-Key");
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
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
          "Reload publication readiness and send its ETag in If-Match.",
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
          "The publication schedule body may not exceed 1 KiB.",
          correlationId);
    }
    byte[] body;
    try {
      body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    } catch (IOException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_INVALID",
          "The publication schedule body could not be read.",
          correlationId);
    }
    if (body.length == 0) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_REQUIRED",
          "Provide a scheduledAt RFC 3339 timestamp with an explicit UTC offset.",
          correlationId);
    }
    if (body.length > MAX_BODY_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "REQUEST_TOO_LARGE",
          "The publication schedule body may not exceed 1 KiB.",
          correlationId);
    }
    Optional<Instant> maybeScheduledAt = parseScheduledAt(body);
    if (maybeScheduledAt.isEmpty()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_SCHEDULE_TIME_INVALID",
          "Provide only a future scheduledAt RFC 3339 timestamp with an explicit UTC offset.",
          correlationId);
    }

    try {
      PublicationReleaseService.ScheduleResult result =
          scheduleService.schedule(
              principal.userId(),
              parsedWorkId,
              ifMatch,
              maybeScheduledAt.orElseThrow(),
              idempotencyKey);
      return scheduleResponse(result, correlationId);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_SCHEDULE_UNAVAILABLE",
          "The work could not be safely scheduled. Retry with the same Idempotency-Key.",
          correlationId);
    }
  }

  @GetMapping("/{scheduleId}")
  public ResponseEntity<?> get(
      Authentication authentication,
      @PathVariable String workId,
      @PathVariable String scheduleId,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view this publication schedule.",
          correlationId);
    }
    UUID parsedWorkId;
    UUID parsedScheduleId;
    try {
      parsedWorkId = UUID.fromString(workId);
      parsedScheduleId = UUID.fromString(scheduleId);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.NOT_FOUND,
          "PUBLICATION_SCHEDULE_NOT_FOUND",
          "The publication schedule could not be found.",
          correlationId);
    }
    try {
      Optional<ScheduleView> view =
          scheduleService.get(principal.userId(), parsedWorkId, parsedScheduleId);
      return view.<ResponseEntity<?>>map(value -> noStore(HttpStatus.OK).body(value))
          .orElseGet(
              () ->
                  problem(
                      HttpStatus.NOT_FOUND,
                      "PUBLICATION_SCHEDULE_NOT_FOUND",
                      "The publication schedule could not be found.",
                      correlationId));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_SCHEDULE_UNAVAILABLE",
          "The publication schedule could not be loaded.",
          correlationId);
    }
  }

  @DeleteMapping("/{scheduleId}")
  public ResponseEntity<?> cancel(
      Authentication authentication,
      @PathVariable String workId,
      @PathVariable String scheduleId,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in as the manuscript owner to cancel publication.",
          correlationId);
    }
    UUID parsedWorkId;
    UUID parsedScheduleId;
    try {
      parsedWorkId = UUID.fromString(workId);
      parsedScheduleId = UUID.fromString(scheduleId);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.NOT_FOUND,
          "PUBLICATION_SCHEDULE_NOT_FOUND",
          "The publication schedule could not be found.",
          correlationId);
    }
    try {
      CancelOutcome outcome =
          scheduleService.cancel(principal.userId(), parsedWorkId, parsedScheduleId);
      return switch (outcome) {
        case CANCELLED, ALREADY_CANCELLED -> noStore(HttpStatus.NO_CONTENT).build();
        case TOO_LATE ->
            problem(
                HttpStatus.CONFLICT,
                "PUBLICATION_SCHEDULE_ALREADY_PROCESSING",
                "Publication has started or the schedule is no longer cancellable.",
                correlationId);
        case NOT_FOUND ->
            problem(
                HttpStatus.NOT_FOUND,
                "PUBLICATION_SCHEDULE_NOT_FOUND",
                "The publication schedule could not be found.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_SCHEDULE_UNAVAILABLE",
          "The publication schedule could not be cancelled.",
          correlationId);
    }
  }

  private Optional<Instant> parseScheduledAt(byte[] body) {
    try (JsonParser parser = STRICT_JSON_FACTORY.createParser(body)) {
      JsonNode root = objectMapper.readTree(parser);
      if (root == null || !root.isObject() || parser.nextToken() != null) return Optional.empty();
      Set<String> fields = new HashSet<>();
      fields.addAll(root.propertyNames());
      JsonNode value = root.get("scheduledAt");
      if (!fields.equals(BODY_FIELDS) || value == null || !value.isTextual())
        return Optional.empty();
      Instant scheduledAt = OffsetDateTime.parse(value.textValue()).toInstant();
      return Optional.of(scheduledAt);
    } catch (JacksonException | DateTimeParseException exception) {
      return Optional.empty();
    }
  }

  private static ResponseEntity<?> scheduleResponse(
      PublicationReleaseService.ScheduleResult result, UUID correlationId) {
    return switch (result.outcome()) {
      case CREATED -> noStore(HttpStatus.CREATED).body(scheduleReceipt(result.receipt()));
      case REPLAYED -> noStore(HttpStatus.OK).body(scheduleReceipt(result.receipt()));
      case KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "Use a new key for a different publication schedule.",
              correlationId);
      case TIME_INVALID ->
          problem(
              HttpStatus.BAD_REQUEST,
              "PUBLICATION_SCHEDULE_TIME_INVALID",
              "scheduledAt must be in the future for a new schedule.",
              correlationId);
      case ALREADY_SCHEDULED ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_ALREADY_SCHEDULED",
              "This approved request already has an active publication schedule.",
              correlationId);
      case ALREADY_RELEASED ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_ALREADY_RELEASED",
              "This approved request has already been released.",
              correlationId);
      case WORK_NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "MANUSCRIPT_NOT_FOUND",
              "The private authored manuscript could not be found.",
              correlationId);
      case APPROVAL_REQUIRED ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_APPROVAL_REQUIRED",
              "A current reviewer approval for the required publication scopes is required.",
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
              "Accept the current publication-rights policy before scheduling release.",
              correlationId);
      case RIGHTS_POLICY_STALE ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_RIGHTS_POLICY_STALE",
              "The approved rights policy changed. Refresh readiness and submit a new review request.",
              correlationId);
      case APPROVAL_CHANGED ->
          problem(
              HttpStatus.CONFLICT,
              "PUBLICATION_APPROVAL_CHANGED",
              "The approved rights or content snapshot is no longer current.",
              correlationId);
      case STALE ->
          noStore(HttpStatus.PRECONDITION_FAILED)
              .header(HttpHeaders.ETAG, result.currentEtag())
              .body(
                  problemBody(
                      HttpStatus.PRECONDITION_FAILED,
                      "PUBLICATION_SNAPSHOT_STALE",
                      "Reload publication readiness before scheduling.",
                      correlationId));
    };
  }

  private static ScheduleResponse scheduleReceipt(
      PublicationReleaseService.ScheduleReceipt receipt) {
    return new ScheduleResponse(
        receipt.id(),
        receipt.workId(),
        receipt.requestId(),
        receipt.state(),
        receipt.scheduledAt());
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

  private static ResponseEntity<?> problem(
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

  public record ScheduleResponse(
      UUID scheduleId, UUID workId, UUID requestId, String state, Instant scheduledAt) {}
}
