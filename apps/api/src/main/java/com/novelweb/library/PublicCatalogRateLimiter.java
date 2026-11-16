package com.novelweb.library;

import com.novelweb.platform.security.TrustedProxyClientAddressResolver;
import jakarta.servlet.http.HttpServletRequest;
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

/** Persistent per-client token buckets for anonymous catalog reads. */
@Service
@Profile("postgres")
public final class PublicCatalogRateLimiter {

  private static final BigDecimal ONE_TOKEN = BigDecimal.ONE;
  private static final int TOKEN_SCALE = 6;
  private static final Duration INACTIVE_RETENTION = Duration.ofHours(24);
  private static final int CLEANUP_BATCH_SIZE = 500;

  private final DataSource dataSource;
  private final String hmacKey;
  private final int capacity;
  private final Duration refillInterval;
  private final TrustedProxyClientAddressResolver clientAddressResolver;
  private final boolean configured;

  public PublicCatalogRateLimiter(
      DataSource dataSource,
      @Value("${novel.public-catalog.rate-limit.hmac-key:}") String hmacKey,
      @Value("${novel.public-catalog.trusted-proxies:}") String trustedProxyCidrs,
      @Value("${novel.public-catalog.rate-limit.capacity:60}") int capacity,
      @Value("${novel.public-catalog.rate-limit.refill-interval:1s}") Duration refillInterval) {
    this.dataSource = dataSource;
    this.hmacKey = hmacKey == null ? "" : hmacKey.trim();
    this.capacity = capacity;
    this.refillInterval = refillInterval;

    TrustedProxyClientAddressResolver resolver = null;
    try {
      resolver = new TrustedProxyClientAddressResolver(trustedProxyCidrs);
    } catch (IllegalArgumentException ignored) {
      // Invalid forwarding trust configuration disables anonymous reads at readiness and runtime.
    }
    this.clientAddressResolver = resolver;
    this.configured =
        dataSource != null
            && PublicCatalogRateLimitKeyHasher.isValidKey(this.hmacKey)
            && resolver != null
            && capacity >= 1
            && capacity <= 500
            && refillInterval != null
            && !refillInterval.isNegative()
            && !refillInterval.isZero()
            && refillInterval.compareTo(Duration.ofDays(1)) <= 0;
  }

  public boolean isConfigured() {
    return configured;
  }

  public Decision tryAcquire(HttpServletRequest request) {
    if (!configured) {
      throw new IllegalStateException("Public catalog rate limiting is not configured");
    }

    final byte[] clientKeyHash;
    try {
      clientKeyHash =
          PublicCatalogRateLimitKeyHasher.hash(clientAddressResolver.resolve(request), hmacKey);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Unable to resolve public catalog client key", exception);
    }

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        insertBucketIfMissing(connection, clientKeyHash);
        Bucket bucket = lockBucket(connection, clientKeyHash);
        Instant now = databaseNow(connection);
        BigDecimal available = refill(bucket.availableTokens(), bucket.refilledAt(), now);
        boolean allowed = available.compareTo(ONE_TOKEN) >= 0;
        BigDecimal remaining = allowed ? available.subtract(ONE_TOKEN) : available;
        updateBucket(connection, clientKeyHash, remaining, now);
        connection.commit();
        return new Decision(allowed, allowed ? 0 : retryAfterSeconds(available));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to apply public catalog rate limit", exception);
    }
  }

  @Scheduled(fixedDelayString = "${novel.public-catalog.rate-limit.cleanup-delay:1h}")
  public int purgeInactiveBuckets() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                """
                    WITH candidates AS (
                        SELECT rate_limit_key_hash
                        FROM public_catalog_rate_limits
                        WHERE last_seen_at < clock_timestamp() - (? * INTERVAL '1 millisecond')
                        ORDER BY last_seen_at, rate_limit_key_hash
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM public_catalog_rate_limits AS buckets
                    USING candidates
                    WHERE buckets.rate_limit_key_hash = candidates.rate_limit_key_hash
                    """)) {
      delete.setLong(1, INACTIVE_RETENTION.toMillis());
      delete.setInt(2, CLEANUP_BATCH_SIZE);
      return delete.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to purge public catalog rate-limit buckets", exception);
    }
  }

  private void insertBucketIfMissing(Connection connection, byte[] clientKeyHash)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO public_catalog_rate_limits (
                    rate_limit_key_hash, available_tokens, refilled_at, last_seen_at)
                VALUES (?, ?, clock_timestamp(), clock_timestamp())
                ON CONFLICT (rate_limit_key_hash) DO NOTHING
                """)) {
      insert.setBytes(1, clientKeyHash);
      insert.setBigDecimal(2, BigDecimal.valueOf(capacity));
      insert.executeUpdate();
    }
  }

  private static Bucket lockBucket(Connection connection, byte[] clientKeyHash)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT available_tokens, refilled_at
                FROM public_catalog_rate_limits
                WHERE rate_limit_key_hash = ?
                FOR UPDATE
                """)) {
      select.setBytes(1, clientKeyHash);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Public catalog rate-limit bucket disappeared");
        return new Bucket(
            row.getBigDecimal("available_tokens"),
            row.getObject("refilled_at", java.time.OffsetDateTime.class).toInstant());
      }
    }
  }

  private static Instant databaseNow(Connection connection) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement("SELECT clock_timestamp()")) {
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Database clock query returned no value");
        return row.getObject(1, java.time.OffsetDateTime.class).toInstant();
      }
    }
  }

  private BigDecimal refill(BigDecimal availableTokens, Instant refilledAt, Instant now) {
    long elapsedNanos = Math.max(0, Duration.between(refilledAt, now).toNanos());
    BigDecimal regeneratedTokens =
        BigDecimal.valueOf(elapsedNanos)
            .divide(BigDecimal.valueOf(refillInterval.toNanos()), TOKEN_SCALE, RoundingMode.DOWN);
    return availableTokens.add(regeneratedTokens).min(BigDecimal.valueOf(capacity));
  }

  private int retryAfterSeconds(BigDecimal availableTokens) {
    BigDecimal missing = ONE_TOKEN.subtract(availableTokens).max(BigDecimal.ZERO);
    BigDecimal nanos = missing.multiply(BigDecimal.valueOf(refillInterval.toNanos()));
    long seconds =
        nanos
            .divide(BigDecimal.valueOf(Duration.ofSeconds(1).toNanos()), 0, RoundingMode.CEILING)
            .longValue();
    return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
  }

  private static void updateBucket(
      Connection connection, byte[] clientKeyHash, BigDecimal availableTokens, Instant now)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE public_catalog_rate_limits
                SET available_tokens = ?, refilled_at = ?, last_seen_at = ?
                WHERE rate_limit_key_hash = ?
                """)) {
      update.setBigDecimal(1, availableTokens.setScale(TOKEN_SCALE, RoundingMode.DOWN));
      update.setTimestamp(2, Timestamp.from(now));
      update.setTimestamp(3, Timestamp.from(now));
      update.setBytes(4, clientKeyHash);
      if (update.executeUpdate() != 1) {
        throw new SQLException("Public catalog rate-limit bucket update failed");
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
}
