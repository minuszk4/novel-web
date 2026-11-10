package com.novelweb.library.imports;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/imports/rights-statement")
@Profile("postgres")
public class ImportRightsController {

  private final ImportRightsService importRightsService;

  public ImportRightsController(ImportRightsService importRightsService) {
    this.importRightsService = importRightsService;
  }

  @GetMapping("/current")
  public ResponseEntity<?> current(HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    try {
      return importRightsService
          .currentStatement()
          .<ResponseEntity<?>>map(
              statement ->
                  noStore(HttpStatus.OK)
                      .body(
                          new StatementResponse(
                              statement.version(),
                              statement.effectiveAt(),
                              statement.displayText())))
          .orElseGet(
              () ->
                  problem(
                      HttpStatus.SERVICE_UNAVAILABLE,
                      "IMPORT_RIGHTS_STATEMENT_NOT_CONFIGURED",
                      "An owner-approved import-rights statement is not available.",
                      correlationId));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "IMPORT_RIGHTS_STATEMENT_STORE_UNAVAILABLE",
          "The current import-rights statement could not be loaded.",
          correlationId);
    }
  }

  @PostMapping("/acceptance")
  public ResponseEntity<?> accept(
      Authentication authentication,
      @RequestBody(required = false) ImportRightsAcceptanceRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String rawIdempotencyKey,
      HttpServletRequest request) {
    UUID correlationId = correlationId(request);
    SessionService.SessionPrincipal principal = principal(authentication);
    if (principal == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in before recording an import-rights confirmation.",
          correlationId);
    }
    if (body == null
        || !ImportRightsService.isValidVersion(body.statementVersion())
        || !Boolean.TRUE.equals(body.confirmed())) {
      return problem(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "IMPORT_RIGHTS_CONFIRMATION_REQUIRED",
          "Review the current statement and explicitly confirm permission for private import.",
          correlationId);
    }
    UUID idempotencyKey = parseIdempotencyKey(rawIdempotencyKey);
    if (idempotencyKey == null) {
      return problem(
          HttpStatus.UNPROCESSABLE_CONTENT,
          "IDEMPOTENCY_KEY_REQUIRED",
          "Provide a canonical UUID Idempotency-Key for this confirmation attempt.",
          correlationId);
    }

    try {
      ImportRightsService.AcceptanceResult result =
          importRightsService.accept(principal.userId(), body.statementVersion(), idempotencyKey);
      return switch (result.outcome()) {
        case ACCEPTED -> noStore(HttpStatus.OK).body(result.acceptance());
        case STALE -> stale(result.currentStatement().version(), correlationId);
        case POLICY_REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Accept the current Terms and acknowledge the current Privacy Notice first.",
                correlationId);
        case POLICY_NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_NOT_CONFIGURED",
                "Current approved policy documents are not available.",
                correlationId);
        case KEY_REUSED ->
            problem(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "Use a new Idempotency-Key for a different statement confirmation.",
                correlationId);
        case NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "IMPORT_RIGHTS_STATEMENT_NOT_CONFIGURED",
                "An owner-approved import-rights statement is not available.",
                correlationId);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "IMPORT_RIGHTS_STATEMENT_STORE_UNAVAILABLE",
          "The import-rights confirmation could not be recorded.",
          correlationId);
    }
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> stale(String currentVersion, UUID correlationId) {
    ProblemDetail problem =
        baseProblem(
            HttpStatus.CONFLICT,
            "Review the current statement before confirming it.",
            "IMPORT_RIGHTS_STATEMENT_STALE",
            correlationId);
    problem.setProperty("currentStatementVersion", currentVersion);
    return noStore(HttpStatus.CONFLICT).body(problem);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    ProblemDetail problem = baseProblem(status, detail, code, correlationId);
    return noStore(status).body(problem);
  }

  private static ProblemDetail baseProblem(
      HttpStatus status, String detail, String code, UUID correlationId) {
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

  private static UUID parseIdempotencyKey(String value) {
    if (value == null
        || !value.matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
      return null;
    }
    return UUID.fromString(value);
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

  public record StatementResponse(
      String version, java.time.Instant effectiveAt, String displayText) {}

  public record ImportRightsAcceptanceRequest(String statementVersion, Boolean confirmed) {}
}
