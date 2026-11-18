package com.novelweb.library.invitation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceInvitationTokenServiceTests {

  private static final String SIGNING_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void tokenIsDeterministicBoundToInvitationIdAndUsesAppFragment() {
    WorkspaceInvitationTokenService service =
        new WorkspaceInvitationTokenService(SIGNING_KEY, "https://app.example.test/");
    UUID invitationId = UUID.randomUUID();

    String token = service.tokenFor(invitationId);

    assertTrue(service.matches(invitationId, token));
    assertTrue(service.invitationUrl(invitationId).contains("#token=" + token));
    assertFalse(service.invitationUrl(invitationId).contains("?token="));
    assertNotEquals(service.tokenFor(UUID.randomUUID()), token);
    assertFalse(service.matches(invitationId, token + "x"));
  }

  @Test
  void missingOrShortKeyAndNonTlsRemoteAppUrlDisableLinks() {
    WorkspaceInvitationTokenService missing =
        new WorkspaceInvitationTokenService("", "https://app.example.test");
    WorkspaceInvitationTokenService shortKey =
        new WorkspaceInvitationTokenService(
            Base64.getEncoder().encodeToString(new byte[16]), "https://app.example.test");
    WorkspaceInvitationTokenService insecureRemote =
        new WorkspaceInvitationTokenService(SIGNING_KEY, "http://app.example.test");

    assertFalse(missing.isConfigured());
    assertFalse(shortKey.isConfigured());
    assertFalse(insecureRemote.isConfigured());
  }

  @Test
  void localHttpIsAllowedForDevelopment() {
    WorkspaceInvitationTokenService service =
        new WorkspaceInvitationTokenService(SIGNING_KEY, "http://localhost:4200");

    assertTrue(service.isConfigured());
  }

  @Test
  void appUrlRejectsExtraPathQueryAndFragment() {
    WorkspaceInvitationTokenService nested =
        new WorkspaceInvitationTokenService(SIGNING_KEY, "https://app.example.test/nested");
    WorkspaceInvitationTokenService query =
        new WorkspaceInvitationTokenService(SIGNING_KEY, "https://app.example.test/?next=evil");
    WorkspaceInvitationTokenService fragment =
        new WorkspaceInvitationTokenService(SIGNING_KEY, "https://app.example.test/#token=evil");

    assertFalse(nested.isConfigured());
    assertFalse(query.isConfigured());
    assertFalse(fragment.isConfigured());
  }
}
