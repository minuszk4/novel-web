package com.novelweb.identity.auth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class GoogleAuthBootstrapRateLimiter {

  private static final BigDecimal BUCKET_CAPACITY = BigDecimal.TEN;
  private static final BigDecimal ONE_TOKEN = BigDecimal.ONE;
  private static final int TOKEN_SCALE = 6;
  private static final Duration INACTIVE_RETENTION = Duration.ofHours(24);

  private final DataSource dataSource;
  private final Duration refillInterval;

  public GoogleAuthBootstrapRateLimiter(
      DataSource dataSource,
      @Value("${novel.auth.google.rate-limit-refill-interval:6s}") Duration refillInterval) {
    this.dataSource = dataSource;
    if (refillInterval.isZero()
        || refillInterval.isNegative()
        || refillInterval.compareTo(Duration.ofDays(1)) > 0) {
      throw new IllegalArgumentException(
          "Rate-limit refill interval must be positive and at most one day");
    }
    this.refillInterval = refillInterval;
  }

  public boolean tryAcquire(byte[] clientKeyHash) {
    validateClientKeyHash(clientKeyHash);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertBucketIfMissing(connection, clientKeyHash);
        Bucket bucket = lockBucket(connection, clientKeyHash);
        Instant now = databaseNow(connection);
        BigDecimal available = refill(bucket, now);
        boolean acquired = available.compareTo(ONE_TOKEN) >= 0;

        if (acquired) {
          updateBucket(connection, clientKeyHash, available.subtract(ONE_TOKEN), now, true);
        } else {
          updateBucket(connection, clientKeyHash, bucket.availableTokens(), now, false);
        }

        connection.commit();
        return acquired;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to apply Google auth bootstrap rate limit", exception);
    }
  }

  @Scheduled(fixedDelayString = "${novel.auth.google.rate-limit-cleanup-delay-ms:3600000}")
  public void scheduledPurgeInactiveBuckets() {
    purgeInactiveBuckets();
  }

  public int purgeInactiveBuckets() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                """
                     DELETE FROM google_auth_bootstrap_rate_limits
                     WHERE last_seen_at < clock_timestamp() - (? * INTERVAL '1 millisecond')
                     """)) {
      delete.setLong(1, INACTIVE_RETENTION.toMillis());
      return delete.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to purge inactive Google auth rate-limit buckets", exception);
    }
  }

  private static void validateClientKeyHash(byte[] clientKeyHash) {
    if (clientKeyHash == null || clientKeyHash.length != 32) {
      throw new IllegalArgumentException("Rate-limit key must be a 32-byte HMAC");
    }
  }

  private static void insertBucketIfMissing(Connection connection, byte[] clientKeyHash)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO google_auth_bootstrap_rate_limits (
                    rate_limit_key_hash, available_tokens, refilled_at, last_seen_at)
                VALUES (?, ?, clock_timestamp(), clock_timestamp())
                ON CONFLICT (rate_limit_key_hash) DO NOTHING
                """)) {
      insert.setBytes(1, clientKeyHash);
      insert.setBigDecimal(2, BUCKET_CAPACITY);
      insert.executeUpdate();
    }
  }

  private static Bucket lockBucket(Connection connection, byte[] clientKeyHash)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT available_tokens, refilled_at
                FROM google_auth_bootstrap_rate_limits
                WHERE rate_limit_key_hash = ?
                FOR UPDATE
                """)) {
      select.setBytes(1, clientKeyHash);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          throw new SQLException("Rate-limit bucket disappeared before it could be locked");
        }
        return new Bucket(
            row.getBigDecimal("available_tokens"), row.getTimestamp("refilled_at").toInstant());
      }
    }
  }

  private static Instant databaseNow(Connection connection) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement("SELECT clock_timestamp()");
        ResultSet row = select.executeQuery()) {
      if (!row.next()) {
        throw new SQLException("Database clock query returned no value");
      }
      return row.getTimestamp(1).toInstant();
    }
  }

  private BigDecimal refill(Bucket bucket, Instant now) {
    long elapsedNanos = Math.max(0, Duration.between(bucket.refilledAt(), now).toNanos());
    BigDecimal regeneratedTokens =
        BigDecimal.valueOf(elapsedNanos)
            .divide(BigDecimal.valueOf(refillInterval.toNanos()), TOKEN_SCALE, RoundingMode.DOWN);
    return bucket.availableTokens().add(regeneratedTokens).min(BUCKET_CAPACITY);
  }

  private static void updateBucket(
      Connection connection,
      byte[] clientKeyHash,
      BigDecimal availableTokens,
      Instant now,
      boolean consumeToken)
      throws SQLException {
    String sql =
        consumeToken
            ? """
                  UPDATE google_auth_bootstrap_rate_limits
                  SET available_tokens = ?, refilled_at = ?, last_seen_at = ?
                  WHERE rate_limit_key_hash = ?
                  """
            : """
                  UPDATE google_auth_bootstrap_rate_limits
                  SET last_seen_at = ?
                  WHERE rate_limit_key_hash = ?
                  """;
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      if (consumeToken) {
        update.setBigDecimal(1, availableTokens.setScale(TOKEN_SCALE, RoundingMode.DOWN));
        update.setTimestamp(2, Timestamp.from(now));
        update.setTimestamp(3, Timestamp.from(now));
        update.setBytes(4, clientKeyHash);
      } else {
        update.setTimestamp(1, Timestamp.from(now));
        update.setBytes(2, clientKeyHash);
      }
      if (update.executeUpdate() != 1) {
        throw new SQLException("Rate-limit bucket update affected an unexpected number of rows");
      }
    }
  }

  private record Bucket(BigDecimal availableTokens, Instant refilledAt) {}
}
