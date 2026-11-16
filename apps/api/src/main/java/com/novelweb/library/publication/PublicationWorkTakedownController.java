package com.novelweb.library.publication;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Revokes public scope access and reports external purge delivery separately. */
@RestController
@RequestMapping("/api/v1/review/works")
@Profile("postgres")
public class PublicationWorkTakedownController {

  private static final int MAX_BODY_BYTES = 2_048;
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final Set<String> BODY_FIELDS = Set.of("reasonCode");
  private static final Set<String> REASON_CODES =
      Set.of("RIGHTS_WITHDRAWN", "REVIEW_INVALIDATED", "POLICY_CHANGED", "EMERGENCY_TAKEDOWN");

  private final PublicationWorkTakedownService takedownService;
  private final PublicationTakedownPurgeService purgeService;
  private final ObjectMapper objectMapper;

  public PublicationWorkTakedownController(
      PublicationWorkTakedownService takedownService,
      PublicationTakedownPurgeService purgeService,
      ObjectMapper objectMapper) {
    this.takedownService = takedownService;
    this.purgeService = purgeService;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/{workId}/takedown-purge-status")
  public ResponseEntity<?> purgeStatus(
      Authentication authentication, @PathVariable String workId, HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in with an assigned reviewer or system administrator account.",
          correlationId);
    }
    final UUID parsedWorkId;
    try {
      parsedWorkId = UUID.fromString(workId);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_WORK_ID_INVALID",
          "workId must be a UUID.",
          correlationId);
    }
    try {
      PublicationTakedownPurgeService.StatusResult result =
          purgeService.status(principal.userId(), parsedWorkId);
      return switch (result.outcome()) {
        case AVAILABLE -> noStore(HttpStatus.OK).body(result.status());
        case REVIEWER_REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "PUBLICATION_TAKEDOWN_ROLE_REQUIRED",
                "An active publication reviewer or system administrator role is required.",
                correlationId);
        case POLICY_REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Accept the current Terms and acknowledge the Privacy Notice first.",
                correlationId);
        case POLICY_NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_NOT_CONFIGURED",
                "Current required policy documents are not available.",
                correlationId);
        case NOT_FOUND ->
            problem(
                HttpStatus.NOT_FOUND,
                "PUBLICATION_WORK_NOT_FOUND",
                "The publication work does not have an available takedown status.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_TAKEDOWN_STATUS_UNAVAILABLE",
          "The publication purge status could not be loaded.",
          correlationId);
    }
  }

  @PostMapping("/{workId}/takedown")
  public ResponseEntity<?> takeDown(
      Authentication authentication, @PathVariable String workId, HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in with an assigned reviewer or system administrator account.",
          correlationId);
    }
    if (!isJson(request.getContentType())) {
      return problem(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "UNSUPPORTED_MEDIA_TYPE",
          "Send the publication takedown reason as JSON.",
          correlationId);
    }

    final UUID parsedWorkId;
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
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "REQUEST_TOO_LARGE",
          "The takedown request may not exceed 2 KiB.",
          correlationId);
    }
    final byte[] body;
    try {
      body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    } catch (IOException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_INVALID",
          "The takedown request body could not be read.",
          correlationId);
    }
    if (body.length == 0) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_REQUIRED",
          "Provide a supported reasonCode.",
          correlationId);
    }
    if (body.length > MAX_BODY_BYTES) {
      return problem(
          HttpStatus.valueOf(413),
          "REQUEST_TOO_LARGE",
          "The takedown request may not exceed 2 KiB.",
          correlationId);
    }
    String reasonCode = parseReasonCode(body);
    if (reasonCode == null) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PUBLICATION_TAKEDOWN_INVALID",
          "Provide a supported reasonCode without additional fields.",
          correlationId);
    }

    try {
      return response(
          takedownService.takeDown(principal.userId(), parsedWorkId, idempotencyKey, reasonCode),
          correlationId);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_TAKEDOWN_UNAVAILABLE",
          "Access was not confirmed as revoked. Retry with the same Idempotency-Key.",
          correlationId);
    }
  }

  private String parseReasonCode(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      if (root == null || !root.isObject()) return null;
      Set<String> fields = new HashSet<>(root.propertyNames());
      if (!BODY_FIELDS.equals(fields)) return null;
      JsonNode reason = root.get("reasonCode");
      if (reason == null || !reason.isTextual()) return null;
      String value = reason.textValue();
      return REASON_CODES.contains(value) ? value : null;
    } catch (JacksonException exception) {
      return null;
    }
  }

  private static ResponseEntity<?> response(
      PublicationWorkTakedownService.TakedownResult result, UUID correlationId) {
    return switch (result.outcome()) {
      case CREATED -> noStore(HttpStatus.OK).body(result.receipt());
      case REPLAYED ->
          noStore(HttpStatus.OK).header("Idempotency-Replayed", "true").body(result.receipt());
      case REVIEWER_REQUIRED ->
          problem(
              HttpStatus.FORBIDDEN,
              "PUBLICATION_TAKEDOWN_ROLE_REQUIRED",
              "An active publication reviewer or system administrator role is required.",
              correlationId);
      case POLICY_REQUIRED ->
          problem(
              HttpStatus.FORBIDDEN,
              "POLICY_ACCEPTANCE_REQUIRED",
              "Accept the current Terms and acknowledge the Privacy Notice first.",
              correlationId);
      case POLICY_NOT_CONFIGURED ->
          problem(
              HttpStatus.SERVICE_UNAVAILABLE,
              "POLICY_NOT_CONFIGURED",
              "Current required policy documents are not available.",
              correlationId);
      case NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "PUBLICATION_WORK_NOT_FOUND",
              "The publication work is not available.",
              correlationId);
      case KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "This Idempotency-Key was already used for another takedown command.",
              correlationId);
    };
  }

  private static boolean isJson(String contentType) {
    if (contentType == null) return false;
    try {
      return MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.APPLICATION_JSON);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static String singleHeader(HttpServletRequest request, String name) {
    var values = request.getHeaders(name);
    if (values == null || !values.hasMoreElements()) return null;
    String value = values.nextElement();
    return values.hasMoreElements() ? null : value;
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(status.getReasonPhrase());
    body.setProperty("code", code);
    body.setProperty("correlationId", correlationId.toString());
    return noStore(status).body(body);
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
        // Generate a fallback correlation ID if normal request filters are not present.
      }
    }
    return UUID.randomUUID();
  }
}
