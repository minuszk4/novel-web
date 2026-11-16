package com.novelweb.library.publication;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Withdraws the current pending owner request without changing manuscript content. */
@RestController
@RequestMapping("/api/v1/authoring/manuscripts/{workId}/publication/requests/withdrawal")
@Profile("postgres")
public class PublicationRequestWithdrawalController {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final Pattern SNAPSHOT_ETAG =
      Pattern.compile("\"publication-snapshot-[a-f0-9]{64}\"");

  private final PublicationRequestService publicationService;

  public PublicationRequestWithdrawalController(PublicationRequestService publicationService) {
    this.publicationService = publicationService;
  }

  @PostMapping
  public ResponseEntity<?> withdraw(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to withdraw this publication request.",
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
    if (!SNAPSHOT_ETAG.matcher(ifMatch).matches()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong publication-readiness ETag.",
          correlationId);
    }

    if (request.getContentLengthLong() > 0) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_NOT_ALLOWED",
          "Withdrawing a request does not accept a request body.",
          correlationId);
    }
    try {
      if (request.getInputStream().readNBytes(1).length > 0) {
        return problem(
            HttpStatus.BAD_REQUEST,
            "REQUEST_BODY_NOT_ALLOWED",
            "Withdrawing a request does not accept a request body.",
            correlationId);
      }
    } catch (IOException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "REQUEST_BODY_INVALID",
          "The empty withdrawal request could not be read.",
          correlationId);
    }

    try {
      PublicationRequestService.WithdrawalResult result =
          publicationService.withdraw(principal.userId(), workId, ifMatch, idempotencyKey);
      return response(result, correlationId);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLICATION_REQUEST_WITHDRAWAL_UNAVAILABLE",
          "The request could not be safely withdrawn. Retry with the same Idempotency-Key.",
          correlationId);
    }
  }

  private static ResponseEntity<?> response(
      PublicationRequestService.WithdrawalResult result, UUID correlationId) {
    return switch (result.outcome()) {
      case CREATED, REPLAYED -> noStore(HttpStatus.CREATED).body(result.receipt());
      case WORK_NOT_FOUND ->
          problem(
              HttpStatus.NOT_FOUND,
              "MANUSCRIPT_NOT_FOUND",
              "The private authored manuscript is not available.",
              correlationId);
      case KEY_REUSED ->
          problem(
              HttpStatus.CONFLICT,
              "IDEMPOTENCY_KEY_REUSED",
              "This Idempotency-Key belongs to a different withdrawal attempt. Reload before trying again.",
              correlationId);
      case STALE_SNAPSHOT ->
          noStore(HttpStatus.valueOf(412))
              .header(HttpHeaders.ETAG, result.currentSnapshotEtag())
              .body(
                  baseProblem(
                      HttpStatus.valueOf(412),
                      "PUBLICATION_SNAPSHOT_STALE",
                      "The request changed after you reviewed it. Reload publication readiness.",
                      correlationId));
      case NOT_WITHDRAWABLE -> {
        ProblemDetail body =
            baseProblem(
                HttpStatus.CONFLICT,
                "PUBLICATION_REQUEST_NOT_WITHDRAWABLE",
                "Only a request still awaiting review can be withdrawn.",
                correlationId);
        if (result.currentState() != null) body.setProperty("currentState", result.currentState());
        yield noStore(HttpStatus.CONFLICT).body(body);
      }
    };
  }

  private static String singleHeader(HttpServletRequest request, String name) {
    Enumeration<String> values = request.getHeaders(name);
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
    return noStore(status).body(baseProblem(status, code, detail, correlationId));
  }

  private static ProblemDetail baseProblem(
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
}
