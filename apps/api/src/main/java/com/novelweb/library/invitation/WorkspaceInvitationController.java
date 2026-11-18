package com.novelweb.library.invitation;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Email invitation API. Tokens are accepted only in POST bodies, never query strings. */
@RestController
@RequestMapping("/api/v1")
@Profile("postgres")
public class WorkspaceInvitationController {

  private final WorkspaceInvitationService service;
  private final PolicyService policyService;

  public WorkspaceInvitationController(
      WorkspaceInvitationService service, PolicyService policyService) {
    this.service = service;
    this.policyService = policyService;
  }

  @GetMapping("/workspaces/{workspaceId}/invitations")
  public ResponseEntity<?> list(
      Authentication authentication, @PathVariable String workspaceId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    List<InvitationItem> items =
        service.list(actor.orElseThrow(), parseUuid(workspaceId, "workspaceId")).stream()
            .map(
                invitation ->
                    new InvitationItem(
                        invitation.id(),
                        invitation.role(),
                        invitation.status(),
                        invitation.createdAt(),
                        invitation.expiresAt()))
            .toList();
    return noStore(HttpStatus.OK).body(new InvitationList(items));
  }

  /** Client-selected IDs let a retry re-use the same invitation and outbox row. */
  @PutMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
  public ResponseEntity<?> create(
      Authentication authentication,
      @PathVariable String workspaceId,
      @PathVariable String invitationId,
      @RequestBody(required = false) CreateInvitationRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (body == null
        || body.hasUnknownFields()
        || body.email() == null
        || body.role() == null
        || !body.confirmedPermissionToEmail()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_WORKSPACE_INVITATION",
          "Provide an email, role, and confirmation that you may send this invitation.",
          request);
    }
    WorkspaceInvitationRepository.InvitationRecord invitation =
        service.create(
            actor.orElseThrow(),
            parseUuid(workspaceId, "workspaceId"),
            parseUuid(invitationId, "invitationId"),
            body.email(),
            body.role(),
            body.confirmedPermissionToEmail());
    return noStore(HttpStatus.ACCEPTED)
        .body(
            new InvitationQueued(
                invitation.id(),
                invitation.status(),
                invitation.expiresAt(),
                "If this request is eligible, the invitation email will be sent shortly."));
  }

  @DeleteMapping("/workspaces/{workspaceId}/invitations/{invitationId}")
  public ResponseEntity<?> revoke(
      Authentication authentication,
      @PathVariable String workspaceId,
      @PathVariable String invitationId,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    service.revoke(
        actor.orElseThrow(),
        parseUuid(workspaceId, "workspaceId"),
        parseUuid(invitationId, "invitationId"));
    return noStore(HttpStatus.NO_CONTENT).build();
  }

  @PostMapping("/me/workspace-invitations/{invitationId}/resolve")
  public ResponseEntity<?> resolve(
      Authentication authentication,
      @PathVariable String invitationId,
      @RequestBody(required = false) TokenRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (body == null || body.hasUnknownFields() || body.token() == null) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_INVITATION_TOKEN", request);
    }
    WorkspaceInvitationRepository.RecipientInvitation invitation =
        service.resolve(actor.orElseThrow(), parseUuid(invitationId, "invitationId"), body.token());
    return noStore(HttpStatus.OK)
        .body(
            new RecipientInvitation(
                invitation.id(),
                invitation.workspaceId(),
                invitation.workspaceName(),
                invitation.role(),
                invitation.expiresAt()));
  }

  @PostMapping("/me/workspace-invitations/{invitationId}/accept")
  public ResponseEntity<?> accept(
      Authentication authentication,
      @PathVariable String invitationId,
      @RequestBody(required = false) TokenRequest body,
      HttpServletRequest request) {
    return decide(authentication, invitationId, body, true, request);
  }

  @PostMapping("/me/workspace-invitations/{invitationId}/reject")
  public ResponseEntity<?> reject(
      Authentication authentication,
      @PathVariable String invitationId,
      @RequestBody(required = false) TokenRequest body,
      HttpServletRequest request) {
    return decide(authentication, invitationId, body, false, request);
  }

  private ResponseEntity<?> decide(
      Authentication authentication,
      String invitationId,
      TokenRequest body,
      boolean accept,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (body == null || body.hasUnknownFields() || body.token() == null) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_INVITATION_TOKEN", request);
    }
    UUID parsedInvitationId = parseUuid(invitationId, "invitationId");
    service.decide(actor.orElseThrow(), parsedInvitationId, body.token(), accept);
    return noStore(HttpStatus.OK)
        .body(new InvitationDecision(parsedInvitationId, accept ? "accepted" : "rejected"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalid(HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_WORKSPACE_INVITATION", request);
  }

  @ExceptionHandler({
    WorkspaceInvitationRepository.InvitationNotFoundException.class,
    WorkspaceInvitationService.InvitationNotFoundException.class
  })
  public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "WORKSPACE_INVITATION_NOT_FOUND", request);
  }

  @ExceptionHandler(WorkspaceInvitationRepository.InvitationConflictException.class)
  public ResponseEntity<ProblemDetail> conflict(HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "WORKSPACE_INVITATION_CONFLICT", request);
  }

  @ExceptionHandler(WorkspaceInvitationRepository.InvitationRateLimitException.class)
  public ResponseEntity<ProblemDetail> rateLimited(HttpServletRequest request) {
    return problem(HttpStatus.TOO_MANY_REQUESTS, "WORKSPACE_INVITATION_RATE_LIMITED", request);
  }

  @ExceptionHandler(WorkspaceInvitationService.InvitationUnavailableException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "WORKSPACE_INVITATIONS_UNAVAILABLE",
        "Email invitations are disabled or not configured.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> storageUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "WORKSPACE_INVITATIONS_UNAVAILABLE",
        "Workspace invitation data could not be loaded or saved. Please retry.",
        request);
  }

  private ResponseEntity<?> policyGate(UUID actorId, HttpServletRequest request) {
    try {
      return switch (policyService.requiredAcceptance(actorId)) {
        case COMPLETE -> null;
        case REQUIRED -> problem(HttpStatus.FORBIDDEN, "POLICY_ACCEPTANCE_REQUIRED", request);
        case NOT_CONFIGURED ->
            problem(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_NOT_CONFIGURED", request);
      };
    } catch (IllegalStateException exception) {
      return problem(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_STORE_UNAVAILABLE", request);
    }
  }

  private static Optional<UUID> actorUserId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static UUID parseUuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a valid UUID");
    }
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

  public record InvitationList(List<InvitationItem> items) {}

  public record InvitationItem(
      UUID id, String role, String status, Instant createdAt, Instant expiresAt) {}

  public record InvitationQueued(UUID id, String status, Instant expiresAt, String message) {}

  public record RecipientInvitation(
      UUID id, UUID workspaceId, String workspaceName, String role, Instant expiresAt) {}

  public record InvitationDecision(UUID id, String status) {}

  public static final class CreateInvitationRequest {
    private String email;
    private String role;
    private boolean confirmedPermissionToEmail;
    private boolean unknownFields;

    public String email() {
      return email;
    }

    public String role() {
      return role;
    }

    public boolean confirmedPermissionToEmail() {
      return confirmedPermissionToEmail;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public void setRole(String role) {
      this.role = role;
    }

    public void setConfirmedPermissionToEmail(boolean value) {
      this.confirmedPermissionToEmail = value;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignored) {
      unknownFields = true;
    }
  }

  public static final class TokenRequest {
    private String token;
    private boolean unknownFields;

    public String token() {
      return token;
    }

    public void setToken(String token) {
      this.token = token;
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
