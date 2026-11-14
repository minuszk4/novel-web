package com.novelweb.translation.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Claims and renews PostgreSQL translation-unit leases without loading manuscript content. */
@Repository
@Profile("postgres")
public class TranslationUnitLeaseRepository {

  private final DataSource dataSource;

  public TranslationUnitLeaseRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public Optional<Lease> claimNext(String workerId, long leaseSeconds) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        Optional<Candidate> next = findNextClaimableUnit(connection);
        if (next.isEmpty()) {
          connection.commit();
          return Optional.empty();
        }

        Candidate candidate = next.orElseThrow();
        Optional<Lease> lease = assignLease(connection, candidate, workerId, leaseSeconds);
        if (lease.isEmpty()) {
          connection.commit();
          return Optional.empty();
        }
        if (!refreshJobProgress(connection, candidate.jobId())) {
          connection.rollback();
          return Optional.empty();
        }
        connection.commit();
        return lease;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to claim translation unit", exception);
    }
  }

  public Optional<Lease> heartbeat(
      Lease lease, String workerId, UUID leaseToken, long leaseSeconds) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        Optional<Lease> renewed = renewLease(connection, lease, workerId, leaseToken, leaseSeconds);
        connection.commit();
        return renewed;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to renew translation-unit lease", exception);
    }
  }

  private static Optional<Candidate> findNextClaimableUnit(Connection connection)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT unit.id, unit.job_id
                FROM translation_units unit
                JOIN translation_jobs job ON job.id = unit.job_id
                WHERE job.status IN ('queued', 'preparing', 'retry_wait', 'translating')
                  AND ((unit.status IN ('queued', 'retry_wait')
                        AND unit.available_at <= clock_timestamp())
                    OR (unit.status = 'running' AND unit.lease_until <= clock_timestamp()))
                ORDER BY job.created_at, unit.unit_no, unit.id
                LIMIT 1
                FOR UPDATE OF unit SKIP LOCKED
                """)) {
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new Candidate(row.getObject("job_id", UUID.class), row.getObject("id", UUID.class)))
            : Optional.empty();
      }
    }
  }

  private static Optional<Lease> assignLease(
      Connection connection, Candidate candidate, String workerId, long leaseSeconds)
      throws SQLException {
    UUID leaseToken = UUID.randomUUID();
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_units unit
                SET status = 'running', lease_owner = ?, lease_token = ?,
                    lease_until = clock_timestamp() + (? * INTERVAL '1 second'),
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE unit.id = ? AND unit.job_id = ?
                  AND ((unit.status IN ('queued', 'retry_wait')
                        AND unit.available_at <= clock_timestamp())
                    OR (unit.status = 'running' AND unit.lease_until <= clock_timestamp()))
                RETURNING unit.id, unit.job_id, unit.source_revision_id, unit.unit_no,
                          unit.attempt_count, unit.max_attempts, unit.lease_owner,
                          unit.lease_token, unit.lease_until, unit.heartbeat_at
                """)) {
      update.setString(1, workerId);
      update.setObject(2, leaseToken);
      update.setLong(3, leaseSeconds);
      update.setObject(4, candidate.unitId());
      update.setObject(5, candidate.jobId());
      try (ResultSet row = update.executeQuery()) {
        return row.next() ? Optional.of(lease(row)) : Optional.empty();
      }
    }
  }

  private static Optional<Lease> renewLease(
      Connection connection, Lease lease, String workerId, UUID leaseToken, long leaseSeconds)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_units unit
                SET lease_until = clock_timestamp() + (? * INTERVAL '1 second'),
                    heartbeat_at = clock_timestamp(), updated_at = clock_timestamp()
                WHERE unit.job_id = ? AND unit.id = ? AND unit.status = 'running'
                  AND unit.lease_owner = ? AND unit.lease_token = ?
                  AND unit.lease_until > clock_timestamp()
                RETURNING unit.id, unit.job_id, unit.source_revision_id, unit.unit_no,
                          unit.attempt_count, unit.max_attempts, unit.lease_owner,
                          unit.lease_token, unit.lease_until, unit.heartbeat_at
                """)) {
      update.setLong(1, leaseSeconds);
      update.setObject(2, lease.jobId());
      update.setObject(3, lease.unitId());
      update.setString(4, workerId);
      update.setObject(5, leaseToken);
      try (ResultSet row = update.executeQuery()) {
        return row.next() ? Optional.of(lease(row)) : Optional.empty();
      }
    }
  }

  private static boolean refreshJobProgress(Connection connection, UUID jobId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_jobs job
                SET completed_units = counts.completed_units,
                    failed_units = counts.failed_units,
                    status = CASE WHEN job.status IN ('queued', 'preparing', 'retry_wait')
                                  THEN 'translating' ELSE job.status END,
                    started_at = COALESCE(job.started_at, clock_timestamp()),
                    updated_at = clock_timestamp()
                FROM (
                    SELECT count(*) FILTER (WHERE status = 'succeeded')::integer
                               AS completed_units,
                           count(*) FILTER (WHERE status = 'failed')::integer AS failed_units
                    FROM translation_units WHERE job_id = ?
                ) counts
                WHERE job.id = ?
                  AND job.status IN ('queued', 'preparing', 'retry_wait', 'translating')
                """)) {
      update.setObject(1, jobId);
      update.setObject(2, jobId);
      return update.executeUpdate() == 1;
    }
  }

  private static Lease lease(ResultSet row) throws SQLException {
    return new Lease(
        row.getObject("job_id", UUID.class),
        row.getObject("id", UUID.class),
        row.getObject("source_revision_id", UUID.class),
        row.getInt("unit_no"),
        row.getInt("attempt_count"),
        row.getInt("max_attempts"),
        row.getString("lease_owner"),
        row.getObject("lease_token", UUID.class),
        row.getObject("lease_until", OffsetDateTime.class).toInstant(),
        row.getObject("heartbeat_at", OffsetDateTime.class).toInstant());
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record Candidate(UUID jobId, UUID unitId) {}

  public record Lease(
      UUID jobId,
      UUID unitId,
      UUID sourceRevisionId,
      int unitNo,
      int attemptCount,
      int maxAttempts,
      String workerId,
      UUID leaseToken,
      Instant leaseUntil,
      Instant heartbeatAt) {}
}
