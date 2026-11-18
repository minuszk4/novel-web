package com.novelweb.identity.privacy;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.auth.PolicyService.RequiredAcceptance;
import com.novelweb.identity.privacy.AccountDataExportRepository.CreateOutcome;
import com.novelweb.identity.privacy.AccountDataExportRepository.ExportJob;
import com.novelweb.identity.privacy.AccountDataExportRepository.ExportStatus;
import com.novelweb.identity.session.SessionCookieFactory;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Actor-scoped account data export requests, state reads and authenticated downloads. */
@RestController
@RequestMapping("/api/v1/me/data-exports")
@Profile("postgres")
public class AccountDataExportController {

  private final AccountDataExportService exportService;
  private final SessionService sessionService;
  private final PolicyService policyService;
  private final boolean enabled;
  private final Duration recentAuthenticationWindow;

  public AccountDataExportController(
      AccountDataExportService exportService,
      SessionService sessionService,
      PolicyService policyService,
      @Value("${novel.account-data-export.enabled:false}") boolean enabled,
      @Value("${novel.account-data-export.recent-authentication-window:10m}")
          Duration recentAuthenticationWindow) {
    this.exportService = exportService;
    this.sessionService = sessionService;
    this.policyService = policyService;
    this.enabled = enabled;
    if (recentAuthenticationWindow.isZero()
        || recentAuthenticationWindow.isNegative()
        || recentAuthenticationWindow.compareTo(Duration.ofDays(90)) > 0) {
      throw new IllegalArgumentException("Account export authentication window is invalid");
    }
    this.recentAuthenticationWindow = recentAuthenticationWindow;
  }

  @PostMapping
  public ResponseEntity<?> create(
      Authentication authentication,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    Optional<UUID> actor = actor(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to request an account export.",
          request);
    }
    if (!enabled || !exportService.isAvailable()) return disabled(request);
    ResponseEntity<?> policyFailure = requireCurrentPolicy(actor.orElseThrow(), request);
    if (policyFailure != null) return policyFailure;
    if (idempotencyKey == null || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IDEMPOTENCY_KEY_REQUIRED",
          "Provide a valid Idempotency-Key to request an export.",
          request);
    }
    try {
      if (!sessionService.wasCreatedWithin(
          sessionCookie(request), actor.orElseThrow(), recentAuthenticationWindow)) {
        return problem(
            HttpStatus.FORBIDDEN,
            "RECENT_AUTHENTICATION_REQUIRED",
            "Sign in again, then request the export from account settings.",
            request);
      }
      var result = exportService.createOrReplay(actor.orElseThrow(), idempotencyKey);
      if (result.outcome() == CreateOutcome.ACTIVE_EXPORT_EXISTS) {
        return problem(
            HttpStatus.CONFLICT,
            "EXPORT_ALREADY_PENDING",
            "An account export is already queued or being prepared.",
            request);
      }
      if (result.outcome() == CreateOutcome.INACTIVE_ACCOUNT) {
        return problem(
            HttpStatus.UNAUTHORIZED,
            "UNAUTHENTICATED",
            "The application session is no longer active.",
            request);
      }
      return ResponseEntity.status(HttpStatus.ACCEPTED)
          .cacheControl(CacheControl.noStore())
          .header(HttpHeaders.PRAGMA, "no-cache")
          .header(
              "Idempotency-Replayed", Boolean.toString(result.outcome() == CreateOutcome.REPLAYED))
          .body(AccountDataExportService.CreateResponse.from(result));
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_EXPORT_REQUEST",
          "Provide a valid Idempotency-Key to request an export.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "ACCOUNT_EXPORT_UNAVAILABLE",
          "The export request could not be processed. Please try again.",
          request);
    }
  }

  @GetMapping("/{exportId}")
  public ResponseEntity<?> status(
      Authentication authentication, @PathVariable UUID exportId, HttpServletRequest request) {
    Optional<UUID> actor = actor(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view an account export.",
          request);
    }
    if (!enabled || !exportService.isAvailable()) return disabled(request);
    try {
      return exportService
          .status(actor.orElseThrow(), exportId)
          .<ResponseEntity<?>>map(
              job ->
                  ResponseEntity.ok()
                      .cacheControl(CacheControl.noStore())
                      .header(HttpHeaders.PRAGMA, "no-cache")
                      .body(AccountDataExportService.JobResponse.from(job)))
          .orElseGet(
              () ->
                  problem(
                      HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "Export was not found.", request));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "ACCOUNT_EXPORT_UNAVAILABLE",
          "Export status could not be loaded. Please try again.",
          request);
    }
  }

  @GetMapping("/latest")
  public ResponseEntity<?> latest(Authentication authentication, HttpServletRequest request) {
    Optional<UUID> actor = actor(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to view account exports.", request);
    }
    if (!enabled || !exportService.isAvailable()) return disabled(request);
    try {
      return exportService
          .latest(actor.orElseThrow())
          .<ResponseEntity<?>>map(
              job ->
                  ResponseEntity.ok()
                      .cacheControl(CacheControl.noStore())
                      .header(HttpHeaders.PRAGMA, "no-cache")
                      .body(AccountDataExportService.JobResponse.from(job)))
          .orElseGet(
              () ->
                  problem(
                      HttpStatus.NOT_FOUND,
                      "EXPORT_NOT_FOUND",
                      "No account export was found.",
                      request));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "ACCOUNT_EXPORT_UNAVAILABLE",
          "Export status could not be loaded. Please try again.",
          request);
    }
  }

  @GetMapping("/{exportId}/download")
  public ResponseEntity<?> download(
      Authentication authentication, @PathVariable UUID exportId, HttpServletRequest request) {
    Optional<UUID> actor = actor(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to download an account export.",
          request);
    }
    if (!enabled || !exportService.isAvailable()) return disabled(request);
    try {
      Optional<ExportJob> found = exportService.status(actor.orElseThrow(), exportId);
      if (found.isEmpty()) {
        return problem(HttpStatus.NOT_FOUND, "EXPORT_NOT_FOUND", "Export was not found.", request);
      }
      ExportJob job = found.orElseThrow();
      if (job.status() == ExportStatus.EXPIRED
          || (job.expiresAt() != null && !job.expiresAt().isAfter(Instant.now()))) {
        return problem(
            HttpStatus.GONE,
            "EXPORT_EXPIRED",
            "This export has expired. Request a new one.",
            request);
      }
      if (job.status() != ExportStatus.READY) {
        return problem(
            HttpStatus.CONFLICT,
            "EXPORT_NOT_READY",
            "The export is not ready to download.",
            request);
      }
      Optional<Path> artifact = exportService.downloadableArtifact(actor.orElseThrow(), exportId);
      if (artifact.isEmpty()) {
        return problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "EXPORT_ARTIFACT_UNAVAILABLE",
            "The export file could not be verified. Please request a new export.",
            request);
      }
      Path path = artifact.orElseThrow();
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .header(HttpHeaders.PRAGMA, "no-cache")
          .header("X-Content-Type-Options", "nosniff")
          .header("Referrer-Policy", "no-referrer")
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              ContentDisposition.attachment()
                  .filename("novel-web-account-data.zip", StandardCharsets.UTF_8)
                  .build()
                  .toString())
          .contentType(MediaType.parseMediaType("application/zip"))
          .contentLength(Files.size(path))
          .body(new FileSystemResource(path));
    } catch (IllegalStateException | IOException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "ACCOUNT_EXPORT_UNAVAILABLE",
          "The export could not be downloaded. Please try again.",
          request);
    }
  }

  private ResponseEntity<?> requireCurrentPolicy(UUID actorId, HttpServletRequest request) {
    try {
      if (policyService.requiredAcceptance(actorId) == RequiredAcceptance.COMPLETE) return null;
      return problem(
          HttpStatus.FORBIDDEN,
          "POLICY_ACCEPTANCE_REQUIRED",
          "Review and accept the current Terms and Privacy Notice before requesting an export.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "POLICY_STORE_UNAVAILABLE",
          "Current account policies could not be checked. Please try again.",
          request);
    }
  }

  private static Optional<UUID> actor(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static String sessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return null;
    String token = null;
    for (Cookie cookie : cookies) {
      if (SessionCookieFactory.COOKIE_NAME.equals(cookie.getName())) {
        if (token != null) return null;
        token = cookie.getValue();
      }
    }
    return token;
  }

  private static ResponseEntity<ProblemDetail> disabled(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "ACCOUNT_EXPORT_DISABLED",
        "Account export is not enabled on this server.",
        request);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId(request).toString());
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }

  private static UUID correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    if (value instanceof String id) {
      try {
        return UUID.fromString(id);
      } catch (IllegalArgumentException ignored) {
        // Use a random fallback if tests or a custom filter omit the normal correlation ID.
      }
    }
    return UUID.randomUUID();
  }
}
