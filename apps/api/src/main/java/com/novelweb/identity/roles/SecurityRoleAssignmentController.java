package com.novelweb.identity.roles;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Least-privilege administration of publication reviewer assignments. */
@RestController
@RequestMapping("/api/v1/admin/security")
@Profile("postgres")
public class SecurityRoleAssignmentController {

  private final SecurityRoleAssignmentService service;

  public SecurityRoleAssignmentController(SecurityRoleAssignmentService service) {
    this.service = service;
  }

  @GetMapping("/role-assignments")
  public ResponseEntity<?> listReviewerAssignments(
      Authentication authentication,
      @RequestParam(required = false) String role,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to manage reviewer access.",
          request);
    }
    if (!"publication_reviewer".equals(role)) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_ROLE_FILTER",
          "Request the publication_reviewer role assignment list.",
          request);
    }
    try {
      List<SecurityRoleAssignmentRepository.ReviewerAssignment> items =
          service.listActiveReviewers(actor.orElseThrow());
      return noStore(HttpStatus.OK, new ReviewerAssignmentListResponse(items));
    } catch (SecurityRoleAssignmentRepository.NotSystemAdminException exception) {
      return problem(
          HttpStatus.FORBIDDEN,
          "ADMIN_REQUIRED",
          "An active system administrator is required for this action.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "ROLE_STORE_UNAVAILABLE",
          "Reviewer access could not be loaded. Please try again.",
          request);
    }
  }

  @PutMapping("/users/{userId}/roles/publication_reviewer")
  public ResponseEntity<?> grantReviewer(
      Authentication authentication,
      @PathVariable UUID userId,
      @RequestBody(required = false) ReviewerRoleRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to manage reviewer access.",
          request);
    }
    if (body == null || body.hasUnknownFields()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_ROLE_ASSIGNMENT",
          "Provide only a supported reasonCode for this assignment.",
          request);
    }
    try {
      SecurityRoleAssignmentRepository.CommandResult result =
          service.grantReviewer(actor.orElseThrow(), userId, body.reasonCode(), idempotencyKey);
      return noStore(
          HttpStatus.valueOf(result.responseStatus()),
          new RoleAssignmentCommandResponse(
              result.resultCode(), result.assignment(), result.replayed()));
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_ROLE_ASSIGNMENT",
          "Provide a supported reasonCode and a valid Idempotency-Key.",
          request);
    } catch (SecurityRoleAssignmentRepository.NotSystemAdminException exception) {
      return problem(
          HttpStatus.FORBIDDEN,
          "ADMIN_REQUIRED",
          "An active system administrator is required for this action.",
          request);
    } catch (SecurityRoleAssignmentRepository.TargetUserUnavailableException exception) {
      return problem(
          HttpStatus.NOT_FOUND,
          "TARGET_UNAVAILABLE",
          "The target account is unavailable for reviewer assignment.",
          request);
    } catch (SecurityRoleAssignmentRepository.SelfAssignmentNotAllowedException exception) {
      return problem(
          HttpStatus.CONFLICT,
          "SELF_ASSIGNMENT_FORBIDDEN",
          "An administrator cannot assign reviewer access to their own account.",
          request);
    } catch (SecurityRoleAssignmentRepository.IdempotencyKeyReusedException exception) {
      return problem(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "Use a new Idempotency-Key when the assignment details change.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "ROLE_STORE_UNAVAILABLE",
          "Reviewer access could not be changed. Please try again.",
          request);
    }
  }

  @DeleteMapping("/users/{userId}/roles/publication_reviewer")
  public ResponseEntity<?> revokeReviewer(
      Authentication authentication,
      @PathVariable UUID userId,
      @RequestBody(required = false) ReviewerRoleRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty()) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to manage reviewer access.",
          request);
    }
    if (body == null || body.hasUnknownFields()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_ROLE_ASSIGNMENT",
          "Provide only a supported reasonCode for this revocation.",
          request);
    }
    try {
      SecurityRoleAssignmentRepository.CommandResult result =
          service.revokeReviewer(actor.orElseThrow(), userId, body.reasonCode(), idempotencyKey);
      return noStore(HttpStatus.NO_CONTENT, null);
    } catch (IllegalArgumentException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_ROLE_ASSIGNMENT",
          "Provide a supported reasonCode and a valid Idempotency-Key.",
          request);
    } catch (SecurityRoleAssignmentRepository.NotSystemAdminException exception) {
      return problem(
          HttpStatus.FORBIDDEN,
          "ADMIN_REQUIRED",
          "An active system administrator is required for this action.",
          request);
    } catch (SecurityRoleAssignmentRepository.TargetUserUnavailableException exception) {
      return problem(
          HttpStatus.NOT_FOUND,
          "TARGET_UNAVAILABLE",
          "The target account is unavailable.",
          request);
    } catch (SecurityRoleAssignmentRepository.IdempotencyKeyReusedException exception) {
      return problem(
          HttpStatus.CONFLICT,
          "IDEMPOTENCY_KEY_REUSED",
          "Use a new Idempotency-Key when the revocation details change.",
          request);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "ROLE_STORE_UNAVAILABLE",
          "Reviewer access could not be changed. Please try again.",
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

  private static <T> ResponseEntity<T> noStore(HttpStatus status, T body) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    Object correlation = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    String correlationId = correlation instanceof String value ? value : "unavailable";
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setProperty("code", code);
    body.setProperty("correlationId", correlationId);
    return noStore(status, body);
  }

  public record ReviewerAssignmentListResponse(
      List<SecurityRoleAssignmentRepository.ReviewerAssignment> items) {
    public ReviewerAssignmentListResponse {
      items = List.copyOf(items);
    }
  }

  public record RoleAssignmentCommandResponse(
      String resultCode,
      SecurityRoleAssignmentRepository.ReviewerAssignment assignment,
      boolean replayed) {}

  public static final class ReviewerRoleRequest {
    private String reasonCode;
    private boolean unknownFields;

    public String reasonCode() {
      return reasonCode;
    }

    public boolean hasUnknownFields() {
      return unknownFields;
    }

    @JsonAnySetter
    public void unknownField(String name, Object value) {
      unknownFields = true;
    }

    public void setReasonCode(String reasonCode) {
      this.reasonCode = reasonCode;
    }
  }
}
