package com.novelweb.library.invitation;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class WorkspaceInvitationService {

  private final WorkspaceInvitationRepository repository;
  private final WorkspaceInvitationTokenService tokenService;
  private final WorkspaceInvitationMailer mailer;
  private final boolean enabled;
  private final boolean workerEnabled;
  private final Duration ttl;

  public WorkspaceInvitationService(
      WorkspaceInvitationRepository repository,
      WorkspaceInvitationTokenService tokenService,
      WorkspaceInvitationMailer mailer,
      @Value("${novel.workspaces.invitations.enabled:false}") boolean enabled,
      @Value("${novel.workspaces.invitations.worker-enabled:false}") boolean workerEnabled,
      @Value("${novel.workspaces.invitations.ttl:7d}") Duration ttl) {
    this.repository = Objects.requireNonNull(repository);
    this.tokenService = Objects.requireNonNull(tokenService);
    this.mailer = Objects.requireNonNull(mailer);
    this.enabled = enabled;
    this.workerEnabled = workerEnabled;
    this.ttl = validTtl(ttl);
  }

  public WorkspaceInvitationRepository.InvitationRecord create(
      UUID actorId,
      UUID workspaceId,
      UUID invitationId,
      String email,
      String role,
      boolean confirmedEmailRequest) {
    requireReady();
    if (!confirmedEmailRequest)
      throw new IllegalArgumentException("Confirm the invitation email request.");
    String normalizedEmail = normalizeEmail(email);
    if (!List.of("admin", "editor", "reader").contains(role)) {
      throw new IllegalArgumentException("role must be admin, editor, or reader");
    }
    return repository.create(actorId, workspaceId, invitationId, normalizedEmail, role, ttl);
  }

  public List<WorkspaceInvitationRepository.InvitationSummary> list(
      UUID actorId, UUID workspaceId) {
    requireReady();
    return repository.list(actorId, workspaceId);
  }

  public void revoke(UUID actorId, UUID workspaceId, UUID invitationId) {
    requireReady();
    repository.revoke(actorId, workspaceId, invitationId);
  }

  public WorkspaceInvitationRepository.RecipientInvitation resolve(
      UUID actorId, UUID invitationId, String token) {
    requireReady();
    WorkspaceInvitationRepository.RecipientInvitation invitation =
        repository
            .findForRecipient(actorId, invitationId)
            .orElseThrow(InvitationNotFoundException::new);
    if (!tokenService.matches(invitationId, token)) throw new InvitationNotFoundException();
    return invitation;
  }

  public void decide(UUID actorId, UUID invitationId, String token, boolean accept) {
    resolve(actorId, invitationId, token);
    repository.decide(actorId, invitationId, accept);
  }

  public int deliverDueBatch() {
    if (!isEnabled()) return 0;
    List<WorkspaceInvitationRepository.ClaimedDelivery> claims = repository.claimDue();
    for (WorkspaceInvitationRepository.ClaimedDelivery claim : claims) {
      Optional<WorkspaceInvitationMailer.Delivery> delivery = repository.delivery(claim);
      if (delivery.isEmpty()) {
        repository.cancelClaim(claim);
        continue;
      }
      try {
        mailer.send(delivery.orElseThrow());
        repository.complete(claim);
      } catch (RuntimeException exception) {
        repository.fail(claim, true);
      }
    }
    return claims.size();
  }

  public boolean isEnabled() {
    return enabled && workerEnabled && mailer.isConfigured();
  }

  private void requireReady() {
    if (!isEnabled()) throw new InvitationUnavailableException();
  }

  private static String normalizeEmail(String email) {
    String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (normalized.length() > 320
        || normalized.length() < 3
        || normalized.indexOf('@') <= 0
        || normalized.indexOf('@') != normalized.lastIndexOf('@')
        || normalized.endsWith("@")
        || normalized.chars().anyMatch(Character::isWhitespace)
        || normalized.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
      throw new IllegalArgumentException("Provide a valid email address.");
    }
    try {
      InternetAddress address = new InternetAddress(normalized, true);
      address.validate();
    } catch (AddressException exception) {
      throw new IllegalArgumentException("Provide a valid email address.");
    }
    return normalized;
  }

  private static Duration validTtl(Duration requested) {
    if (requested == null
        || requested.compareTo(Duration.ofHours(1)) < 0
        || requested.compareTo(Duration.ofDays(14)) > 0) {
      throw new IllegalArgumentException(
          "Workspace invitation TTL must be between 1 hour and 14 days.");
    }
    return requested;
  }

  public static class InvitationUnavailableException extends RuntimeException {}

  public static class InvitationNotFoundException extends RuntimeException {}
}
