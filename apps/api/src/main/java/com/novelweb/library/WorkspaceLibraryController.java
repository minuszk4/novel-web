package com.novelweb.library;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Session-scoped creation and listing of a workspace's shared libraries. */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/libraries")
@Profile("postgres")
public class WorkspaceLibraryController {

  private final LibraryService libraryService;
  private final WorkspaceService workspaceService;
  private final PolicyService policyService;

  public WorkspaceLibraryController(
      LibraryService libraryService,
      WorkspaceService workspaceService,
      PolicyService policyService) {
    this.libraryService = libraryService;
    this.workspaceService = workspaceService;
    this.policyService = policyService;
  }

  @GetMapping
  public ResponseEntity<?> listLibraries(
      Authentication authentication,
      @PathVariable String workspaceId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") String limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    UUID parsedWorkspaceId = parseUuid(workspaceId, "workspaceId");
    LibraryService.WorkspaceLibraryPage<LibraryRepository.LibraryRecord> page =
        libraryService.listWorkspaceLibraries(
            actor.orElseThrow(), parsedWorkspaceId, cursor, parseLimit(limit));
    return noStore(HttpStatus.OK)
        .header(HttpHeaders.ETAG, workspaceEtag(parsedWorkspaceId, page.scopeVersion()))
        .body(
            new WorkspaceLibraryListResponse(
                page.items().stream().map(WorkspaceLibraryController::summary).toList(),
                page.nextCursor()));
  }

  /** Client-selected IDs make lost-response retries return the original empty library. */
  @PutMapping("/{libraryId}")
  public ResponseEntity<?> createLibrary(
      Authentication authentication,
      @PathVariable String workspaceId,
      @PathVariable String libraryId,
      @RequestBody(required = false) CreateLibraryRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    UUID parsedWorkspaceId = parseUuid(workspaceId, "workspaceId");
    UUID parsedLibraryId = parseUuid(libraryId, "libraryId");
    if (body == null || body.hasUnknownFields() || body.name() == null) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_WORKSPACE_LIBRARY",
          "Provide only a workspace library name.",
          request);
    }

    LibraryRepository.LibraryRecord library =
        libraryService.createWorkspaceLibrary(
            actor.orElseThrow(), parsedWorkspaceId, parsedLibraryId, body.name());
    WorkspaceRepository.WorkspaceRecord workspace =
        workspaceService
            .findWorkspace(actor.orElseThrow(), parsedWorkspaceId)
            .orElseThrow(WorkspaceRepository.WorkspaceNotFoundException::new);
    return noStore(HttpStatus.OK)
        .header(HttpHeaders.ETAG, workspaceEtag(parsedWorkspaceId, workspace.scopeVersion()))
        .body(summary(library));
  }

  @ExceptionHandler(LibraryRepository.LibraryNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "WORKSPACE_NOT_FOUND",
        "The workspace library is not available.",
        request);
  }

  @ExceptionHandler(WorkspaceRepository.WorkspaceNotFoundException.class)
  public ResponseEntity<ProblemDetail> workspaceNotFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "WORKSPACE_NOT_FOUND",
        "The workspace library is not available.",
        request);
  }

  @ExceptionHandler(LibraryRepository.LibraryNameConflictException.class)
  public ResponseEntity<ProblemDetail> nameConflict(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "LIBRARY_NAME_CONFLICT",
        "A library with this name already exists in the workspace, or this request ID was used with different details.",
        request);
  }

  @ExceptionHandler(LibraryCursorCodec.InvalidCursorException.class)
  public ResponseEntity<ProblemDetail> invalidCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_CURSOR",
        "The cursor is malformed, belongs to another workspace, or expired after a scope change.",
        request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE_LIBRARY", exception.getMessage(), request);
  }

  @ExceptionHandler(LibraryCursorCodec.SigningUnavailableException.class)
  public ResponseEntity<ProblemDetail> signingUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "WORKSPACE_LIBRARY_CURSOR_UNAVAILABLE",
        "Workspace libraries are temporarily unavailable.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "WORKSPACE_LIBRARY_UNAVAILABLE",
        "Workspace libraries could not be loaded. Please try again.",
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
                "Review and accept the current Terms and Privacy Notice before using this workspace.",
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

  private static Optional<UUID> actorUserId(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static int parseLimit(String rawLimit) {
    try {
      return Integer.parseInt(rawLimit);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("limit must be an integer between 1 and 100");
    }
  }

  private static UUID parseUuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a valid UUID");
    }
  }

  private static WorkspaceLibrarySummary summary(LibraryRepository.LibraryRecord library) {
    return new WorkspaceLibrarySummary(
        library.id(),
        library.name(),
        library.visibility(),
        library.isDefault(),
        library.createdAt());
  }

  private static String workspaceEtag(UUID workspaceId, long scopeVersion) {
    return "\"workspace-" + workspaceId + "-" + scopeVersion + "\"";
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, HttpServletRequest request) {
    return problem(status, code, code, request);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(status.getReasonPhrase());
    body.setProperty("code", code);
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    body.setProperty("correlationId", value instanceof String id ? id : "unavailable");
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  public record WorkspaceLibraryListResponse(
      List<WorkspaceLibrarySummary> items, String nextCursor) {}

  public record WorkspaceLibrarySummary(
      UUID id, String name, String visibility, boolean isDefault, Instant createdAt) {}

  public static final class CreateLibraryRequest {
    private String name;
    private boolean unknownFields;

    public String name() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignored) {
      unknownFields = true;
    }
  }
}
