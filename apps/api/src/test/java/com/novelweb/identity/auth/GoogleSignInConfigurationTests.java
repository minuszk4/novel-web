package com.novelweb.identity.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class GoogleSignInConfigurationTests {

  private static final String CLIENT_ID = "1234567890-example.apps.googleusercontent.com";
  private static final String CALLBACK_URI =
      "https://api.example.test/api/v1/auth/google/credential";
  private static final String RATE_LIMIT_KEY =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

  @Test
  void isReadyWhenAllGoogleSignInPrerequisitesAreConfigured() {
    GoogleSignInConfiguration configuration =
        new GoogleSignInConfiguration(CLIENT_ID, CALLBACK_URI, RATE_LIMIT_KEY);

    assertTrue(configuration.isReadyForSignIn());
  }

  @Test
  void isNotReadyWhenAnyRequiredSettingIsMissingOrInvalid() {
    assertFalse(new GoogleSignInConfiguration("", CALLBACK_URI, RATE_LIMIT_KEY).isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration("placeholder", CALLBACK_URI, RATE_LIMIT_KEY)
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(".apps.googleusercontent.com", CALLBACK_URI, RATE_LIMIT_KEY)
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(
                CLIENT_ID, "http://api.example.test/api/v1/auth/google/credential", RATE_LIMIT_KEY)
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(
                CLIENT_ID, "https://api.example.test/wrong-path", RATE_LIMIT_KEY)
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(CLIENT_ID, CALLBACK_URI, "not-a-valid-key")
            .isReadyForSignIn());
    assertFalse(
        new GoogleSignInConfiguration(
                CLIENT_ID,
                CALLBACK_URI,
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31]))
            .isReadyForSignIn());
  }

  @Test
  void permitsHttpCallbackOnlyForLocalhostDevelopment() {
    GoogleSignInConfiguration configuration =
        new GoogleSignInConfiguration(
            CLIENT_ID, "http://localhost:8080/api/v1/auth/google/credential", RATE_LIMIT_KEY);

    assertTrue(configuration.isReadyForSignIn());
  }
}
