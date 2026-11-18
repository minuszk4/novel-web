package com.novelweb.library;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-scoped workspace and membership operations for the PostgreSQL-backed collaboration model.
 */
@RestController
@RequestMapping("/api/v1")
@Profile("postgres")
public class WorkspaceController {

  private final WorkspaceService workspaceService;
  private final PolicyService policyService;

  public WorkspaceController(WorkspaceService workspaceService, PolicyService policyService) {
    this.workspaceService = workspaceService;
    this.policyService = policyService;
  }

  @GetMapping("/me/workspaces")
  public ResponseEntity<?> listWorkspaces(
      Authentication authentication, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    List<WorkspaceSummary> items =
        workspaceService.listWorkspaces(actor.orElseThrow()).stream()
            .map(WorkspaceController::summary)
            .toList();
    return noStore(HttpStatus.OK).body(new WorkspaceListResponse(items));
  }

  /** Client-chosen IDs make this create operation naturally safe to retry after a lost response. */
  @PutMapping("/me/workspaces/{workspaceId}")
  public ResponseEntity<?> createWorkspace(
      Authentication authentication,
      @PathVariable String workspaceId,
      @RequestBody(required = false) CreateWorkspaceRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    UUID parsedWorkspaceId = parseUuid(workspaceId, "workspaceId");
    if (body == null || body.hasUnknownFields() || body.name() == null) {
      return problem(
          HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE", "Provide only a workspace name.", request);
    }
    WorkspaceRepository.WorkspaceRecord workspace =
        workspaceService.createWorkspace(actor.orElseThrow(), parsedWorkspaceId, body.name());
    return noStore(HttpStatus.OK)
        .header(HttpHeaders.ETAG, workspaceEtag(workspace))
        .body(
            new WorkspaceDetailResponse(
                workspace.id(), workspace.name(), workspace.scopeVersion(), workspace.createdAt()));
  }

  @GetMapping("/workspaces/{workspaceId}")
  public ResponseEntity<?> getWorkspace(
      Authentication authentication, @PathVariable String workspaceId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    UUID parsedWorkspaceId = parseUuid(workspaceId, "workspaceId");
    WorkspaceRepository.WorkspaceRecord workspace =
        workspaceService
            .findWorkspace(actor.orElseThrow(), parsedWorkspaceId)
            .orElseThrow(WorkspaceRepository.WorkspaceNotFoundException::new);
    return noStore(HttpStatus.OK)
        .header(HttpHeaders.ETAG, workspaceEtag(workspace))
        .body(
            new WorkspaceDetailResponse(
                workspace.id(), workspace.name(), workspace.scopeVersion(), workspace.createdAt()));
  }

  @GetMapping("/workspaces/{workspaceId}/members")
  public ResponseEntity<?> listMembers(
      Authentication authentication, @PathVariable String workspaceId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    UUID parsedWorkspaceId = parseUuid(workspaceId, "workspaceId");
    WorkspaceRepository.WorkspaceRecord workspace =
        workspaceService
            .findWorkspace(actor.orElseThrow(), parsedWorkspaceId)
            .orElseThrow(WorkspaceRepository.WorkspaceNotFoundException::new);
    List<MemberSummary> members =
        workspaceService.listMembers(actor.orElseThrow(), parsedWorkspaceId).stream()
            .map(WorkspaceController::summary)
            .toList();
    return noStore(HttpStatus.OK)
        .header(HttpHeaders.ETAG, workspaceEtag(workspace))
        .body(new MemberListResponse(members));
  }

  @GetMapping("/workspaces/{workspaceId}/membership-audit")
  public ResponseEntity<?> listMembershipAudit(
      Authentication authentication,
      @PathVariable String workspaceId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") int limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    UUID parsedWorkspaceId = parseUuid(workspaceId, "workspaceId");
    Long beforeEventId = parseAuditCursor(cursor);
    WorkspaceMembershipAuditRepository.Page page =
        workspaceService.listMembershipAudit(
            actor.orElseThrow(), parsedWorkspaceId, beforeEventId, limit);
    return noStore(HttpStatus.OK)
        .body(
            new MembershipAuditResponse(
                page.items().stream().map(WorkspaceController::summary).toList(),
                page.nextCursor()));
  }

  /**
   * PUT supports both first-time addition and setting a changed role; an exact retry is a no-op.
   */
  @PutMapping("/workspaces/{workspaceId}/members/{memberUserId}")
  public ResponseEntity<?> putMember(
      Authentication authentication,
      @PathVariable String workspaceId,
      @PathVariable String memberUserId,
      @RequestBody(required = false) SetMemberRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (body == null || body.hasUnknownFields() || body.role() == null) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE_MEMBER", request);
    }
    UUID parsedWorkspaceId = parseUuid(workspaceId, "workspaceId");
    UUID parsedMemberId = parseUuid(memberUserId, "memberUserId");
    WorkspaceRepository.MemberRecord member =
        workspaceService.putMember(
            actor.orElseThrow(), parsedWorkspaceId, parsedMemberId, parseRole(body.role()));
    return noStore(HttpStatus.OK).body(summary(member));
  }

  @DeleteMapping("/workspaces/{workspaceId}/members/{memberUserId}")
  public ResponseEntity<?> revokeMember(
      Authentication authentication,
      @PathVariable String workspaceId,
      @PathVariable String memberUserId,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    UUID parsedWorkspaceId = parseUuid(workspaceId, "workspaceId");
    UUID parsedMemberId = parseUuid(memberUserId, "memberUserId");
    return noStore(HttpStatus.OK)
        .body(
            summary(
                workspaceService.revokeMember(
                    actor.orElseThrow(), parsedWorkspaceId, parsedMemberId)));
  }

  @PutMapping("/workspaces/{workspaceId}/owner")
  public ResponseEntity<?> transferOwnership(
      Authentication authentication,
      @PathVariable String workspaceId,
      @RequestBody(required = false) TransferOwnershipRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (body == null || body.hasUnknownFields() || body.newOwnerUserId() == null) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE_OWNER", request);
    }
    UUID parsedWorkspaceId = parseUuid(workspaceId, "workspaceId");
    UUID parsedNewOwnerId = parseUuid(body.newOwnerUserId(), "newOwnerUserId");
    WorkspaceRepository.WorkspaceRecord workspace =
        workspaceService.transferOwnership(
            actor.orElseThrow(), parsedWorkspaceId, parsedNewOwnerId);
    return noStore(HttpStatus.OK)
        .header(HttpHeaders.ETAG, workspaceEtag(workspace))
        .body(
            new WorkspaceDetailResponse(
                workspace.id(), workspace.name(), workspace.scopeVersion(), workspace.createdAt()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE_REQUEST", exception.getMessage(), request);
  }

  @ExceptionHandler({
    WorkspaceRepository.WorkspaceNotFoundException.class,
    WorkspaceRepository.WorkspaceAccessDeniedException.class,
    WorkspaceRepository.WorkspaceMemberNotFoundException.class
  })
  public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "WORKSPACE_NOT_FOUND", request);
  }

  @ExceptionHandler(WorkspaceRepository.WorkspaceMemberConflictException.class)
  public ResponseEntity<ProblemDetail> conflict(HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "WORKSPACE_MEMBER_CONFLICT", request);
  }

  @ExceptionHandler({
    WorkspaceRepository.WorkspaceOwnerCannotBeRevokedException.class,
    WorkspaceRepository.OwnershipTransferRequiredException.class
  })
  public ResponseEntity<ProblemDetail> ownershipRequired(HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "WORKSPACE_OWNER_PROTECTED", request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "WORKSPACE_STORE_UNAVAILABLE",
        "Workspace data could not be loaded or saved. Please retry.",
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
                "Review and accept the current Terms and Privacy Notice first.",
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
          "Current policy state could not be checked. Please retry.",
          request);
    }
  }

  private static WorkspaceSummary summary(
      WorkspaceRepository.WorkspaceMembershipRecord membership) {
    WorkspaceRepository.WorkspaceRecord workspace = membership.workspace();
    return new WorkspaceSummary(
        workspace.id(),
        workspace.name(),
        membership.role().value(),
        workspace.scopeVersion(),
        workspace.createdAt(),
        membership.joinedAt());
  }

  private static MemberSummary summary(WorkspaceRepository.MemberRecord member) {
    return new MemberSummary(
        member.userId(),
        member.role().value(),
        member.status().name().toLowerCase(Locale.ROOT),
        member.joinedAt(),
        member.revokedAt());
  }

  private static MembershipAuditSummary summary(WorkspaceMembershipAuditRepository.Event event) {
    return new MembershipAuditSummary(
        Long.toString(event.eventId()),
        event.workspaceId(),
        event.actorUserId(),
        event.subjectUserId(),
        event.action().name(),
        event.reason().name(),
        event.previousRole() == null ? null : event.previousRole().value(),
        event.newRole() == null ? null : event.newRole().value(),
        event.previousStatus() == null
            ? null
            : event.previousStatus().name().toLowerCase(Locale.ROOT),
        event.newStatus() == null ? null : event.newStatus().name().toLowerCase(Locale.ROOT),
        event.occurredAt());
  }

  private static WorkspaceRepository.Role parseRole(String value) {
    try {
      WorkspaceRepository.Role role =
          WorkspaceRepository.Role.valueOf(value.toUpperCase(Locale.ROOT));
      if (role == WorkspaceRepository.Role.OWNER)
        throw new IllegalArgumentException("Owner role requires ownership transfer.");
      return role;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("role must be admin, editor, or reader");
    }
  }

  private static Long parseAuditCursor(String value) {
    if (value == null) return null;
    if (!value.matches("[1-9][0-9]{0,18}")) {
      throw new IllegalArgumentException("cursor must be a positive event id");
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("cursor must be a positive event id");
    }
  }

  private static UUID parseUuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a valid UUID");
    }
  }

  private static Optional<UUID> actorUserId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static String workspaceEtag(WorkspaceRepository.WorkspaceRecord workspace) {
    return "\"workspace-" + workspace.id() + "-" + workspace.scopeVersion() + "\"";
  }

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, HttpServletRequest request) {
    return problem(status, code, status.getReasonPhrase(), request);
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

  public record WorkspaceListResponse(List<WorkspaceSummary> items) {}

  public record WorkspaceSummary(
      UUID id, String name, String role, long scopeVersion, Instant createdAt, Instant joinedAt) {}

  public record WorkspaceDetailResponse(
      UUID id, String name, long scopeVersion, Instant createdAt) {}

  public record MemberListResponse(List<MemberSummary> items) {}

  public record MemberSummary(
      UUID userId, String role, String status, Instant joinedAt, Instant revokedAt) {}

  public record MembershipAuditResponse(List<MembershipAuditSummary> items, String nextCursor) {}

  public record MembershipAuditSummary(
      String eventId,
      UUID workspaceId,
      UUID actorUserId,
      UUID subjectUserId,
      String action,
      String reason,
      String previousRole,
      String newRole,
      String previousStatus,
      String newStatus,
      Instant occurredAt) {}

  public static final class CreateWorkspaceRequest {
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

  public static final class SetMemberRequest {
    private String role;
    private boolean unknownFields;

    public String role() {
      return role;
    }

    public void setRole(String role) {
      this.role = role;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignored) {
      unknownFields = true;
    }
  }

  public static final class TransferOwnershipRequest {
    private String newOwnerUserId;
    private boolean unknownFields;

    public String newOwnerUserId() {
      return newOwnerUserId;
    }

    public void setNewOwnerUserId(String newOwnerUserId) {
      this.newOwnerUserId = newOwnerUserId;
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
