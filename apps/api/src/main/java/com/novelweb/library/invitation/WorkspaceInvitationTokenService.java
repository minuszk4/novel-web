package com.novelweb.library.invitation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Derives single-invitation bearer links without persisting the raw token. */
@Service
public class WorkspaceInvitationTokenService {

  private static final String TOKEN_CONTEXT = "novel-web/workspace-invitation/v1:";

  private final byte[] signingKey;
  private final String appBaseUrl;

  public WorkspaceInvitationTokenService(
      @Value("${novel.workspaces.invitations.signing-key:}") String encodedSigningKey,
      @Value("${novel.workspaces.invitations.app-base-url:http://localhost:4200}")
          String requestedAppBaseUrl) {
    byte[] decoded = decodeKey(encodedSigningKey);
    this.signingKey = decoded.length >= 32 ? decoded : new byte[0];
    this.appBaseUrl = normalizeAppBaseUrl(requestedAppBaseUrl);
  }

  public boolean isConfigured() {
    return signingKey.length >= 32 && !appBaseUrl.isBlank();
  }

  public String tokenFor(UUID invitationId) {
    if (!isConfigured())
      throw new IllegalStateException("Workspace invitation signing is disabled");
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac(invitationId));
  }

  public boolean matches(UUID invitationId, String candidate) {
    if (!isConfigured() || candidate == null || candidate.length() > 128) return false;
    byte[] expected = mac(invitationId);
    byte[] supplied;
    try {
      supplied = Base64.getUrlDecoder().decode(candidate);
    } catch (IllegalArgumentException exception) {
      return false;
    }
    return MessageDigest.isEqual(expected, supplied);
  }

  public String invitationUrl(UUID invitationId) {
    return appBaseUrl
        + "/workspace-invitations/"
        + invitationId
        + "#token="
        + tokenFor(invitationId);
  }

  private byte[] mac(UUID invitationId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
      mac.update(TOKEN_CONTEXT.getBytes(StandardCharsets.UTF_8));
      mac.update(invitationId.toString().getBytes(StandardCharsets.US_ASCII));
      return mac.doFinal();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Workspace invitation token could not be created", exception);
    }
  }

  private static byte[] decodeKey(String encoded) {
    if (encoded == null || encoded.isBlank()) return new byte[0];
    try {
      return Base64.getDecoder().decode(encoded.trim());
    } catch (IllegalArgumentException exception) {
      return new byte[0];
    }
  }

  private static String normalizeAppBaseUrl(String requested) {
    if (requested == null || requested.isBlank()) return "";
    String candidate = requested.trim().replaceAll("/+$", "");
    try {
      URI uri = URI.create(candidate);
      boolean secure = "https".equalsIgnoreCase(uri.getScheme());
      boolean local =
          "http".equalsIgnoreCase(uri.getScheme())
              && ("localhost".equalsIgnoreCase(uri.getHost())
                  || "127.0.0.1".equals(uri.getHost())
                  || "[::1]".equals(uri.getHost()));
      String path = uri.getPath();
      if ((!secure && !local)
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || (path != null && !path.isEmpty() && !"/".equals(path))
          || uri.getQuery() != null
          || uri.getFragment() != null) return "";
      return candidate;
    } catch (IllegalArgumentException exception) {
      return "";
    }
  }
}
