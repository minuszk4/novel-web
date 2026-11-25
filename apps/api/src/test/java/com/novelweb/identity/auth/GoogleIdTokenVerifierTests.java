package com.novelweb.identity.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class GoogleIdTokenVerifierTests {

  private static final String PROJECT_ID = "novel-web-test";
  private static final String ISSUER = "https://securetoken.google.com/" + PROJECT_ID;
  private static KeyPair signingKey;
  private static KeyPair otherSigningKey;

  private Instant now;
  private GoogleIdTokenVerifier verifier;

  @BeforeAll
  static void generateSigningKeys() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    signingKey = generator.generateKeyPair();
    otherSigningKey = generator.generateKeyPair();
  }

  @BeforeEach
  void setUp() {
    now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    JwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.getPublic()).build();
    verifier = new GoogleIdTokenVerifier(decoder, PROJECT_ID, Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  void acceptsGoogleProviderFirebaseTokenAndReturnsOnlyNeededIdentityFields() throws Exception {
    GoogleIdTokenVerifier.VerifiedGoogleIdentity identity =
        verifier.verify(signedToken(validClaims(), signingKey));

    assertEquals(ISSUER, identity.issuer());
    assertEquals("firebase-uid-123", identity.subject());
    assertEquals("reader@example.com", identity.email());
    assertEquals("Novel Reader", identity.displayName());
  }

  @Test
  void rejectsSignatureNotMadeByFirebaseTrustedKey() throws Exception {
    assertInvalid(signedToken(validClaims(), otherSigningKey));
  }

  @Test
  void requiresFirebaseIssuerAndExactProjectAudience() throws Exception {
    Map<String, Object> wrongIssuer = validClaims();
    wrongIssuer.put("iss", "https://securetoken.google.com/another-project");
    assertInvalid(signedToken(wrongIssuer, signingKey));

    Map<String, Object> wrongAudience = validClaims();
    wrongAudience.put("aud", List.of("another-project"));
    assertInvalid(signedToken(wrongAudience, signingKey));

    Map<String, Object> multipleAudiences = validClaims();
    multipleAudiences.put("aud", List.of(PROJECT_ID, "another-project"));
    assertInvalid(signedToken(multipleAudiences, signingKey));
  }

  @Test
  void rejectsExpiredFutureIssuedOrStructurallyInvalidToken() throws Exception {
    Map<String, Object> expired = validClaims();
    expired.put("exp", Date.from(now.minusSeconds(1)));
    assertInvalid(signedToken(expired, signingKey));

    Map<String, Object> issuedInFuture = validClaims();
    issuedInFuture.put("iat", Date.from(now.plusSeconds(61)));
    assertInvalid(signedToken(issuedInFuture, signingKey));

    Map<String, Object> noSubject = validClaims();
    noSubject.remove("sub");
    assertInvalid(signedToken(noSubject, signingKey));

    Map<String, Object> noAuthTime = validClaims();
    noAuthTime.remove("auth_time");
    assertInvalid(signedToken(noAuthTime, signingKey));

    Map<String, Object> futureAuthTime = validClaims();
    futureAuthTime.put("auth_time", now.plusSeconds(1).getEpochSecond());
    assertInvalid(signedToken(futureAuthTime, signingKey));
  }

  @Test
  void requiresVerifiedEmailAndGoogleProvider() throws Exception {
    Map<String, Object> unverifiedEmail = validClaims();
    unverifiedEmail.put("email_verified", false);
    assertInvalid(signedToken(unverifiedEmail, signingKey));

    Map<String, Object> stringEmailVerification = validClaims();
    stringEmailVerification.put("email_verified", "true");
    assertInvalid(signedToken(stringEmailVerification, signingKey));

    Map<String, Object> otherProvider = validClaims();
    otherProvider.put("firebase", Map.of("sign_in_provider", "password"));
    assertInvalid(signedToken(otherProvider, signingKey));

    Map<String, Object> missingProvider = validClaims();
    missingProvider.remove("firebase");
    assertInvalid(signedToken(missingProvider, signingKey));
  }

  @Test
  void failsClosedWhenProjectIdIsMissingOrCredentialIsTooLarge() throws Exception {
    GoogleIdTokenVerifier unconfigured =
        new GoogleIdTokenVerifier(
            NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.getPublic()).build(),
            " ",
            Clock.fixed(now, ZoneOffset.UTC));
    assertThrows(
        InvalidGoogleCredentialException.class,
        () -> unconfigured.verify(signedToken(validClaims(), signingKey)));

    assertInvalid("x".repeat(16_385));
  }

  private void assertInvalid(String token) {
    assertThrows(InvalidGoogleCredentialException.class, () -> verifier.verify(token));
  }

  private Map<String, Object> validClaims() {
    Map<String, Object> claims = new HashMap<>();
    claims.put("iss", ISSUER);
    claims.put("aud", new ArrayList<>(List.of(PROJECT_ID)));
    claims.put("sub", "firebase-uid-123");
    claims.put("email", "reader@example.com");
    claims.put("email_verified", true);
    claims.put("name", "Novel Reader");
    claims.put("auth_time", now.minusSeconds(5).getEpochSecond());
    claims.put("firebase", Map.of("sign_in_provider", "google.com"));
    claims.put("iat", Date.from(now));
    claims.put("exp", Date.from(now.plusSeconds(300)));
    return claims;
  }

  private static String signedToken(Map<String, Object> claims, KeyPair keyPair) throws Exception {
    JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
    claims.forEach(builder::claim);
    SignedJWT signed =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), builder.build());
    signed.sign(new RSASSASigner(keyPair.getPrivate()));
    return signed.serialize();
  }
}
