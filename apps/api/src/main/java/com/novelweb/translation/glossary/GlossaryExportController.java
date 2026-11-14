package com.novelweb.translation.glossary;

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
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exports one immutable active or latest glossary snapshot without mutating its state. */
@RestController
@RequestMapping("/api/v1/glossaries/{glossaryId}/export")
@Profile("postgres")
public class GlossaryExportController {

  private final GlossaryRepository readRepository;
  private final GlossaryExportService exportService;
  private final PolicyService policyService;

  public GlossaryExportController(
      GlossaryRepository readRepository,
      GlossaryExportService exportService,
      PolicyService policyService) {
    this.readRepository = readRepository;
    this.exportService = exportService;
    this.policyService = policyService;
  }

  @GetMapping
  public ResponseEntity<?> export(
      Authentication authentication,
      @PathVariable UUID glossaryId,
      @RequestParam(defaultValue = "json") String format,
      @RequestParam(defaultValue = "active") String revision,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to export this glossary.", request);
    }
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;

    GlossaryExportService.Format exportFormat;
    if (format.equals("json") || format.equals("csv") || format.equals("tsv")) {
      exportFormat = GlossaryExportService.Format.parse(format);
    } else {
      return problem(
          HttpStatus.BAD_REQUEST,
          "GLOSSARY_EXPORT_FORMAT_INVALID",
          "Choose json, csv or tsv for the glossary export.",
          request);
    }
    if (!revision.equals("active") && !revision.equals("latest")) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "GLOSSARY_EXPORT_REVISION_INVALID",
          "Choose active or latest for the glossary revision.",
          request);
    }

    try {
      UUID actorId = actor.orElseThrow();
      ExportSelection selection = select(actorId, glossaryId, revision);
      if (selection == null || selection.terms().isEmpty()) {
        return problem(
            HttpStatus.NOT_FOUND,
            "NOT_FOUND",
            "The glossary or requested non-empty revision is unavailable.",
            request);
      }
      byte[] content = exportService.encode(selection.terms(), exportFormat);
      return ResponseEntity.ok()
          .contentType(MediaType.parseMediaType(exportFormat.contentType()))
          .header(
              HttpHeaders.CONTENT_DISPOSITION,
              "attachment; filename=\""
                  + safeBaseName(selection.name())
                  + "-r"
                  + selection.revisionNumber()
                  + "."
                  + exportFormat.extension()
                  + "\"")
          .header(HttpHeaders.ETAG, etag(selection.version()))
          .header("X-Content-Type-Options", "nosniff")
          .cacheControl(CacheControl.noStore())
          .header(HttpHeaders.PRAGMA, "no-cache")
          .body(content);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "GLOSSARY_EXPORT_UNAVAILABLE",
          "The glossary export could not be prepared. Please try again.",
          request);
    }
  }

  private ExportSelection select(UUID actorId, UUID glossaryId, String revision) {
    if (revision.equals("active")) {
      Optional<GlossaryRepository.GlossarySnapshot> result =
          readRepository.loadActiveRevision(actorId, glossaryId);
      return result
          .map(
              snapshot ->
                  new ExportSelection(
                      snapshot.name(),
                      snapshot.version(),
                      snapshot.revisionNumber(),
                      snapshot.terms()))
          .orElse(null);
    }
    Optional<GlossaryRepository.WritableRevisionSnapshot> result =
        readRepository.loadLatestRevisionForWriter(actorId, glossaryId);
    return result
        .map(
            snapshot ->
                new ExportSelection(
                    snapshot.name(),
                    snapshot.version(),
                    snapshot.revisionNumber(),
                    snapshot.terms()))
        .orElse(null);
  }

  private ResponseEntity<?> policyGate(UUID actorUserId, HttpServletRequest request) {
    try {
      return switch (policyService.requiredAcceptance(actorUserId)) {
        case COMPLETE -> null;
        case REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Review and accept the current Terms and Privacy Notice before exporting a glossary.",
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

  private static String safeBaseName(String name) {
    String safe = name.replaceAll("[^A-Za-z0-9_-]+", "_");
    safe = safe.replaceAll("^_+|_+$", "");
    if (safe.isBlank()) safe = "glossary";
    return safe.substring(0, Math.min(safe.length(), 64));
  }

  private static Optional<UUID> actorUserId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static String etag(long version) {
    return "\"v" + version + "\"";
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", correlationId instanceof String id ? id : "unavailable");
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }

  private record ExportSelection(
      String name,
      long version,
      int revisionNumber,
      java.util.List<GlossaryRepository.TermRecord> terms) {}
}
