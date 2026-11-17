package com.novelweb.library;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Persistent per-actor token bucket for follow, mute and unfollow mutations. */
@Service
@Profile("postgres")
public final class WorkSubscriptionRateLimiter {

  private static final BigDecimal ONE_TOKEN = BigDecimal.ONE;
  private static final int TOKEN_SCALE = 6;

  private final DataSource dataSource;
  private final int capacity;
  private final Duration refillInterval;
  private final Duration retention;
  private final int cleanupBatchSize;

  public WorkSubscriptionRateLimiter(
      DataSource dataSource,
      @Value("${novel.work-subscriptions.rate-limit-capacity:30}") int capacity,
      @Value("${novel.work-subscriptions.rate-limit-refill-interval:PT20S}")
          Duration refillInterval,
      @Value("${novel.work-subscriptions.rate-limit-retention:P30D}") Duration retention,
      @Value("${novel.work-subscriptions.rate-limit-cleanup-batch-size:500}")
          int cleanupBatchSize) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    if (capacity < 1 || capacity > 100) {
      throw new IllegalArgumentException("Work subscription rate-limit capacity is invalid");
    }
    if (refillInterval == null || refillInterval.isZero() || refillInterval.isNegative()) {
      throw new IllegalArgumentException("Work subscription refill interval must be positive");
    }
    if (retention == null || retention.isZero() || retention.isNegative()) {
      throw new IllegalArgumentException("Work subscription bucket retention must be positive");
    }
    if (cleanupBatchSize < 1 || cleanupBatchSize > 10_000) {
      throw new IllegalArgumentException("Work subscription cleanup batch size is invalid");
    }
    this.capacity = capacity;
    this.refillInterval = refillInterval;
    this.retention = retention;
    this.cleanupBatchSize = cleanupBatchSize;
  }

  public Decision acquire(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertBucketIfMissing(connection, actorUserId, capacity);
        Bucket bucket = lockBucket(connection, actorUserId);
        Instant now = databaseNow(connection);
        BigDecimal available = refill(bucket, now);
        boolean allowed = available.compareTo(ONE_TOKEN) >= 0;
        BigDecimal remaining = allowed ? available.subtract(ONE_TOKEN) : available;
        updateBucket(connection, actorUserId, remaining, now);
        connection.commit();
        return new Decision(allowed, allowed ? 0 : retryAfterSeconds(available));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new RateLimitStoreUnavailableException(exception);
    }
  }

  @Scheduled(fixedDelayString = "${novel.work-subscriptions.rate-limit-cleanup-delay:PT1H}")
  public int purgeInactiveBuckets() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                """
                    WITH candidates AS (
                        SELECT ctid
                        FROM work_subscription_rate_limits
                        WHERE last_seen_at < clock_timestamp() - (? * INTERVAL '1 millisecond')
                        ORDER BY last_seen_at, actor_user_id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM work_subscription_rate_limits bucket
                    USING candidates
                    WHERE bucket.ctid = candidates.ctid
                    """)) {
      delete.setLong(1, retention.toMillis());
      delete.setInt(2, cleanupBatchSize);
      return delete.executeUpdate();
    } catch (SQLException exception) {
      throw new RateLimitStoreUnavailableException(exception);
    }
  }

  private static void insertBucketIfMissing(Connection connection, UUID actorUserId, int capacity)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO work_subscription_rate_limits
                    (actor_user_id, available_tokens, refilled_at, last_seen_at)
                VALUES (?, ?, clock_timestamp(), clock_timestamp())
                ON CONFLICT (actor_user_id) DO NOTHING
                """)) {
      insert.setObject(1, actorUserId);
      insert.setBigDecimal(2, BigDecimal.valueOf(capacity));
      insert.executeUpdate();
    }
  }

  private static Bucket lockBucket(Connection connection, UUID actorUserId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT available_tokens, refilled_at
                FROM work_subscription_rate_limits
                WHERE actor_user_id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Work subscription rate-limit bucket disappeared");
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

  private BigDecimal refill(Bucket bucket, Instant now) {
    long elapsedNanos = Math.max(0, Duration.between(bucket.refilledAt(), now).toNanos());
    BigDecimal generated =
        BigDecimal.valueOf(elapsedNanos)
            .divide(BigDecimal.valueOf(refillInterval.toNanos()), TOKEN_SCALE, RoundingMode.DOWN);
    return bucket.availableTokens().add(generated).min(BigDecimal.valueOf(capacity));
  }

  private int retryAfterSeconds(BigDecimal available) {
    BigDecimal missing = ONE_TOKEN.subtract(available).max(BigDecimal.ZERO);
    BigDecimal nanos = missing.multiply(BigDecimal.valueOf(refillInterval.toNanos()));
    long seconds =
        nanos
            .divide(BigDecimal.valueOf(Duration.ofSeconds(1).toNanos()), 0, RoundingMode.CEILING)
            .longValue();
    return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
  }

  private static void updateBucket(
      Connection connection, UUID actorUserId, BigDecimal available, Instant now)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE work_subscription_rate_limits
                   SET available_tokens = ?, refilled_at = ?, last_seen_at = ?
                 WHERE actor_user_id = ?
                """)) {
      update.setBigDecimal(1, available.setScale(TOKEN_SCALE, RoundingMode.DOWN));
      update.setTimestamp(2, Timestamp.from(now));
      update.setTimestamp(3, Timestamp.from(now));
      update.setObject(4, actorUserId);
      if (update.executeUpdate() != 1) {
        throw new SQLException("Work subscription rate-limit bucket update failed");
      }
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  public record Decision(boolean allowed, int retryAfterSeconds) {}

  private record Bucket(BigDecimal availableTokens, Instant refilledAt) {}

  public static final class RateLimitStoreUnavailableException extends RuntimeException {
    public RateLimitStoreUnavailableException(Throwable cause) {
      super("Work subscription rate-limit store is unavailable", cause);
    }
  }
}
