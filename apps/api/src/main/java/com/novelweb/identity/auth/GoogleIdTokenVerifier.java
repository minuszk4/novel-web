package com.novelweb.identity.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class GoogleIdTokenVerifier {

  private static final Duration ISSUED_AT_FUTURE_SKEW = Duration.ofSeconds(60);
  private static final int MAX_CREDENTIAL_LENGTH = 16_384;
  private static final int MAX_SUBJECT_LENGTH = 255;
  private static final int MAX_EMAIL_LENGTH = 320;
  private static final int MAX_NAME_LENGTH = 200;

  private final JwtDecoder jwtDecoder;
  private final String firebaseProjectId;
  private final Clock clock;

  @Autowired
  public GoogleIdTokenVerifier(
      JwtDecoder jwtDecoder,
      @Value("${novel.auth.google.firebase-project-id:}") String firebaseProjectId) {
    this(jwtDecoder, firebaseProjectId, Clock.systemUTC());
  }

  GoogleIdTokenVerifier(JwtDecoder jwtDecoder, String firebaseProjectId, Clock clock) {
    this.jwtDecoder = jwtDecoder;
    this.firebaseProjectId = firebaseProjectId == null ? "" : firebaseProjectId.trim();
    this.clock = clock;
  }

  public VerifiedGoogleIdentity verify(String credential) {
    if (firebaseProjectId.isBlank()
        || credential == null
        || credential.isBlank()
        || credential.length() > MAX_CREDENTIAL_LENGTH) {
      throw new InvalidGoogleCredentialException();
    }

    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(credential);
    } catch (JwtException exception) {
      throw new InvalidGoogleCredentialException(exception);
    }

    Map<String, Object> claims = jwt.getClaims();
    String canonicalIssuer = canonicalIssuer(claims.get("iss"));
    validateAudience(jwt.getAudience());

    Instant now = clock.instant();
    Instant issuedAt = jwt.getIssuedAt();
    Instant expiresAt = jwt.getExpiresAt();
    if (issuedAt == null
        || expiresAt == null
        || issuedAt.isAfter(now.plus(ISSUED_AT_FUTURE_SKEW))
        || !expiresAt.isAfter(now)
        || !expiresAt.isAfter(issuedAt)) {
      throw new InvalidGoogleCredentialException();
    }

    String subject = boundedString(claims.get("sub"), MAX_SUBJECT_LENGTH);
    String email = boundedString(claims.get("email"), MAX_EMAIL_LENGTH);
    String displayName = optionalBoundedString(claims.get("name"), MAX_NAME_LENGTH);
    Object emailVerified = claims.get("email_verified");
    Instant authTime = epochSeconds(claims.get("auth_time"));
    Object firebaseClaim = claims.get("firebase");
    String signInProvider =
        firebaseClaim instanceof Map<?, ?> firebase
            ? optionalBoundedString(firebase.get("sign_in_provider"), 64)
            : null;
    if (subject == null
        || email == null
        || authTime == null
        || !authTime.isBefore(now)
        || !"google.com".equals(signInProvider)
        || !Boolean.TRUE.equals(emailVerified)) {
      throw new InvalidGoogleCredentialException();
    }

    return new VerifiedGoogleIdentity(canonicalIssuer, subject, email, displayName);
  }

  private void validateAudience(List<String> audiences) {
    if (audiences == null || audiences.size() != 1 || !audiences.contains(firebaseProjectId)) {
      throw new InvalidGoogleCredentialException();
    }
  }

  private String canonicalIssuer(Object issuer) {
    String expected = "https://securetoken.google.com/" + firebaseProjectId;
    if (expected.equals(issuer)) {
      return expected;
    }
    throw new InvalidGoogleCredentialException();
  }

  private static Instant epochSeconds(Object value) {
    if (!(value instanceof Number number)) {
      return null;
    }
    try {
      return Instant.ofEpochSecond(number.longValue());
    } catch (ArithmeticException | java.time.DateTimeException exception) {
      return null;
    }
  }

  private static String boundedString(Object claim, int maxLength) {
    String value = optionalBoundedString(claim, maxLength);
    return value == null || value.isBlank() ? null : value;
  }

  private static String optionalBoundedString(Object claim, int maxLength) {
    if (claim == null) {
      return null;
    }
    if (!(claim instanceof String value) || value.length() > maxLength) {
      throw new InvalidGoogleCredentialException();
    }
    return value;
  }

  public static final class VerifiedGoogleIdentity {

    private final String issuer;
    private final String subject;
    private final String email;
    private final String displayName;

    VerifiedGoogleIdentity(String issuer, String subject, String email, String displayName) {
      this.issuer = issuer;
      this.subject = subject;
      this.email = email;
      this.displayName = displayName;
    }

    public String issuer() {
      return issuer;
    }

    public String subject() {
      return subject;
    }

    public String email() {
      return email;
    }

    public String displayName() {
      return displayName;
    }
  }
}
