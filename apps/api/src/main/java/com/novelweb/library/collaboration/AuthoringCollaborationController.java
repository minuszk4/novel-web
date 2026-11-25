package com.novelweb.library.collaboration;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session-scoped API for private manuscript collaboration. */
@RestController
@RequestMapping("/api/v1")
@Profile("postgres")
public class AuthoringCollaborationController {

  private static final Pattern GRANT_ETAG = Pattern.compile("\\\"manuscript-grant-(\\d+)\\\"");

  private final AuthoringCollaborationService service;
  private final PolicyService policyService;

  public AuthoringCollaborationController(
      AuthoringCollaborationService service, PolicyService policyService) {
    this.service = service;
    this.policyService = policyService;
  }

  @GetMapping("/authoring/manuscripts/{workId}/contributors")
  public ResponseEntity<?> contributors(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    AuthoringCollaborationRepository.ManagementList list =
        service.list(actor.orElseThrow(), workId);
    List<GrantItem> grants =
        list.collaborators().stream()
            .map(
                grant ->
                    new GrantItem(
                        grant.id(),
                        grant.permissions(),
                        grant.version(),
                        grant.grantedAt(),
                        grantEtag(grant.version())))
            .toList();
    List<InvitationItem> invitations =
        list.invitations().stream()
            .map(
                invitation ->
                    new InvitationItem(
                        invitation.id(),
                        invitation.permissions(),
                        invitation.status(),
                        invitation.createdAt(),
                        invitation.expiresAt()))
            .toList();
    return noStore(HttpStatus.OK).body(new ManagementResponse(grants, invitations));
  }

  @GetMapping("/authoring/manuscripts/{workId}/invitations")
  public ResponseEntity<?> invitations(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    List<InvitationItem> invitations =
        service.list(actor.orElseThrow(), workId).invitations().stream()
            .map(
                invitation ->
                    new InvitationItem(
                        invitation.id(),
                        invitation.permissions(),
                        invitation.status(),
                        invitation.createdAt(),
                        invitation.expiresAt()))
            .toList();
    return noStore(HttpStatus.OK).body(new InvitationList(invitations));
  }

  @PutMapping("/authoring/manuscripts/{workId}/invitations/{invitationId}")
  public ResponseEntity<?> invite(
      Authentication authentication,
      @PathVariable UUID workId,
      @PathVariable UUID invitationId,
      @RequestBody(required = false) CreateInvitationRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (body == null
        || body.hasUnknownFields()
        || body.email() == null
        || body.permissions() == null
        || !body.confirmedPermissionToEmail()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_MANUSCRIPT_INVITATION",
          "Provide an email, permission set, and confirmation that you may send the invitation.",
          request);
    }
    AuthoringCollaborationRepository.InvitationRecord invitation =
        service.create(
            actor.orElseThrow(),
            workId,
            invitationId,
            body.email(),
            body.permissions(),
            body.confirmedPermissionToEmail());
    return noStore(HttpStatus.ACCEPTED)
        .body(
            new InvitationQueued(
                invitation.id(),
                invitation.permissions(),
                invitation.status(),
                invitation.expiresAt(),
                "If eligible, the invitation email will be sent shortly."));
  }

  @DeleteMapping("/authoring/manuscripts/{workId}/invitations/{invitationId}")
  public ResponseEntity<?> revokeInvitation(
      Authentication authentication,
      @PathVariable UUID workId,
      @PathVariable UUID invitationId,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    service.revokeInvitation(actor.orElseThrow(), workId, invitationId);
    return noStore(HttpStatus.NO_CONTENT).build();
  }

  @DeleteMapping("/authoring/manuscripts/{workId}/contributors/{grantId}")
  public ResponseEntity<?> revokeGrant(
      Authentication authentication,
      @PathVariable UUID workId,
      @PathVariable UUID grantId,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (ifMatch == null)
      return problem(HttpStatus.PRECONDITION_REQUIRED, "GRANT_PRECONDITION_REQUIRED", request);
    Matcher matcher = GRANT_ETAG.matcher(ifMatch);
    if (!matcher.matches()) return problem(HttpStatus.BAD_REQUEST, "INVALID_GRANT_ETAG", request);
    service.revokeGrant(actor.orElseThrow(), workId, grantId, Long.parseLong(matcher.group(1)));
    return noStore(HttpStatus.NO_CONTENT).build();
  }

  @PutMapping("/authoring/manuscripts/{workId}/contributors/{grantId}")
  public ResponseEntity<?> changePermissions(
      Authentication authentication,
      @PathVariable UUID workId,
      @PathVariable UUID grantId,
      @RequestBody(required = false) ChangePermissionsRequest body,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (body == null
        || body.hasUnknownFields()
        || body.invitationId() == null
        || body.permissions() == null
        || !body.confirmedPermissionToEmail()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_MANUSCRIPT_PERMISSION_CHANGE",
          "Provide a new invitation ID, permission set, and email-contact confirmation.",
          request);
    }
    if (ifMatch == null)
      return problem(HttpStatus.PRECONDITION_REQUIRED, "GRANT_PRECONDITION_REQUIRED", request);
    Matcher matcher = GRANT_ETAG.matcher(ifMatch);
    if (!matcher.matches()) return problem(HttpStatus.BAD_REQUEST, "INVALID_GRANT_ETAG", request);
    AuthoringCollaborationRepository.InvitationRecord invitation =
        service.changePermissions(
            actor.orElseThrow(),
            workId,
            grantId,
            Long.parseLong(matcher.group(1)),
            body.invitationId(),
            body.permissions(),
            body.confirmedPermissionToEmail());
    return noStore(HttpStatus.ACCEPTED)
        .body(
            new InvitationQueued(
                invitation.id(),
                invitation.permissions(),
                invitation.status(),
                invitation.expiresAt(),
                "If eligible, the updated-permission invitation email will be sent shortly."));
  }

  @GetMapping("/authoring/collaborations")
  public ResponseEntity<?> sharedWorks(Authentication authentication, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    List<SharedWorkItem> items =
        service.listSharedWorks(actor.orElseThrow()).stream()
            .map(
                item ->
                    new SharedWorkItem(
                        item.workId(),
                        item.title(),
                        item.author(),
                        item.permissions(),
                        item.grantedAt()))
            .toList();
    return noStore(HttpStatus.OK).body(new SharedWorkList(items));
  }

  @PostMapping("/me/authoring-invitations/{invitationId}/resolve")
  public ResponseEntity<?> resolve(
      Authentication authentication,
      @PathVariable UUID invitationId,
      @RequestBody(required = false) TokenRequest body,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (body == null || body.hasUnknownFields() || body.token() == null)
      return problem(HttpStatus.BAD_REQUEST, "INVALID_INVITATION_TOKEN", request);
    AuthoringCollaborationRepository.RecipientInvitation invitation =
        service
            .resolve(actor.orElseThrow(), invitationId, body.token())
            .orElseThrow(AuthoringCollaborationRepository.InvitationNotFoundException::new);
    return noStore(HttpStatus.OK)
        .body(
            new RecipientInvitation(
                invitation.id(),
                invitation.title(),
                invitation.author(),
                invitation.permissions(),
                invitation.expiresAt()));
  }

  @PostMapping("/me/authoring-invitations/{invitationId}/accept")
  public ResponseEntity<?> accept(
      Authentication authentication,
      @PathVariable UUID invitationId,
      @RequestBody(required = false) TokenRequest body,
      HttpServletRequest request) {
    return decide(authentication, invitationId, body, true, request);
  }

  @PostMapping("/me/authoring-invitations/{invitationId}/reject")
  public ResponseEntity<?> reject(
      Authentication authentication,
      @PathVariable UUID invitationId,
      @RequestBody(required = false) TokenRequest body,
      HttpServletRequest request) {
    return decide(authentication, invitationId, body, false, request);
  }

  private ResponseEntity<?> decide(
      Authentication authentication,
      UUID invitationId,
      TokenRequest body,
      boolean accept,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", request);
    ResponseEntity<?> gate = policyGate(actor.orElseThrow(), request);
    if (gate != null) return gate;
    if (body == null || body.hasUnknownFields() || body.token() == null)
      return problem(HttpStatus.BAD_REQUEST, "INVALID_INVITATION_TOKEN", request);
    service.decide(actor.orElseThrow(), invitationId, body.token(), accept);
    return noStore(HttpStatus.OK)
        .body(new InvitationDecision(invitationId, accept ? "accepted" : "rejected"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalid(HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_MANUSCRIPT_INVITATION", request);
  }

  @ExceptionHandler(AuthoringCollaborationRepository.InvitationNotFoundException.class)
  public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
    return problem(HttpStatus.NOT_FOUND, "MANUSCRIPT_INVITATION_NOT_FOUND", request);
  }

  @ExceptionHandler({
    AuthoringCollaborationRepository.InvitationConflictException.class,
    AuthoringCollaborationRepository.GrantConflictException.class
  })
  public ResponseEntity<ProblemDetail> conflict(HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "MANUSCRIPT_COLLABORATION_CONFLICT", request);
  }

  @ExceptionHandler(AuthoringCollaborationRepository.GrantPreconditionFailedException.class)
  public ResponseEntity<ProblemDetail> grantPreconditionFailed(HttpServletRequest request) {
    return problem(HttpStatus.PRECONDITION_FAILED, "GRANT_VERSION_STALE", request);
  }

  @ExceptionHandler(AuthoringCollaborationRepository.InvitationRateLimitException.class)
  public ResponseEntity<ProblemDetail> rateLimited(HttpServletRequest request) {
    return problem(HttpStatus.TOO_MANY_REQUESTS, "MANUSCRIPT_INVITATION_RATE_LIMITED", request);
  }

  @ExceptionHandler(AuthoringCollaborationService.CollaborationUnavailableException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "MANUSCRIPT_COLLABORATION_UNAVAILABLE",
        "Manuscript collaboration is disabled or not configured.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> storageUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "MANUSCRIPT_COLLABORATION_UNAVAILABLE",
        "Manuscript collaboration data could not be loaded or saved. Please retry.",
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

  private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache");
  }

  private static String grantEtag(long version) {
    return "\"manuscript-grant-" + version + "\"";
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

  public record ManagementResponse(
      List<GrantItem> collaborators, List<InvitationItem> invitations) {}

  public record InvitationList(List<InvitationItem> items) {}

  public record GrantItem(
      UUID id, List<String> permissions, long version, Instant grantedAt, String etag) {}

  public record InvitationItem(
      UUID id, List<String> permissions, String status, Instant createdAt, Instant expiresAt) {}

  public record InvitationQueued(
      UUID id, List<String> permissions, String status, Instant expiresAt, String message) {}

  public record RecipientInvitation(
      UUID id, String title, String author, List<String> permissions, Instant expiresAt) {}

  public record InvitationDecision(UUID id, String status) {}

  public record SharedWorkItem(
      UUID workId, String title, String author, List<String> permissions, Instant grantedAt) {}

  public record SharedWorkList(List<SharedWorkItem> items) {}

  public static final class CreateInvitationRequest {
    private String email;
    private List<String> permissions;
    private boolean confirmedPermissionToEmail;
    private boolean unknownFields;

    public String email() {
      return email;
    }

    public List<String> permissions() {
      return permissions;
    }

    public boolean confirmedPermissionToEmail() {
      return confirmedPermissionToEmail;
    }

    public void setEmail(String email) {
      this.email = email;
    }

    public void setPermissions(List<String> permissions) {
      this.permissions = permissions;
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

  public static final class ChangePermissionsRequest {
    private UUID invitationId;
    private List<String> permissions;
    private boolean confirmedPermissionToEmail;
    private boolean unknownFields;

    public UUID invitationId() {
      return invitationId;
    }

    public List<String> permissions() {
      return permissions;
    }

    public boolean confirmedPermissionToEmail() {
      return confirmedPermissionToEmail;
    }

    public void setInvitationId(UUID invitationId) {
      this.invitationId = invitationId;
    }

    public void setPermissions(List<String> permissions) {
      this.permissions = permissions;
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
}
