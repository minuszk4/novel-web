package com.novelweb.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class GoogleAuthAttemptService {

  private static final int NONCE_BYTES = 32;
  private static final Duration MAXIMUM_ATTEMPT_TTL = Duration.ofMinutes(15);
  private static final Pattern NONCE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

  private final DataSource dataSource;
  private final Duration attemptTtl;
  private final SecureRandom secureRandom = new SecureRandom();

  public GoogleAuthAttemptService(
      DataSource dataSource, @Value("${novel.auth.google.attempt-ttl:5m}") Duration attemptTtl) {
    this.dataSource = dataSource;
    if (attemptTtl.isZero()
        || attemptTtl.isNegative()
        || attemptTtl.compareTo(MAXIMUM_ATTEMPT_TTL) > 0
        || attemptTtl.toMillis() == 0) {
      throw new IllegalArgumentException(
          "Google auth attempt TTL must be positive and at most 15 minutes");
    }
    this.attemptTtl = attemptTtl;
  }

  public CreatedAttempt create(String returnTo, UUID correlationId) {
    Objects.requireNonNull(correlationId, "correlationId is required");

    byte[] nonceBytes = new byte[NONCE_BYTES];
    secureRandom.nextBytes(nonceBytes);
    String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
    String safeReturnTo = SafeReturnPath.sanitize(returnTo);
    Instant expiresAt;

    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                INSERT INTO google_auth_attempts (id, nonce_hash, safe_return_path, expires_at, correlation_id)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), ?)
                RETURNING expires_at
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setBytes(2, sha256(nonce));
      insert.setString(3, safeReturnTo);
      insert.setLong(4, attemptTtl.toMillis());
      insert.setObject(5, correlationId);
      try (ResultSet inserted = insert.executeQuery()) {
        if (!inserted.next()) {
          throw new SQLException("Auth attempt insert returned no expiry");
        }
        OffsetDateTime databaseExpiry = inserted.getObject("expires_at", OffsetDateTime.class);
        expiresAt = databaseExpiry.toInstant();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to create a Google sign-in attempt", exception);
    }

    return new CreatedAttempt(nonce, safeReturnTo, expiresAt, correlationId);
  }

  public Optional<ConsumedAttempt> consumeOnce(String nonce) {
    if (!isGeneratedNonce(nonce)) {
      return Optional.empty();
    }

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<ConsumedAttempt> consumedAttempt;
        try (PreparedStatement consume =
            connection.prepareStatement(
                """
                        UPDATE google_auth_attempts
                        SET consumed_at = clock_timestamp()
                        WHERE nonce_hash = ?
                          AND consumed_at IS NULL
                          AND expires_at > clock_timestamp()
                        RETURNING safe_return_path, correlation_id
                        """)) {
          consume.setBytes(1, sha256(nonce));
          try (ResultSet consumed = consume.executeQuery()) {
            consumedAttempt =
                consumed.next()
                    ? Optional.of(
                        new ConsumedAttempt(
                            consumed.getString("safe_return_path"),
                            consumed.getObject("correlation_id", UUID.class)))
                    : Optional.empty();
          }
        }
        connection.commit();
        return consumedAttempt;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to consume a Google sign-in attempt", exception);
    }
  }

  private static boolean isGeneratedNonce(String nonce) {
    if (nonce == null || !NONCE_PATTERN.matcher(nonce).matches()) {
      return false;
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(nonce);
      return decoded.length == NONCE_BYTES
          && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(nonce);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record CreatedAttempt(
      String nonce, String returnTo, Instant expiresAt, UUID correlationId) {}

  public record ConsumedAttempt(String returnTo, UUID correlationId) {}
}
