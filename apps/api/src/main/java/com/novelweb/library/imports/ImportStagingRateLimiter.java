package com.novelweb.library.imports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** PostgreSQL token buckets for actor-scoped import creation and upload request bursts. */
@Service
@Profile("postgres")
public class ImportStagingRateLimiter {

  private static final BigDecimal ONE_TOKEN = BigDecimal.ONE;
  private static final int TOKEN_SCALE = 6;

  private final DataSource dataSource;
  private final ImportStagingProperties properties;

  public ImportStagingRateLimiter(DataSource dataSource, ImportStagingProperties properties) {
    this.dataSource = dataSource;
    this.properties = properties;
  }

  public Decision tryAcquire(UUID userId, Action action) {
    if (userId == null) throw new IllegalArgumentException("Import rate-limit actor is required");
    if (action == null) throw new IllegalArgumentException("Import rate-limit action is required");

    int capacity = capacity(action);
    Duration refillInterval = refillInterval(action);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertBucketIfMissing(connection, userId, action, capacity);
        Bucket bucket = lockBucket(connection, userId, action);
        Instant now = databaseNow(connection);
        BigDecimal available = refill(bucket, now, refillInterval, capacity);
        boolean acquired = available.compareTo(ONE_TOKEN) >= 0;
        BigDecimal remaining = acquired ? available.subtract(ONE_TOKEN) : available;
        updateBucket(connection, userId, action, remaining, now);
        connection.commit();
        return new Decision(acquired, acquired ? 0 : retryAfterSeconds(available, refillInterval));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to apply import request rate limit", exception);
    }
  }

  @Scheduled(fixedDelayString = "${novel.imports.staging.cleanup-delay:1m}")
  public int purgeInactiveBuckets() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                """
                     WITH candidates AS (
                         SELECT user_id, action
                         FROM import_actor_rate_limits
                         WHERE last_seen_at < clock_timestamp() - (? * INTERVAL '1 millisecond')
                         ORDER BY last_seen_at, user_id, action
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED
                     )
                     DELETE FROM import_actor_rate_limits AS buckets
                     USING candidates
                     WHERE buckets.user_id = candidates.user_id AND buckets.action = candidates.action
                     """)) {
      delete.setLong(1, properties.rateLimitRetention().toMillis());
      delete.setInt(2, properties.cleanupBatchSize());
      return delete.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to purge inactive import rate-limit buckets", exception);
    }
  }

  private int capacity(Action action) {
    return switch (action) {
      case CREATE -> properties.createRateLimitCapacity();
      case UPLOAD -> properties.uploadRateLimitCapacity();
    };
  }

  private Duration refillInterval(Action action) {
    return switch (action) {
      case CREATE -> properties.createRateLimitRefillInterval();
      case UPLOAD -> properties.uploadRateLimitRefillInterval();
    };
  }

  private static void insertBucketIfMissing(
      Connection connection, UUID userId, Action action, int capacity) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO import_actor_rate_limits (user_id, action, available_tokens, refilled_at, last_seen_at)
                VALUES (?, ?, ?, clock_timestamp(), clock_timestamp())
                ON CONFLICT (user_id, action) DO NOTHING
                """)) {
      insert.setObject(1, userId);
      insert.setString(2, action.name());
      insert.setBigDecimal(3, BigDecimal.valueOf(capacity));
      insert.executeUpdate();
    }
  }

  private static Bucket lockBucket(Connection connection, UUID userId, Action action)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT available_tokens, refilled_at
                FROM import_actor_rate_limits
                WHERE user_id = ? AND action = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setString(2, action.name());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next())
          throw new SQLException("Import rate-limit bucket disappeared before it was locked");
        return new Bucket(
            row.getBigDecimal("available_tokens"),
            row.getObject("refilled_at", java.time.OffsetDateTime.class).toInstant());
      }
    }
  }

  private static Instant databaseNow(Connection connection) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement("SELECT clock_timestamp()");
        ResultSet row = select.executeQuery()) {
      if (!row.next()) throw new SQLException("Database clock query returned no value");
      return row.getObject(1, java.time.OffsetDateTime.class).toInstant();
    }
  }

  private static BigDecimal refill(Bucket bucket, Instant now, Duration interval, int capacity) {
    long elapsedNanos = Math.max(0, Duration.between(bucket.refilledAt(), now).toNanos());
    BigDecimal generated =
        BigDecimal.valueOf(elapsedNanos)
            .divide(BigDecimal.valueOf(interval.toNanos()), TOKEN_SCALE, RoundingMode.DOWN);
    return bucket.availableTokens().add(generated).min(BigDecimal.valueOf(capacity));
  }

  private static int retryAfterSeconds(BigDecimal available, Duration interval) {
    BigDecimal missing = ONE_TOKEN.subtract(available).max(BigDecimal.ZERO);
    BigDecimal nanos = missing.multiply(BigDecimal.valueOf(interval.toNanos()));
    long seconds =
        nanos
            .divide(BigDecimal.valueOf(Duration.ofSeconds(1).toNanos()), 0, RoundingMode.CEILING)
            .longValue();
    return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
  }

  private static void updateBucket(
      Connection connection, UUID userId, Action action, BigDecimal available, Instant now)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE import_actor_rate_limits
                SET available_tokens = ?, refilled_at = ?, last_seen_at = ?
                WHERE user_id = ? AND action = ?
                """)) {
      update.setBigDecimal(1, available.setScale(TOKEN_SCALE, RoundingMode.DOWN));
      update.setTimestamp(2, Timestamp.from(now));
      update.setTimestamp(3, Timestamp.from(now));
      update.setObject(4, userId);
      update.setString(5, action.name());
      if (update.executeUpdate() != 1)
        throw new SQLException("Import rate-limit bucket update failed");
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  public enum Action {
    CREATE,
    UPLOAD
  }

  public record Decision(boolean allowed, int retryAfterSeconds) {}

  private record Bucket(BigDecimal availableTokens, Instant refilledAt) {}
}
