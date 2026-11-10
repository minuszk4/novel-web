package com.novelweb.identity.auth;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleCredentialVerificationServiceTests {

    private static final String CSRF_TOKEN = "gis-csrf-test-token";

    private GoogleIdTokenVerifier idTokenVerifier;
    private GoogleAuthAttemptService authAttemptService;
    private GoogleCredentialVerificationService service;

    @BeforeEach
    void setUp() {
        idTokenVerifier = mock(GoogleIdTokenVerifier.class);
        authAttemptService = mock(GoogleAuthAttemptService.class);
        service = new GoogleCredentialVerificationService(idTokenVerifier, authAttemptService);
    }

    @Test
    void validatesCsrfBeforeCredentialAndConsumesOnlyTheVerifiedNonce() {
        GoogleIdTokenVerifier.VerifiedGoogleIdentity identity = new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
                "https://accounts.google.com", "subject-1", "reader@example.com", "Reader", "nonce-1");
        UUID correlationId = UUID.randomUUID();
        when(idTokenVerifier.verify("signed-token")).thenReturn(identity);
        when(authAttemptService.consumeOnce("nonce-1"))
                .thenReturn(Optional.of(new GoogleAuthAttemptService.ConsumedAttempt("/library", correlationId)));

        GoogleCredentialVerificationService.VerifiedGoogleSignIn verified = service.verify(
                "signed-token", CSRF_TOKEN, CSRF_TOKEN);

        assertEquals(identity, verified.identity());
        assertEquals("/library", verified.returnTo());
        assertEquals(correlationId, verified.correlationId());
        verify(authAttemptService).consumeOnce("nonce-1");
    }

    @Test
    void rejectsCsrfMismatchBeforeDecodingTokenOrConsumingAttempt() {
        assertThrows(InvalidGoogleCredentialException.class,
                () -> service.verify("signed-token", "cookie-csrf", "body-csrf"));

        verify(idTokenVerifier, never()).verify("signed-token");
        verify(authAttemptService, never()).consumeOnce("nonce-1");
    }

    @Test
    void rejectsReplayedOrExpiredAttemptAfterTokenValidation() {
        when(idTokenVerifier.verify("signed-token")).thenReturn(
                new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
                        "https://accounts.google.com", "subject-1", "reader@example.com", null, "nonce-1"));
        when(authAttemptService.consumeOnce("nonce-1")).thenReturn(Optional.empty());

        assertThrows(InvalidGoogleCredentialException.class,
                () -> service.verify("signed-token", CSRF_TOKEN, CSRF_TOKEN));
    }

    @Test
    void doesNotConsumeAttemptWhenCredentialSignatureOrClaimsAreInvalid() {
        when(idTokenVerifier.verify("signed-token")).thenThrow(new InvalidGoogleCredentialException());

        assertThrows(InvalidGoogleCredentialException.class,
                () -> service.verify("signed-token", CSRF_TOKEN, CSRF_TOKEN));

        verify(authAttemptService, never()).consumeOnce("nonce-1");
    }
}
