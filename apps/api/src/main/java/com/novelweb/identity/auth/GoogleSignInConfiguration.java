package com.novelweb.identity.auth;

import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Public Firebase web configuration and server-side Google sign-in readiness checks. */
@Component
public class GoogleSignInConfiguration {

  private static final Pattern PROJECT_ID = Pattern.compile("[a-z0-9-]{1,30}");
  private static final Pattern AUTH_DOMAIN = Pattern.compile("[A-Za-z0-9.-]{1,253}");

  private final String apiKey;
  private final String authDomain;
  private final String projectId;
  private final String appId;
  private final String rateLimitHmacKey;

  public GoogleSignInConfiguration(
      @Value("${novel.auth.google.firebase-api-key:}") String apiKey,
      @Value("${novel.auth.google.firebase-auth-domain:}") String authDomain,
      @Value("${novel.auth.google.firebase-project-id:}") String projectId,
      @Value("${novel.auth.google.firebase-app-id:}") String appId,
      @Value("${novel.auth.google.rate-limit-hmac-key:}") String rateLimitHmacKey) {
    this.apiKey = normalized(apiKey);
    this.authDomain = normalized(authDomain);
    this.projectId = normalized(projectId);
    this.appId = normalized(appId);
    this.rateLimitHmacKey = normalized(rateLimitHmacKey);
  }

  public boolean isReadyForSignIn() {
    return apiKey.length() >= 20
        && apiKey.chars().noneMatch(Character::isWhitespace)
        && AUTH_DOMAIN.matcher(authDomain).matches()
        && PROJECT_ID.matcher(projectId).matches()
        && appId.length() >= 10
        && appId.chars().noneMatch(Character::isWhitespace)
        && GoogleAuthRateLimitKeyHasher.isValidKey(rateLimitHmacKey);
  }

  public String apiKey() {
    return apiKey;
  }

  public String authDomain() {
    return authDomain;
  }

  public String projectId() {
    return projectId;
  }

  public String appId() {
    return appId;
  }

  public String rateLimitHmacKey() {
    return rateLimitHmacKey;
  }

  private static String normalized(String value) {
    return value == null ? "" : value.trim();
  }
}
