package com.novelweb.identity.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class GoogleSignInConfigurationTests {

  private static final String API_KEY = "firebase-test-key-public-web-api-key-not-a-credential";
  private static final String AUTH_DOMAIN = "novel-web.firebaseapp.com";
  private static final String PROJECT_ID = "novel-web";
  private static final String APP_ID = "1:921742618964:web:9790fb7f701242a6d258c1";
  private static final String RATE_LIMIT_KEY =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

  @Test
  void isReadyWhenFirebaseWebConfigAndRateLimitKeyAreConfigured() {
    GoogleSignInConfiguration configuration =
        new GoogleSignInConfiguration(API_KEY, AUTH_DOMAIN, PROJECT_ID, APP_ID, RATE_LIMIT_KEY);

    assertTrue(configuration.isReadyForSignIn());
  }

  @Test
  void isNotReadyWhenAnyRequiredSettingIsMissingOrInvalid() {
    assertFalse(
        new GoogleSignInConfiguration("", AUTH_DOMAIN, PROJECT_ID, APP_ID, RATE_LIMIT_KEY)
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(
                API_KEY, "https://novel-web.firebaseapp.com", PROJECT_ID, APP_ID, RATE_LIMIT_KEY)
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(
                API_KEY, AUTH_DOMAIN, "Invalid_Project", APP_ID, RATE_LIMIT_KEY)
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(API_KEY, AUTH_DOMAIN, PROJECT_ID, "", RATE_LIMIT_KEY)
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(API_KEY, AUTH_DOMAIN, PROJECT_ID, APP_ID, "not-a-valid-key")
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(
                API_KEY,
                AUTH_DOMAIN,
                PROJECT_ID,
                APP_ID,
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31]))
            .isReadyForSignIn());
  }
}
