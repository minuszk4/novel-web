package com.novelweb.identity.auth;

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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleIdTokenVerifierTests {

    private static final String CLIENT_ID = "novel-web-test.apps.googleusercontent.com";
    private static final String NONCE = "test-nonce-32-characters-long-0001";
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
        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.getPublic()).build();
        verifier = new GoogleIdTokenVerifier(decoder, CLIENT_ID, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void acceptsGoogleSignedClaimsAndReturnsOnlyNeededIdentityFields() throws Exception {
        GoogleIdTokenVerifier.VerifiedGoogleIdentity identity = verifier.verify(signedToken(validClaims(), signingKey));

        assertEquals("google-subject-123", identity.subject());
        assertEquals("reader@example.com", identity.email());
        assertEquals("Novel Reader", identity.displayName());
        assertEquals(NONCE, identity.nonce());
    }

    @Test
    void acceptsEitherDocumentedGoogleIssuer() throws Exception {
        Map<String, Object> claims = validClaims();
        claims.put("iss", "accounts.google.com");

        assertEquals("google-subject-123", verifier.verify(signedToken(claims, signingKey)).subject());
    }

    @Test
    void rejectsSignatureNotMadeByGoogleTrustedKey() throws Exception {
        assertInvalid(signedToken(validClaims(), otherSigningKey));
    }

    @Test
    void rejectsUntrustedIssuerOrAudience() throws Exception {
        Map<String, Object> wrongIssuer = validClaims();
        wrongIssuer.put("iss", "https://attacker.example");
        assertInvalid(signedToken(wrongIssuer, signingKey));

        Map<String, Object> wrongAudience = validClaims();
        wrongAudience.put("aud", List.of("another-client.apps.googleusercontent.com"));
        assertInvalid(signedToken(wrongAudience, signingKey));
    }

    @Test
    void requiresAuthorizedPartyWhenTokenHasMultipleAudiencesAndRejectsMismatch() throws Exception {
        Map<String, Object> noAuthorizedParty = validClaims();
        noAuthorizedParty.put("aud", List.of(CLIENT_ID, "another-client.apps.googleusercontent.com"));
        assertInvalid(signedToken(noAuthorizedParty, signingKey));

        Map<String, Object> wrongAuthorizedParty = validClaims();
        wrongAuthorizedParty.put("aud", List.of(CLIENT_ID, "another-client.apps.googleusercontent.com"));
        wrongAuthorizedParty.put("azp", "another-client.apps.googleusercontent.com");
        assertInvalid(signedToken(wrongAuthorizedParty, signingKey));

        Map<String, Object> authorizedParty = validClaims();
        authorizedParty.put("aud", List.of(CLIENT_ID, "another-client.apps.googleusercontent.com"));
        authorizedParty.put("azp", CLIENT_ID);
        assertEquals("google-subject-123", verifier.verify(signedToken(authorizedParty, signingKey)).subject());
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
    }

    @Test
    void requiresVerifiedEmailAndNonce() throws Exception {
        Map<String, Object> unverifiedEmail = validClaims();
        unverifiedEmail.put("email_verified", false);
        assertInvalid(signedToken(unverifiedEmail, signingKey));

        Map<String, Object> stringEmailVerification = validClaims();
        stringEmailVerification.put("email_verified", "true");
        assertInvalid(signedToken(stringEmailVerification, signingKey));

        Map<String, Object> noNonce = validClaims();
        noNonce.remove("nonce");
        assertInvalid(signedToken(noNonce, signingKey));
    }

    @Test
    void failsClosedWhenClientIdIsMissingOrCredentialIsTooLarge() throws Exception {
        GoogleIdTokenVerifier unconfigured = new GoogleIdTokenVerifier(
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) signingKey.getPublic()).build(), " ",
                Clock.fixed(now, ZoneOffset.UTC));
        assertThrows(InvalidGoogleCredentialException.class,
                () -> unconfigured.verify(signedToken(validClaims(), signingKey)));

        assertInvalid("x".repeat(16_385));
    }

    private void assertInvalid(String token) {
        assertThrows(InvalidGoogleCredentialException.class, () -> verifier.verify(token));
    }

    private Map<String, Object> validClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", new ArrayList<>(List.of(CLIENT_ID)));
        claims.put("sub", "google-subject-123");
        claims.put("email", "reader@example.com");
        claims.put("email_verified", true);
        claims.put("name", "Novel Reader");
        claims.put("nonce", NONCE);
        claims.put("iat", Date.from(now));
        claims.put("exp", Date.from(now.plusSeconds(300)));
        return claims;
    }

    private static String signedToken(Map<String, Object> claims, KeyPair keyPair) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        claims.forEach(builder::claim);
        SignedJWT signed = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), builder.build());
        signed.sign(new RSASSASigner(keyPair.getPrivate()));
        return signed.serialize();
    }
}
