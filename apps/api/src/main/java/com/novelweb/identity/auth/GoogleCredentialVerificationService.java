package com.novelweb.identity.auth;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class GoogleCredentialVerificationService {

  private final GoogleIdTokenVerifier idTokenVerifier;
  private final GoogleAuthAttemptService authAttemptService;

  public GoogleCredentialVerificationService(
      GoogleIdTokenVerifier idTokenVerifier, GoogleAuthAttemptService authAttemptService) {
    this.idTokenVerifier = idTokenVerifier;
    this.authAttemptService = authAttemptService;
  }

  public VerifiedGoogleSignIn verify(String idToken, String state) {
    if (!GoogleAuthAttemptService.isGeneratedNonce(state)) {
      throw new InvalidGoogleCredentialException();
    }
    GoogleIdTokenVerifier.VerifiedGoogleIdentity identity = idTokenVerifier.verify(idToken);
    GoogleAuthAttemptService.ConsumedAttempt attempt =
        authAttemptService.consumeOnce(state).orElseThrow(InvalidGoogleCredentialException::new);
    return new VerifiedGoogleSignIn(identity, attempt.returnTo(), attempt.correlationId());
  }

  public record VerifiedGoogleSignIn(
      GoogleIdTokenVerifier.VerifiedGoogleIdentity identity, String returnTo, UUID correlationId) {}
}
