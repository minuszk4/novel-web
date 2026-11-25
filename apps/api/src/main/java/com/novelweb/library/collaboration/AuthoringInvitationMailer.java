package com.novelweb.library.collaboration;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Sends invitation email without logging recipient addresses or bearer links. */
@Service
@Profile("postgres")
public class AuthoringInvitationMailer {

  private final ObjectProvider<JavaMailSender> mailSender;
  private final AuthoringInvitationTokenService tokenService;
  private final String senderAddress;
  private final String smtpHost;
  private final int smtpPort;
  private final boolean authenticationEnabled;
  private final String smtpUsername;
  private final String smtpPassword;

  public AuthoringInvitationMailer(
      ObjectProvider<JavaMailSender> mailSender,
      AuthoringInvitationTokenService tokenService,
      @Value("${novel.workspaces.invitations.sender-address:}") String senderAddress,
      @Value("${spring.mail.host:}") String smtpHost,
      @Value("${spring.mail.port:587}") int smtpPort,
      @Value("${spring.mail.properties[mail.smtp.auth]:true}") boolean authenticationEnabled,
      @Value("${spring.mail.username:}") String smtpUsername,
      @Value("${spring.mail.password:}") String smtpPassword) {
    this.mailSender = Objects.requireNonNull(mailSender);
    this.tokenService = Objects.requireNonNull(tokenService);
    this.senderAddress = senderAddress == null ? "" : senderAddress.trim();
    this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
    this.smtpPort = smtpPort;
    this.authenticationEnabled = authenticationEnabled;
    this.smtpUsername = smtpUsername == null ? "" : smtpUsername.trim();
    this.smtpPassword = smtpPassword == null ? "" : smtpPassword;
  }

  public boolean isConfigured() {
    return mailSender.getIfAvailable() != null
        && !senderAddress.isBlank()
        && !smtpHost.isBlank()
        && smtpHost.chars().noneMatch(Character::isWhitespace)
        && smtpPort >= 1
        && smtpPort <= 65_535
        && (!authenticationEnabled || (!smtpUsername.isBlank() && !smtpPassword.isBlank()))
        && validSenderAddress()
        && tokenService.isConfigured();
  }

  public void send(Delivery delivery) {
    JavaMailSender sender = mailSender.getIfAvailable();
    if (sender == null || !isConfigured()) throw new MailDeliveryUnavailableException();
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(senderAddress);
    message.setTo(delivery.recipientEmail());
    message.setSubject("Lời mời cộng tác trên Novel Web");
    message.setText(
        "Bạn được mời cộng tác trên một bản thảo Novel Web với quyền: "
            + String.join(", ", delivery.permissions())
            + ".\n\nMở liên kết để xem thông tin lời mời, đăng nhập bằng tài khoản có email đã xác minh, "
            + "rồi chọn chấp nhận hoặc từ chối:\n"
            + tokenService.invitationUrl(delivery.invitationId())
            + "\n\nLiên kết có thời hạn. Nếu bạn không mong đợi lời mời này, hãy bỏ qua email.");
    sender.send(message);
  }

  private boolean validSenderAddress() {
    try {
      InternetAddress address = new InternetAddress(senderAddress, true);
      address.validate();
      return true;
    } catch (AddressException exception) {
      return false;
    }
  }

  public record Delivery(
      UUID invitationId, String recipientEmail, java.util.List<String> permissions) {}

  public static class MailDeliveryUnavailableException extends RuntimeException {}
}
