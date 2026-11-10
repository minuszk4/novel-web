package com.novelweb.identity.auth;

public final class GoogleIdentityTestFixtures {

  private GoogleIdentityTestFixtures() {}

  public static GoogleIdTokenVerifier.VerifiedGoogleIdentity verifiedIdentity(
      String subject, String email, String displayName) {
    return new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
        "https://accounts.google.com", subject, email, displayName, "synthetic-nonce");
  }
}
