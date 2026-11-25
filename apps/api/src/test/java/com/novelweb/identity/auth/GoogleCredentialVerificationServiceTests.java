package com.novelweb.identity.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoogleCredentialVerificationServiceTests {

  private static final String ONE_TIME_STATE = "A".repeat(43);

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
  void verifiesFirebaseTokenAndConsumesOnlyTheSubmittedOneTimeState() {
    GoogleIdTokenVerifier.VerifiedGoogleIdentity identity =
        new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
            "https://securetoken.google.com/novel-test-project",
            "subject-1",
            "reader@example.com",
            "Reader");
    UUID correlationId = UUID.randomUUID();
    when(idTokenVerifier.verify("signed-token")).thenReturn(identity);
    when(authAttemptService.consumeOnce(ONE_TIME_STATE))
        .thenReturn(
            Optional.of(new GoogleAuthAttemptService.ConsumedAttempt("/library", correlationId)));

    GoogleCredentialVerificationService.VerifiedGoogleSignIn verified =
        service.verify("signed-token", ONE_TIME_STATE);

    assertEquals(identity, verified.identity());
    assertEquals("/library", verified.returnTo());
    assertEquals(correlationId, verified.correlationId());
    verify(authAttemptService).consumeOnce(ONE_TIME_STATE);
  }

  @Test
  void rejectsReplayedOrExpiredAttemptAfterTokenValidation() {
    when(idTokenVerifier.verify("signed-token"))
        .thenReturn(
            new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
                "https://securetoken.google.com/novel-test-project",
                "subject-1",
                "reader@example.com",
                null));
    when(authAttemptService.consumeOnce(ONE_TIME_STATE)).thenReturn(Optional.empty());

    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> service.verify("signed-token", ONE_TIME_STATE));
  }

  @Test
  void doesNotConsumeAttemptWhenCredentialSignatureOrClaimsAreInvalid() {
    when(idTokenVerifier.verify("signed-token")).thenThrow(new InvalidGoogleCredentialException());

    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> service.verify("signed-token", ONE_TIME_STATE));

    verify(authAttemptService, never()).consumeOnce(ONE_TIME_STATE);
  }
}
