package com.novelweb.identity.privacy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Persists actor-owned export requests and coordinates workers using short database leases. */
@Repository
@Profile("postgres")
public class AccountDataExportRepository {

  private static final Duration WORKER_LEASE = Duration.ofMinutes(30);
  private final DataSource dataSource;

  public AccountDataExportRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public CreateResult createOrReplay(UUID userId, byte[] idempotencyKeyHash) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(idempotencyKeyHash, "idempotency key hash is required");
    if (idempotencyKeyHash.length != 32) {
      throw new IllegalArgumentException("Idempotency key hash must be SHA-256");
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        if (!lockActiveUser(connection, userId)) {
          connection.commit();
          return new CreateResult(CreateOutcome.INACTIVE_ACCOUNT, null);
        }

        Optional<ExportJob> replay = findByIdempotencyKey(connection, userId, idempotencyKeyHash);
        if (replay.isPresent()) {
          connection.commit();
          return new CreateResult(CreateOutcome.REPLAYED, replay.orElseThrow());
        }
        if (hasActiveExport(connection, userId)) {
          connection.commit();
          return new CreateResult(CreateOutcome.ACTIVE_EXPORT_EXISTS, null);
        }

        ExportJob created = insert(connection, userId, idempotencyKeyHash);
        connection.commit();
        return new CreateResult(CreateOutcome.CREATED, created);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to create an account data export", exception);
    }
  }

  public Optional<ExportJob> findOwned(UUID userId, UUID exportId) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(exportId, "export id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, user_id, status, storage_key, artifact_sha256, artifact_bytes,
                           requested_at, started_at, ready_at, expires_at, failure_code
                    FROM account_data_exports
                    WHERE id = ? AND user_id = ?
                    """)) {
      select.setObject(1, exportId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readJob(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load an account data export", exception);
    }
  }

  public Optional<ExportJob> findLatestOwned(UUID userId) {
    Objects.requireNonNull(userId, "user id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, user_id, status, storage_key, artifact_sha256, artifact_bytes,
                           requested_at, started_at, ready_at, expires_at, failure_code
                    FROM account_data_exports
                    WHERE user_id = ?
                    ORDER BY requested_at DESC, id DESC
                    LIMIT 1
                    """)) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readJob(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the latest account data export", exception);
    }
  }

  public Optional<ClaimedJob> claimNext() {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement exhaust =
            connection.prepareStatement(
                """
                    UPDATE account_data_exports
                    SET status = 'failed', failure_code = 'GENERATION_FAILED',
                        lease_token = NULL, lease_until = NULL
                    WHERE status = 'processing' AND lease_until <= clock_timestamp()
                      AND attempt_count >= 5
                    """)) {
          exhaust.executeUpdate();
        }
        UUID exportId;
        UUID userId;
        UUID leaseToken = UUID.randomUUID();
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, user_id
                    FROM account_data_exports
                    WHERE attempt_count < 5
                      AND (status = 'queued'
                        OR (status = 'processing' AND lease_until <= clock_timestamp()))
                    ORDER BY requested_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                    """)) {
          try (ResultSet row = select.executeQuery()) {
            if (!row.next()) {
              connection.commit();
              return Optional.empty();
            }
            exportId = row.getObject("id", UUID.class);
            userId = row.getObject("user_id", UUID.class);
          }
        }
        try (PreparedStatement claim =
            connection.prepareStatement(
                """
                    UPDATE account_data_exports
                    SET status = 'processing', lease_token = ?,
                        lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                        started_at = COALESCE(started_at, clock_timestamp()),
                        attempt_count = attempt_count + 1
                    WHERE id = ?
                    RETURNING attempt_count
                    """)) {
          claim.setObject(1, leaseToken);
          claim.setLong(2, WORKER_LEASE.toMillis());
          claim.setObject(3, exportId);
          try (ResultSet row = claim.executeQuery()) {
            if (!row.next()) throw new SQLException("Export claim did not update its locked job");
          }
        }
        connection.commit();
        return Optional.of(new ClaimedJob(exportId, userId, leaseToken));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to claim an account data export", exception);
    }
  }

  public boolean markReady(
      ClaimedJob claim, UUID storageKey, byte[] artifactSha256, long artifactBytes, Duration ttl) {
    Objects.requireNonNull(claim, "claim is required");
    Objects.requireNonNull(storageKey, "storage key is required");
    Objects.requireNonNull(artifactSha256, "artifact checksum is required");
    if (artifactSha256.length != 32 || artifactBytes < 1 || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("Invalid account export artifact metadata");
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE account_data_exports
                    SET status = 'ready', storage_key = ?, artifact_sha256 = ?, artifact_bytes = ?,
                        ready_at = clock_timestamp(),
                        expires_at = clock_timestamp() + (? * INTERVAL '1 millisecond'),
                        lease_token = NULL, lease_until = NULL, failure_code = NULL
                    WHERE id = ? AND user_id = ? AND status = 'processing' AND lease_token = ?
                    """)) {
      update.setObject(1, storageKey);
      update.setBytes(2, artifactSha256);
      update.setLong(3, artifactBytes);
      update.setLong(4, ttl.toMillis());
      update.setObject(5, claim.exportId());
      update.setObject(6, claim.userId());
      update.setObject(7, claim.leaseToken());
      return update.executeUpdate() == 1;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to mark an account data export ready", exception);
    }
  }

  public boolean markFailed(ClaimedJob claim, String failureCode) {
    Objects.requireNonNull(claim, "claim is required");
    if (!List.of("GENERATION_FAILED", "SIZE_LIMIT_EXCEEDED", "STORAGE_UNAVAILABLE")
        .contains(failureCode)) {
      throw new IllegalArgumentException("Unsupported account export failure code");
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE account_data_exports
                    SET status = 'failed', failure_code = ?, lease_token = NULL, lease_until = NULL
                    WHERE id = ? AND user_id = ? AND status = 'processing' AND lease_token = ?
                    """)) {
      update.setString(1, failureCode);
      update.setObject(2, claim.exportId());
      update.setObject(3, claim.userId());
      update.setObject(4, claim.leaseToken());
      return update.executeUpdate() == 1;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to record account export failure", exception);
    }
  }

  public List<ExpiredArtifact> findExpiredArtifacts(int limit) {
    if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid expiry batch size");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, storage_key
                    FROM account_data_exports
                    WHERE status = 'ready' AND expires_at <= clock_timestamp()
                    ORDER BY expires_at, id
                    LIMIT ?
                    """)) {
      select.setInt(1, limit);
      try (ResultSet rows = select.executeQuery()) {
        List<ExpiredArtifact> expired = new ArrayList<>();
        while (rows.next()) {
          expired.add(
              new ExpiredArtifact(
                  rows.getObject("id", UUID.class), rows.getObject("storage_key", UUID.class)));
        }
        return List.copyOf(expired);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load expired account export artifacts", exception);
    }
  }

  public boolean markExpired(ExpiredArtifact artifact) {
    Objects.requireNonNull(artifact, "expired artifact is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE account_data_exports
                    SET status = 'expired', storage_key = NULL,
                        artifact_sha256 = NULL, artifact_bytes = NULL
                    WHERE id = ? AND storage_key = ? AND status = 'ready'
                      AND expires_at <= clock_timestamp()
                    """)) {
      update.setObject(1, artifact.exportId());
      update.setObject(2, artifact.storageKey());
      return update.executeUpdate() == 1;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to expire an account data export", exception);
    }
  }

  public boolean markArtifactUnavailable(UUID userId, UUID exportId, UUID storageKey) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE account_data_exports
                    SET status = 'failed', storage_key = NULL, artifact_sha256 = NULL,
                        artifact_bytes = NULL, ready_at = NULL, expires_at = NULL,
                        failure_code = 'STORAGE_UNAVAILABLE'
                    WHERE id = ? AND user_id = ? AND storage_key = ? AND status = 'ready'
                    """)) {
      update.setObject(1, exportId);
      update.setObject(2, userId);
      update.setObject(3, storageKey);
      return update.executeUpdate() == 1;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to update account export artifact status", exception);
    }
  }

  private static boolean lockActiveUser(Connection connection, UUID userId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static Optional<ExportJob> findByIdempotencyKey(
      Connection connection, UUID userId, byte[] keyHash) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, status, storage_key, artifact_sha256, artifact_bytes,
                       requested_at, started_at, ready_at, expires_at, failure_code
                FROM account_data_exports WHERE user_id = ? AND idempotency_key_hash = ?
                """)) {
      select.setObject(1, userId);
      select.setBytes(2, keyHash);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readJob(row)) : Optional.empty();
      }
    }
  }

  private static boolean hasActiveExport(Connection connection, UUID userId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM account_data_exports WHERE user_id = ? AND status IN ('queued','processing'))")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Active export query returned no row");
        return row.getBoolean(1);
      }
    }
  }

  private static ExportJob insert(Connection connection, UUID userId, byte[] keyHash)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO account_data_exports (id, user_id, idempotency_key_hash)
                VALUES (?, ?, ?)
                RETURNING id, user_id, status, storage_key, artifact_sha256, artifact_bytes,
                          requested_at, started_at, ready_at, expires_at, failure_code
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, userId);
      insert.setBytes(3, keyHash);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Export insert returned no job");
        return readJob(row);
      }
    }
  }

  private static ExportJob readJob(ResultSet row) throws SQLException {
    return new ExportJob(
        row.getObject("id", UUID.class),
        row.getObject("user_id", UUID.class),
        ExportStatus.valueOf(row.getString("status").toUpperCase(java.util.Locale.ROOT)),
        row.getObject("storage_key", UUID.class),
        row.getBytes("artifact_sha256"),
        row.getObject("artifact_bytes", Long.class),
        row.getObject("requested_at", OffsetDateTime.class).toInstant(),
        instant(row, "started_at"),
        instant(row, "ready_at"),
        instant(row, "expires_at"),
        row.getString("failure_code"));
  }

  private static java.time.Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static void rollback(Connection connection, Exception originalException) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      originalException.addSuppressed(rollbackException);
    }
  }

  public enum CreateOutcome {
    CREATED,
    REPLAYED,
    ACTIVE_EXPORT_EXISTS,
    INACTIVE_ACCOUNT
  }

  public enum ExportStatus {
    QUEUED,
    PROCESSING,
    READY,
    FAILED,
    EXPIRED
  }

  public record CreateResult(CreateOutcome outcome, ExportJob job) {}

  public record ExportJob(
      UUID id,
      UUID userId,
      ExportStatus status,
      UUID storageKey,
      byte[] artifactSha256,
      Long artifactBytes,
      java.time.Instant requestedAt,
      java.time.Instant startedAt,
      java.time.Instant readyAt,
      java.time.Instant expiresAt,
      String failureCode) {
    public ExportJob {
      artifactSha256 = artifactSha256 == null ? null : artifactSha256.clone();
    }

    @Override
    public byte[] artifactSha256() {
      return artifactSha256 == null ? null : artifactSha256.clone();
    }
  }

  public record ClaimedJob(UUID exportId, UUID userId, UUID leaseToken) {}

  public record ExpiredArtifact(UUID exportId, UUID storageKey) {}
}
