package com.novelweb.identity.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleCsrfTokenValidatorTests {

    @Test
    void acceptsOnlyTheExactNonblankCookieAndFormValue() {
        assertDoesNotThrow(() -> GoogleCsrfTokenValidator.requireMatchingTokens("csrf-random-value", "csrf-random-value"));
        assertThrows(InvalidGoogleCredentialException.class,
                () -> GoogleCsrfTokenValidator.requireMatchingTokens("cookie-value", "body-value"));
    }

    @Test
    void rejectsMissingBlankAndOversizedTokens() {
        assertThrows(InvalidGoogleCredentialException.class,
                () -> GoogleCsrfTokenValidator.requireMatchingTokens(null, "body-value"));
        assertThrows(InvalidGoogleCredentialException.class,
                () -> GoogleCsrfTokenValidator.requireMatchingTokens(" ", " "));
        assertThrows(InvalidGoogleCredentialException.class,
                () -> GoogleCsrfTokenValidator.requireMatchingTokens("x".repeat(513), "x".repeat(513)));
    }
}
