package com.novelweb.library.collaboration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
public class AuthoringCollaborationService {

  private static final List<String> PERMISSION_CODES = List.of("EDIT", "VIEW");

  private final AuthoringCollaborationRepository repository;
  private final AuthoringInvitationTokenService tokenService;
  private final AuthoringInvitationMailer mailer;
  private final boolean enabled;
  private final boolean workerEnabled;
  private final Duration ttl;

  public AuthoringCollaborationService(
      AuthoringCollaborationRepository repository,
      AuthoringInvitationTokenService tokenService,
      AuthoringInvitationMailer mailer,
      @Value("${novel.authoring.collaboration.enabled:false}") boolean enabled,
      @Value("${novel.authoring.collaboration.invitation-worker-enabled:false}")
          boolean workerEnabled,
      @Value("${novel.authoring.collaboration.ttl:7d}") Duration ttl) {
    this.repository = Objects.requireNonNull(repository);
    this.tokenService = Objects.requireNonNull(tokenService);
    this.mailer = Objects.requireNonNull(mailer);
    this.enabled = enabled;
    this.workerEnabled = workerEnabled;
    this.ttl = validTtl(ttl);
  }

  public AuthoringCollaborationRepository.InvitationRecord create(
      UUID ownerId,
      UUID workId,
      UUID invitationId,
      String email,
      List<String> permissions,
      boolean consented) {
    requireEnabled();
    if (!consented)
      throw new IllegalArgumentException("Confirm permission to send the invitation.");
    if (!tokenService.isConfigured()) throw new CollaborationUnavailableException();
    return repository.create(
        ownerId,
        workId,
        invitationId,
        normalizeEmail(email),
        normalizePermissions(permissions),
        ttl);
  }

  public AuthoringCollaborationRepository.ManagementList list(UUID actorId, UUID workId) {
    requireEnabled();
    return repository.list(actorId, workId);
  }

  public void revokeInvitation(UUID actorId, UUID workId, UUID invitationId) {
    requireEnabled();
    repository.revokeInvitation(actorId, workId, invitationId);
  }

  public Optional<AuthoringCollaborationRepository.RecipientInvitation> resolve(
      UUID actorId, UUID invitationId, String token) {
    requireEnabled();
    if (!tokenService.matches(invitationId, token)) return Optional.empty();
    return repository.findForRecipient(actorId, invitationId);
  }

  public void decide(UUID actorId, UUID invitationId, String token, boolean accept) {
    requireEnabled();
    if (!tokenService.matches(invitationId, token)) {
      throw new AuthoringCollaborationRepository.InvitationNotFoundException();
    }
    repository.decide(actorId, invitationId, accept);
  }

  public void revokeGrant(UUID actorId, UUID workId, UUID grantId, long expectedVersion) {
    requireEnabled();
    repository.revokeGrant(actorId, workId, grantId, expectedVersion);
  }

  public AuthoringCollaborationRepository.InvitationRecord changePermissions(
      UUID ownerId,
      UUID workId,
      UUID grantId,
      long expectedVersion,
      UUID invitationId,
      List<String> permissions,
      boolean consented) {
    requireEnabled();
    if (!consented)
      throw new IllegalArgumentException("Confirm permission to send the invitation.");
    if (!tokenService.isConfigured()) throw new CollaborationUnavailableException();
    return repository.createPermissionChange(
        ownerId,
        workId,
        grantId,
        expectedVersion,
        invitationId,
        normalizePermissions(permissions),
        ttl);
  }

  public List<AuthoringCollaborationRepository.SharedWork> listSharedWorks(UUID actorId) {
    requireEnabled();
    return repository.listSharedWorks(actorId);
  }

  public int deliverDueBatch() {
    if (!enabled || !workerEnabled || !mailer.isConfigured()) return 0;
    List<AuthoringCollaborationRepository.ClaimedDelivery> claims = repository.claimDue();
    for (AuthoringCollaborationRepository.ClaimedDelivery claim : claims) {
      Optional<AuthoringInvitationMailer.Delivery> delivery = repository.delivery(claim);
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
    return enabled;
  }

  private void requireEnabled() {
    if (!enabled) throw new CollaborationUnavailableException();
  }

  private static String normalizeEmail(String requested) {
    String email = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
    if (email.length() < 3
        || email.length() > 320
        || email.indexOf('@') <= 0
        || email.indexOf('@') != email.lastIndexOf('@')
        || email.endsWith("@")
        || email.chars().anyMatch(Character::isWhitespace)
        || email.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
      throw new IllegalArgumentException("Provide a valid email address.");
    }
    return email;
  }

  private static List<String> normalizePermissions(List<String> requested) {
    if (requested == null || requested.isEmpty() || requested.size() > PERMISSION_CODES.size()) {
      throw new IllegalArgumentException("Select one or more manuscript permissions.");
    }
    List<String> normalized = new ArrayList<>();
    for (String permission : requested) {
      if (permission == null || !PERMISSION_CODES.contains(permission)) {
        throw new IllegalArgumentException("Unsupported manuscript permission.");
      }
      if (normalized.contains(permission))
        throw new IllegalArgumentException("Duplicate permission.");
      normalized.add(permission);
    }
    if (!normalized.contains("VIEW")) {
      throw new IllegalArgumentException(
          "VIEW permission is required for every manuscript invitation.");
    }
    normalized.sort(Comparator.naturalOrder());
    return List.copyOf(normalized);
  }

  private static Duration validTtl(Duration requested) {
    if (requested == null
        || requested.compareTo(Duration.ofHours(1)) < 0
        || requested.compareTo(Duration.ofDays(14)) > 0) {
      throw new IllegalArgumentException(
          "Manuscript invitation TTL must be between 1 hour and 14 days.");
    }
    return requested;
  }

  public static class CollaborationUnavailableException extends RuntimeException {}
}
