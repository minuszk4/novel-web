package com.novelweb.identity.session;

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
public class LogoutAllConfirmationService {

  private static final int CONFIRMATION_BYTES = 32;
  private static final Duration MAXIMUM_CONFIRMATION_TTL = Duration.ofMinutes(15);
  private static final Pattern CONFIRMATION_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");

  private final DataSource dataSource;
  private final Duration confirmationTtl;
  private final Duration idleTtl;
  private final SecureRandom secureRandom = new SecureRandom();

  public LogoutAllConfirmationService(
      DataSource dataSource,
      @Value("${novel.identity.logout-all.confirmation-ttl:5m}") Duration confirmationTtl,
      @Value("${novel.identity.session.idle-ttl:7d}") Duration idleTtl) {
    this.dataSource = dataSource;
    if (confirmationTtl.isZero()
        || confirmationTtl.isNegative()
        || confirmationTtl.compareTo(MAXIMUM_CONFIRMATION_TTL) > 0
        || idleTtl.isZero()
        || idleTtl.isNegative()) {
      throw new IllegalArgumentException(
          "Logout-all confirmation and session idle TTLs must be positive and bounded");
    }
    this.confirmationTtl = confirmationTtl;
    this.idleTtl = idleTtl;
  }

  public Optional<IssuedConfirmation> issue(UUID userId, String sessionToken) {
    Objects.requireNonNull(userId, "user id is required");
    if (!SessionService.isCanonicalSessionToken(sessionToken)) {
      return Optional.empty();
    }

    byte[] sessionHash = SessionService.hashSessionToken(sessionToken);
    String confirmationToken = newConfirmationToken();
    byte[] confirmationHash = hashConfirmationToken(confirmationToken);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockActiveSession(connection, userId, sessionHash)) {
          connection.commit();
          return Optional.empty();
        }

        try (PreparedStatement cleanup =
            connection.prepareStatement(
                """
                        DELETE FROM session_revoke_confirmations
                        WHERE consumed_at IS NOT NULL AND consumed_at < clock_timestamp() - INTERVAL '1 day'
                           OR expires_at < clock_timestamp() - INTERVAL '1 day'
                        """)) {
          cleanup.executeUpdate();
        }
        try (PreparedStatement replace =
            connection.prepareStatement(
                """
                        DELETE FROM session_revoke_confirmations
                        WHERE session_id_hash = ? AND consumed_at IS NULL
                        """)) {
          replace.setBytes(1, sessionHash);
          replace.executeUpdate();
        }

        Instant expiresAt;
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                        INSERT INTO session_revoke_confirmations (token_hash, user_id, session_id_hash, expires_at)
                        VALUES (?, ?, ?, clock_timestamp() + (? * INTERVAL '1 millisecond'))
                        RETURNING expires_at
                        """)) {
          insert.setBytes(1, confirmationHash);
          insert.setObject(2, userId);
          insert.setBytes(3, sessionHash);
          insert.setLong(4, confirmationTtl.toMillis());
          try (ResultSet row = insert.executeQuery()) {
            if (!row.next()) {
              throw new SQLException("Logout-all confirmation did not return an expiry");
            }
            expiresAt = row.getObject("expires_at", OffsetDateTime.class).toInstant();
          }
        }

        connection.commit();
        return Optional.of(new IssuedConfirmation(confirmationToken, expiresAt));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to issue a logout-all confirmation", exception);
    }
  }

  public boolean consumeAndRevokeAll(UUID userId, String sessionToken, String confirmationToken) {
    Objects.requireNonNull(userId, "user id is required");
    if (!SessionService.isCanonicalSessionToken(sessionToken)
        || !isCanonicalConfirmationToken(confirmationToken)) {
      return false;
    }

    byte[] sessionHash = SessionService.hashSessionToken(sessionToken);
    byte[] confirmationHash = hashConfirmationToken(confirmationToken);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockMatchingConfirmation(connection, userId, sessionHash, confirmationHash)) {
          connection.commit();
          return false;
        }

        try (PreparedStatement consume =
            connection.prepareStatement(
                """
                        UPDATE session_revoke_confirmations
                        SET consumed_at = clock_timestamp()
                        WHERE token_hash = ? AND user_id = ? AND session_id_hash = ?
                          AND consumed_at IS NULL AND expires_at > clock_timestamp()
                        """)) {
          consume.setBytes(1, confirmationHash);
          consume.setObject(2, userId);
          consume.setBytes(3, sessionHash);
          if (consume.executeUpdate() != 1) {
            connection.commit();
            return false;
          }
        }

        try (PreparedStatement revoke =
            connection.prepareStatement(
                """
                        UPDATE sessions
                        SET revoked_at = clock_timestamp()
                        WHERE user_id = ? AND revoked_at IS NULL
                        """)) {
          revoke.setObject(1, userId);
          if (revoke.executeUpdate() == 0) {
            throw new SQLException("Logout-all confirmation did not revoke any active session");
          }
        }

        connection.commit();
        return true;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to revoke all application sessions", exception);
    }
  }

  private boolean lockActiveSession(Connection connection, UUID userId, byte[] sessionHash)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT s.user_id
                FROM sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.id_hash = ? AND s.user_id = ?
                  AND s.revoked_at IS NULL
                  AND s.expires_at > clock_timestamp()
                  AND s.last_seen_at > clock_timestamp() - (? * INTERVAL '1 millisecond')
                  AND u.status = 'active'
                FOR UPDATE OF s FOR SHARE OF u
                """)) {
      select.setBytes(1, sessionHash);
      select.setObject(2, userId);
      select.setLong(3, idleTtl.toMillis());
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private boolean lockMatchingConfirmation(
      Connection connection, UUID userId, byte[] sessionHash, byte[] confirmationHash)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT c.user_id
                FROM session_revoke_confirmations c
                JOIN sessions s ON s.id_hash = c.session_id_hash AND s.user_id = c.user_id
                JOIN users u ON u.id = c.user_id
                WHERE c.token_hash = ? AND c.user_id = ? AND c.session_id_hash = ?
                  AND c.consumed_at IS NULL AND c.expires_at > clock_timestamp()
                  AND s.revoked_at IS NULL
                  AND s.expires_at > clock_timestamp()
                  AND s.last_seen_at > clock_timestamp() - (? * INTERVAL '1 millisecond')
                  AND u.status = 'active'
                FOR UPDATE OF c, s, u
                """)) {
      select.setBytes(1, confirmationHash);
      select.setObject(2, userId);
      select.setBytes(3, sessionHash);
      select.setLong(4, idleTtl.toMillis());
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private String newConfirmationToken() {
    byte[] secret = new byte[CONFIRMATION_BYTES];
    secureRandom.nextBytes(secret);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
  }

  private static boolean isCanonicalConfirmationToken(String token) {
    if (token == null || !CONFIRMATION_PATTERN.matcher(token).matches()) {
      return false;
    }
    try {
      byte[] secret = Base64.getUrlDecoder().decode(token);
      return secret.length == CONFIRMATION_BYTES
          && Base64.getUrlEncoder().withoutPadding().encodeToString(secret).equals(token);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static byte[] hashConfirmationToken(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(token));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void rollback(Connection connection, Exception originalException) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      originalException.addSuppressed(rollbackException);
    }
  }

  public record IssuedConfirmation(String token, Instant expiresAt) {}
}
