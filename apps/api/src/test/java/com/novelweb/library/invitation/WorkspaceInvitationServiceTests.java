package com.novelweb.library.invitation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkspaceInvitationServiceTests {

  private static final String SIGNING_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Mock private WorkspaceInvitationRepository repository;
  @Mock private WorkspaceInvitationMailer mailer;

  private WorkspaceInvitationTokenService tokenService;
  private WorkspaceInvitationService service;

  @BeforeEach
  void setUp() {
    tokenService = new WorkspaceInvitationTokenService(SIGNING_KEY, "https://app.example.test");
    service =
        new WorkspaceInvitationService(
            repository, tokenService, mailer, true, true, Duration.ofDays(7));
  }

  @Test
  void createCanonicalizesAddressAndKeepsTheConfiguredBoundedLifetime() {
    UUID actor = UUID.randomUUID();
    UUID workspace = UUID.randomUUID();
    UUID invitation = UUID.randomUUID();
    Instant created = Instant.now();
    var record =
        new WorkspaceInvitationRepository.InvitationRecord(
            invitation,
            workspace,
            "reader@example.test",
            "reader",
            actor,
            "pending",
            created,
            created.plus(Duration.ofDays(7)));
    when(mailer.isConfigured()).thenReturn(true);
    when(repository.create(
            eq(actor),
            eq(workspace),
            eq(invitation),
            eq("reader@example.test"),
            eq("reader"),
            eq(Duration.ofDays(7))))
        .thenReturn(record);

    var actual =
        service.create(actor, workspace, invitation, "  Reader@Example.Test  ", "reader", true);

    assertEquals(record, actual);
    verify(repository)
        .create(actor, workspace, invitation, "reader@example.test", "reader", Duration.ofDays(7));
  }

  @Test
  void requestNeedsPermissionConfirmationAndValidRoleAndAddress() {
    when(mailer.isConfigured()).thenReturn(true);
    UUID actor = UUID.randomUUID();
    UUID workspace = UUID.randomUUID();
    UUID invitation = UUID.randomUUID();

    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(actor, workspace, invitation, "reader@example.test", "reader", false));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(actor, workspace, invitation, "reader@example.test", "owner", true));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(actor, workspace, invitation, "bad address", "reader", true));
    verifyNoInteractions(repository);
  }

  @Test
  void inviteDecisionRequiresAValidSignedToken() {
    when(mailer.isConfigured()).thenReturn(true);
    UUID actor = UUID.randomUUID();
    UUID invitation = UUID.randomUUID();
    when(repository.findForRecipient(actor, invitation))
        .thenReturn(
            java.util.Optional.of(
                new WorkspaceInvitationRepository.RecipientInvitation(
                    invitation,
                    UUID.randomUUID(),
                    "Reading Room",
                    "reader@example.test",
                    "reader",
                    Instant.now().plus(Duration.ofDays(1)))));

    assertThrows(
        WorkspaceInvitationService.InvitationNotFoundException.class,
        () -> service.decide(actor, invitation, "invalid", true));
    verify(repository).findForRecipient(actor, invitation);
    verify(repository, never()).decide(actor, invitation, true);
  }

  @Test
  void deliveryCompletesOnlyAfterConfiguredMailerAcceptsTheMessage() {
    UUID invitationId = UUID.randomUUID();
    var claim =
        new WorkspaceInvitationRepository.ClaimedDelivery(
            UUID.randomUUID(), invitationId, UUID.randomUUID(), 1);
    var delivery =
        new WorkspaceInvitationMailer.Delivery(
            invitationId, "reader@example.test", "Reading Room", "reader");
    when(mailer.isConfigured()).thenReturn(true);
    when(repository.claimDue()).thenReturn(List.of(claim));
    when(repository.delivery(claim)).thenReturn(Optional.of(delivery));

    assertEquals(1, service.deliverDueBatch());

    verify(mailer).send(delivery);
    verify(repository).complete(claim);
    verify(repository, never()).fail(eq(claim), eq(true));
  }

  @Test
  void deliveryFailureIsConvertedToRepositoryRetry() {
    UUID invitationId = UUID.randomUUID();
    var claim =
        new WorkspaceInvitationRepository.ClaimedDelivery(
            UUID.randomUUID(), invitationId, UUID.randomUUID(), 1);
    var delivery =
        new WorkspaceInvitationMailer.Delivery(
            invitationId, "reader@example.test", "Reading Room", "reader");
    when(mailer.isConfigured()).thenReturn(true);
    when(repository.claimDue()).thenReturn(List.of(claim));
    when(repository.delivery(claim)).thenReturn(Optional.of(delivery));
    doThrow(new IllegalStateException("synthetic transport failure")).when(mailer).send(delivery);

    assertEquals(1, service.deliverDueBatch());

    verify(repository).fail(claim, true);
    verify(repository, never()).complete(claim);
  }

  @Test
  void deliveryWithoutPendingInvitationCancelsItsClaim() {
    UUID invitationId = UUID.randomUUID();
    var claim =
        new WorkspaceInvitationRepository.ClaimedDelivery(
            UUID.randomUUID(), invitationId, UUID.randomUUID(), 1);
    when(mailer.isConfigured()).thenReturn(true);
    when(repository.claimDue()).thenReturn(List.of(claim));
    when(repository.delivery(claim)).thenReturn(Optional.empty());

    assertEquals(1, service.deliverDueBatch());

    verify(repository).cancelClaim(claim);
    verify(mailer).isConfigured();
    verify(mailer, never()).send(any());
  }

  @Test
  void deliveryDoesNotReadOutboxUntilEmailTransportIsReady() {
    when(mailer.isConfigured()).thenReturn(false);

    assertEquals(0, service.deliverDueBatch());

    verifyNoInteractions(repository);
    verify(mailer).isConfigured();
  }
}
