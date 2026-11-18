package com.novelweb.library.invitation;

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

/** Sends workspace invitation mail without logging the recipient or bearer link. */
@Service
@Profile("postgres")
public class WorkspaceInvitationMailer {

  private final ObjectProvider<JavaMailSender> mailSender;
  private final WorkspaceInvitationTokenService tokenService;
  private final String senderAddress;
  private final String smtpHost;
  private final int smtpPort;
  private final boolean smtpAuthenticationEnabled;
  private final String smtpUsername;
  private final String smtpPassword;

  public WorkspaceInvitationMailer(
      ObjectProvider<JavaMailSender> mailSender,
      WorkspaceInvitationTokenService tokenService,
      @Value("${novel.workspaces.invitations.sender-address:}") String senderAddress,
      @Value("${spring.mail.host:}") String smtpHost,
      @Value("${spring.mail.port:587}") int smtpPort,
      @Value("${spring.mail.properties[mail.smtp.auth]:true}") boolean smtpAuthenticationEnabled,
      @Value("${spring.mail.username:}") String smtpUsername,
      @Value("${spring.mail.password:}") String smtpPassword) {
    this.mailSender = Objects.requireNonNull(mailSender);
    this.tokenService = Objects.requireNonNull(tokenService);
    this.senderAddress = senderAddress == null ? "" : senderAddress.trim();
    this.smtpHost = smtpHost == null ? "" : smtpHost.trim();
    this.smtpPort = smtpPort;
    this.smtpAuthenticationEnabled = smtpAuthenticationEnabled;
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
        && (!smtpAuthenticationEnabled || (!smtpUsername.isBlank() && !smtpPassword.isBlank()))
        && validSenderAddress()
        && tokenService.isConfigured();
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

  public void send(Delivery delivery) {
    JavaMailSender sender = mailSender.getIfAvailable();
    if (sender == null || !isConfigured()) {
      throw new MailDeliveryUnavailableException();
    }
    SimpleMailMessage message = new SimpleMailMessage();
    message.setFrom(senderAddress);
    message.setTo(delivery.recipientEmail());
    message.setSubject("Lời mời tham gia workspace trên Novel Web");
    message.setText(
        "Bạn được mời tham gia workspace \""
            + delivery.workspaceName()
            + "\" với vai trò "
            + roleLabel(delivery.role())
            + ".\n\nMở liên kết này để xem lời mời, đăng nhập bằng tài khoản "
            + "có email đã xác minh, rồi chọn chấp nhận hoặc từ chối:\n"
            + tokenService.invitationUrl(delivery.invitationId())
            + "\n\nLiên kết chỉ dùng một lần và có thời hạn. "
            + "Nếu bạn không mong đợi lời mời này, hãy bỏ qua email. "
            + "Không trả lời email này bằng mật khẩu hoặc mã đăng nhập.");
    sender.send(message);
  }

  private static String roleLabel(String role) {
    return switch (role) {
      case "admin" -> "quản trị viên";
      case "editor" -> "biên tập viên";
      default -> "người đọc";
    };
  }

  public record Delivery(
      UUID invitationId, String recipientEmail, String workspaceName, String role) {}

  public static class MailDeliveryUnavailableException extends RuntimeException {}
}
