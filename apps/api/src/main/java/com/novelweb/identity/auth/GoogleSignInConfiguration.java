package com.novelweb.identity.auth;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared Google sign-in configuration and readiness checks. */
@Component
public class GoogleSignInConfiguration {

  private static final String GOOGLE_CLIENT_ID_SUFFIX = ".apps.googleusercontent.com";
  private static final String CREDENTIAL_CALLBACK_PATH = "/api/v1/auth/google/credential";

  private final String webClientId;
  private final String rateLimitHmacKey;
  private final URI credentialCallbackUri;

  public GoogleSignInConfiguration(
      @Value("${novel.auth.google.web-client-id:}") String webClientId,
      @Value("${novel.auth.google.credential-callback-uri:}") String credentialCallbackUri,
      @Value("${novel.auth.google.rate-limit-hmac-key:}") String rateLimitHmacKey) {
    this.webClientId = webClientId == null ? "" : webClientId.trim();
    this.rateLimitHmacKey = rateLimitHmacKey == null ? "" : rateLimitHmacKey.trim();
    this.credentialCallbackUri = parseCredentialCallbackUri(credentialCallbackUri);
  }

  public boolean isReadyForSignIn() {
    return isPlausibleWebClientId()
        && credentialCallbackUri != null
        && GoogleAuthRateLimitKeyHasher.isValidKey(rateLimitHmacKey);
  }

  private boolean isPlausibleWebClientId() {
    return webClientId.length() > GOOGLE_CLIENT_ID_SUFFIX.length()
        && webClientId.endsWith(GOOGLE_CLIENT_ID_SUFFIX)
        && webClientId.chars().noneMatch(Character::isWhitespace);
  }

  public String webClientId() {
    return webClientId;
  }

  public String rateLimitHmacKey() {
    return rateLimitHmacKey;
  }

  public URI credentialCallbackUri() {
    return credentialCallbackUri;
  }

  private static URI parseCredentialCallbackUri(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return null;
    }
    try {
      URI uri = new URI(rawValue.trim());
      boolean secure = "https".equalsIgnoreCase(uri.getScheme());
      boolean localDevelopment =
          "http".equalsIgnoreCase(uri.getScheme()) && "localhost".equalsIgnoreCase(uri.getHost());
      if ((!secure && !localDevelopment)
          || !uri.isAbsolute()
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || !CREDENTIAL_CALLBACK_PATH.equals(uri.getRawPath())) {
        return null;
      }
      return uri;
    } catch (URISyntaxException exception) {
      return null;
    }
  }
}
