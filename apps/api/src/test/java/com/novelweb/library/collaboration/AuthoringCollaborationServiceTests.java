package com.novelweb.library.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthoringCollaborationServiceTests {

  private static final String SIGNING_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Mock private AuthoringCollaborationRepository repository;
  @Mock private AuthoringInvitationMailer mailer;

  private AuthoringInvitationTokenService tokenService;
  private AuthoringCollaborationService service;

  @BeforeEach
  void setUp() {
    tokenService = new AuthoringInvitationTokenService(SIGNING_KEY, "https://app.example.test");
    service =
        new AuthoringCollaborationService(
            repository, tokenService, mailer, true, false, Duration.ofDays(7));
  }

  @Test
  void createCanonicalizesAddressAndPermissionSetAndRequiresExplicitConsent() {
    UUID ownerId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID invitationId = UUID.randomUUID();
    Instant createdAt = Instant.now();
    var record =
        new AuthoringCollaborationRepository.InvitationRecord(
            invitationId,
            workId,
            ownerId,
            "reader@example.test",
            List.of("EDIT", "VIEW"),
            "pending",
            null,
            createdAt,
            createdAt.plus(Duration.ofDays(7)));
    when(repository.create(
            eq(ownerId),
            eq(workId),
            eq(invitationId),
            eq("reader@example.test"),
            eq(List.of("EDIT", "VIEW")),
            eq(Duration.ofDays(7))))
        .thenReturn(record);

    var actual =
        service.create(
            ownerId,
            workId,
            invitationId,
            "  Reader@Example.Test  ",
            List.of("VIEW", "EDIT"),
            true);

    assertEquals(record, actual);
    verify(repository)
        .create(
            ownerId,
            workId,
            invitationId,
            "reader@example.test",
            List.of("EDIT", "VIEW"),
            Duration.ofDays(7));
  }

  @Test
  void viewIsRequiredAndUnsupportedPermissionOrMissingConsentIsRejected() {
    UUID ownerId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID invitationId = UUID.randomUUID();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                ownerId, workId, invitationId, "reader@example.test", List.of("EDIT"), true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                ownerId,
                workId,
                invitationId,
                "reader@example.test",
                List.of("VIEW", "PUBLISH"),
                true));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(ownerId, workId, invitationId, "bad address", List.of("VIEW"), true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.create(
                ownerId, workId, invitationId, "reader@example.test", List.of("VIEW"), false));
    verifyNoInteractions(repository);
  }

  @Test
  void disabledFeatureDoesNotReadOrWriteCollaborationData() {
    var disabled =
        new AuthoringCollaborationService(
            repository, tokenService, mailer, false, false, Duration.ofDays(7));

    assertThrows(
        AuthoringCollaborationService.CollaborationUnavailableException.class,
        () -> disabled.listSharedWorks(UUID.randomUUID()));
    assertEquals(0, disabled.deliverDueBatch());
    verifyNoInteractions(repository, mailer);
  }

  @Test
  void invitationDecisionRejectsInvalidDomainSeparatedToken() {
    UUID recipientId = UUID.randomUUID();
    UUID invitationId = UUID.randomUUID();

    assertThrows(
        AuthoringCollaborationRepository.InvitationNotFoundException.class,
        () -> service.decide(recipientId, invitationId, "invalid", true));
    verify(repository, never()).decide(recipientId, invitationId, true);
  }
}
