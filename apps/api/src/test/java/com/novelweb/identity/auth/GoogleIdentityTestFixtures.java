package com.novelweb.identity.auth;

public final class GoogleIdentityTestFixtures {

  private GoogleIdentityTestFixtures() {}

  public static GoogleIdTokenVerifier.VerifiedGoogleIdentity verifiedIdentity(
      String subject, String email, String displayName) {
    return verifiedIdentity(
        "https://securetoken.google.com/novel-test-project", subject, email, displayName);
  }

  public static GoogleIdTokenVerifier.VerifiedGoogleIdentity verifiedIdentity(
      String issuer, String subject, String email, String displayName) {
    return new GoogleIdTokenVerifier.VerifiedGoogleIdentity(issuer, subject, email, displayName);
  }
}
