package com.novelweb.library.invitation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class WorkspaceInvitationMailerTests {

  private static final String SIGNING_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void configuredMailerSendsAFragmentLinkWithoutExposingTokenInSubject() {
    @SuppressWarnings("unchecked")
    ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    JavaMailSender sender = mock(JavaMailSender.class);
    when(provider.getIfAvailable()).thenReturn(sender);
    WorkspaceInvitationTokenService tokenService =
        new WorkspaceInvitationTokenService(SIGNING_KEY, "https://app.example.test");
    WorkspaceInvitationMailer mailer =
        new WorkspaceInvitationMailer(
            provider,
            tokenService,
            "no-reply@example.test",
            "smtp.example.test",
            587,
            true,
            "smtp-user",
            "smtp-password");
    UUID invitationId = UUID.randomUUID();

    assertTrue(mailer.isConfigured());
    mailer.send(
        new WorkspaceInvitationMailer.Delivery(
            invitationId, "reader@example.test", "Nhóm đọc", "reader"));

    ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(message.capture());
    assertEquals("no-reply@example.test", message.getValue().getFrom());
    assertEquals("reader@example.test", message.getValue().getTo()[0]);
    assertTrue(
        message
            .getValue()
            .getText()
            .contains("/workspace-invitations/" + invitationId + "#token="));
    assertFalse(message.getValue().getSubject().contains("token"));
    verify(sender).send(any(SimpleMailMessage.class));
  }

  @Test
  void authenticatedSmtpWithoutCredentialsIsNotReady() {
    @SuppressWarnings("unchecked")
    ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(mock(JavaMailSender.class));
    WorkspaceInvitationMailer mailer =
        new WorkspaceInvitationMailer(
            provider,
            new WorkspaceInvitationTokenService(SIGNING_KEY, "https://app.example.test"),
            "no-reply@example.test",
            "smtp.example.test",
            587,
            true,
            "",
            "");

    assertFalse(mailer.isConfigured());
  }
}
