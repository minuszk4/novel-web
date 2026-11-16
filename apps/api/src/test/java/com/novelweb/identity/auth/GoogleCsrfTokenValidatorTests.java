package com.novelweb.identity.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GoogleCsrfTokenValidatorTests {

  @Test
  void acceptsOnlyTheExactNonblankCookieAndFormValue() {
    assertDoesNotThrow(
        () ->
            GoogleCsrfTokenValidator.requireMatchingTokens(
                "csrf-random-value", "csrf-random-value"));
    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> GoogleCsrfTokenValidator.requireMatchingTokens("cookie-value", "body-value"));
  }

  @Test
  void acceptsTokensAtTheMaximumLength() {
    String maximumLengthToken = "x".repeat(512);
    assertDoesNotThrow(
        () ->
            GoogleCsrfTokenValidator.requireMatchingTokens(maximumLengthToken, maximumLengthToken));
  }

  @Test
  void rejectsMissingBlankAndOversizedTokens() {
    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> GoogleCsrfTokenValidator.requireMatchingTokens(null, "body-value"));
    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> GoogleCsrfTokenValidator.requireMatchingTokens("cookie-value", null));
    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> GoogleCsrfTokenValidator.requireMatchingTokens(" ", "body-value"));
    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> GoogleCsrfTokenValidator.requireMatchingTokens("cookie-value", " "));
    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> GoogleCsrfTokenValidator.requireMatchingTokens("x".repeat(513), "body-value"));
    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> GoogleCsrfTokenValidator.requireMatchingTokens("cookie-value", "x".repeat(513)));
  }
}
