package com.novelweb.translation.jobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Stores one durable, body-free provider attempt under the current unit lease. */
@Repository
@Profile("postgres")
public class TranslationAttemptRepository {

  private final DataSource dataSource;

  public TranslationAttemptRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public StartResult begin(TranslationUnitLeaseRepository.Lease lease) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        UnitContext context = loadUnitContext(connection, lease);
        requireCurrentLease(context, lease);
        if (!lockTranslatableJob(connection, lease.jobId())) {
          throw new AttemptNotAllowedException("job_state");
        }

        byte[] requestSha256 = requestSha256(context, loadUnitSegments(connection, lease.unitId()));
        int nextAttemptNo = lease.attemptCount() + 1;
        Optional<ExistingAttempt> previousStart =
            findAttempt(connection, lease.unitId(), nextAttemptNo);
        if (context.attemptCount() == nextAttemptNo && previousStart.isPresent()) {
          ExistingAttempt existing = previousStart.orElseThrow();
          if (!MessageDigest.isEqual(existing.requestSha256(), requestSha256)) {
            throw new AttemptConflictException();
          }
          connection.commit();
          return new StartResult(
              existing.id(),
              context.jobId(),
              lease.unitId(),
              nextAttemptNo,
              context.providerId(),
              requestSha256,
              existing.outcome(),
              true,
              false);
        }

        if (context.attemptCount() == nextAttemptNo && previousStart.isEmpty()) {
          throw new InvalidAttemptContextException();
        }
        if (context.attemptCount() != lease.attemptCount()) {
          throw new StaleLeaseException();
        }
        if (previousStart.isPresent()) throw new InvalidAttemptContextException();
        if (context.attemptCount() >= context.maxAttempts()) {
          throw new AttemptsExhaustedException();
        }
        if (hasRunningAttempt(connection, lease.unitId())) {
          throw new UnresolvedAttemptException();
        }

        int attemptNo = context.attemptCount() + 1;
        UUID attemptId = UUID.randomUUID();
        incrementAttemptCount(connection, context, lease, attemptNo);
        insertAttempt(
            connection, attemptId, lease.unitId(), attemptNo, context.providerId(), requestSha256);
        insertAttemptRequestedEvent(
            connection, UUID.randomUUID(), context.jobId(), lease.unitId(), attemptId, attemptNo);
        connection.commit();
        return new StartResult(
            attemptId,
            context.jobId(),
            lease.unitId(),
            attemptNo,
            context.providerId(),
            requestSha256,
            "running",
            false,
            true);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to start translation attempt", exception);
    }
  }

  private static boolean lockTranslatableJob(Connection connection, UUID jobId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT status FROM translation_jobs WHERE id = ? FOR SHARE")) {
      select.setObject(1, jobId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() && "translating".equals(row.getString("status"));
      }
    }
  }

  private static UnitContext loadUnitContext(
      Connection connection, TranslationUnitLeaseRepository.Lease lease) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT unit.job_id, unit.source_revision_id, unit.status AS unit_status,
                       unit.lease_owner, unit.lease_token, unit.lease_until,
                       unit.attempt_count, unit.max_attempts,
                       job.status AS job_status, job.provider_id, job.source_language,
                       job.target_language, job.source_sha256, job.request_sha256,
                       job.profile_snapshot::text AS profile_snapshot,
                       job.glossary_snapshot::text AS glossary_snapshot,
                       unit.lease_until > clock_timestamp() AS lease_live
                FROM translation_units unit
                JOIN translation_jobs job ON job.id = unit.job_id
                WHERE unit.id = ? AND unit.job_id = ?
                FOR UPDATE OF unit
                """)) {
      select.setObject(1, lease.unitId());
      select.setObject(2, lease.jobId());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new StaleLeaseException();
        return new UnitContext(
            row.getObject("job_id", UUID.class),
            row.getObject("source_revision_id", UUID.class),
            row.getString("unit_status"),
            row.getString("lease_owner"),
            row.getObject("lease_token", UUID.class),
            row.getInt("attempt_count"),
            row.getInt("max_attempts"),
            row.getString("job_status"),
            row.getString("provider_id"),
            row.getString("source_language"),
            row.getString("target_language"),
            row.getBytes("source_sha256"),
            row.getBytes("request_sha256"),
            row.getString("profile_snapshot"),
            row.getString("glossary_snapshot"),
            row.getBoolean("lease_live"));
      }
    }
  }

  private static void requireCurrentLease(
      UnitContext context, TranslationUnitLeaseRepository.Lease lease) {
    if (!"running".equals(context.unitStatus())
        || !lease.workerId().equals(context.leaseOwner())
        || !lease.leaseToken().equals(context.leaseToken())
        || !lease.sourceRevisionId().equals(context.sourceRevisionId())
        || !context.leaseLive()) {
      throw new StaleLeaseException();
    }
  }

  private static List<SegmentSnapshot> loadUnitSegments(Connection connection, UUID unitId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT segment_ordinal, source_segment_id, source_sha256
                FROM translation_unit_segments WHERE unit_id = ?
                ORDER BY segment_ordinal
                """)) {
      select.setObject(1, unitId);
      try (ResultSet rows = select.executeQuery()) {
        List<SegmentSnapshot> segments = new ArrayList<>();
        int expectedOrdinal = 1;
        while (rows.next()) {
          int ordinal = rows.getInt("segment_ordinal");
          byte[] checksum = rows.getBytes("source_sha256");
          if (ordinal != expectedOrdinal++ || checksum == null || checksum.length != 32) {
            throw new InvalidAttemptContextException();
          }
          segments.add(
              new SegmentSnapshot(
                  ordinal, rows.getObject("source_segment_id", UUID.class), checksum));
        }
        if (segments.isEmpty()) throw new InvalidAttemptContextException();
        return List.copyOf(segments);
      }
    }
  }

  private static byte[] requestSha256(UnitContext context, List<SegmentSnapshot> segments) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, "translation-unit-attempt-v1");
      update(digest, context.jobId().toString());
      update(digest, context.sourceRevisionId().toString());
      update(digest, context.providerId());
      update(digest, context.sourceLanguage());
      update(digest, context.targetLanguage());
      update(digest, context.profileSnapshot());
      update(digest, context.glossarySnapshot());
      update(digest, context.sourceSha256());
      update(digest, context.requestSha256());
      for (SegmentSnapshot segment : segments) {
        update(digest, segment.ordinal());
        update(digest, segment.id().toString());
        update(digest, segment.checksum());
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void update(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    update(digest, bytes.length);
    digest.update(bytes);
  }

  private static void update(MessageDigest digest, byte[] value) {
    update(digest, value.length);
    digest.update(value);
  }

  private static void update(MessageDigest digest, int value) {
    digest.update((byte) (value >>> 24));
    digest.update((byte) (value >>> 16));
    digest.update((byte) (value >>> 8));
    digest.update((byte) value);
  }

  private static Optional<ExistingAttempt> findAttempt(
      Connection connection, UUID unitId, int attemptNo) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id, outcome, request_sha256 FROM translation_attempts "
                + "WHERE unit_id = ? AND attempt_no = ?")) {
      select.setObject(1, unitId);
      select.setInt(2, attemptNo);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new ExistingAttempt(
                    row.getObject("id", UUID.class),
                    row.getString("outcome"),
                    row.getBytes("request_sha256")))
            : Optional.empty();
      }
    }
  }

  private static boolean hasRunningAttempt(Connection connection, UUID unitId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM translation_attempts "
                + "WHERE unit_id = ? AND outcome = 'running')")) {
      select.setObject(1, unitId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() && row.getBoolean(1);
      }
    }
  }

  private static void incrementAttemptCount(
      Connection connection,
      UnitContext context,
      TranslationUnitLeaseRepository.Lease lease,
      int attemptNo)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE translation_units
                SET attempt_count = ?, updated_at = clock_timestamp()
                WHERE id = ? AND job_id = ? AND status = 'running'
                  AND lease_owner = ? AND lease_token = ?
                  AND lease_until > clock_timestamp() AND attempt_count = ?
                """)) {
      update.setInt(1, attemptNo);
      update.setObject(2, lease.unitId());
      update.setObject(3, lease.jobId());
      update.setString(4, lease.workerId());
      update.setObject(5, lease.leaseToken());
      update.setInt(6, context.attemptCount());
      if (update.executeUpdate() != 1) throw new StaleLeaseException();
    }
  }

  private static void insertAttempt(
      Connection connection,
      UUID attemptId,
      UUID unitId,
      int attemptNo,
      String providerId,
      byte[] requestSha256)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_attempts
                    (id, unit_id, attempt_no, provider_id, outcome, request_sha256)
                VALUES (?, ?, ?, ?, 'running', ?)
                """)) {
      insert.setObject(1, attemptId);
      insert.setObject(2, unitId);
      insert.setInt(3, attemptNo);
      insert.setString(4, providerId);
      insert.setBytes(5, requestSha256);
      insert.executeUpdate();
    }
  }

  private static void insertAttemptRequestedEvent(
      Connection connection, UUID eventId, UUID jobId, UUID unitId, UUID attemptId, int attemptNo)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO translation_outbox_events
                    (event_id, aggregate_id, event_type, schema_version, deduplication_key,
                     correlation_id, payload)
                VALUES (?, ?, 'translation.unit.attempt_requested', 1, ?, ?,
                        jsonb_build_object('jobId', ?::text, 'unitId', ?::text,
                                           'attemptId', ?::text, 'attemptNo', ?))
                """)) {
      insert.setObject(1, eventId);
      insert.setObject(2, jobId);
      insert.setString(3, "attempt-start-" + attemptId);
      insert.setObject(4, attemptId);
      insert.setObject(5, jobId);
      insert.setObject(6, unitId);
      insert.setObject(7, attemptId);
      insert.setInt(8, attemptNo);
      insert.executeUpdate();
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record UnitContext(
      UUID jobId,
      UUID sourceRevisionId,
      String unitStatus,
      String leaseOwner,
      UUID leaseToken,
      int attemptCount,
      int maxAttempts,
      String jobStatus,
      String providerId,
      String sourceLanguage,
      String targetLanguage,
      byte[] sourceSha256,
      byte[] requestSha256,
      String profileSnapshot,
      String glossarySnapshot,
      boolean leaseLive) {}

  private record SegmentSnapshot(int ordinal, UUID id, byte[] checksum) {}

  private record ExistingAttempt(UUID id, String outcome, byte[] requestSha256) {}

  public record StartResult(
      UUID attemptId,
      UUID jobId,
      UUID unitId,
      int attemptNo,
      String providerId,
      byte[] requestSha256,
      String outcome,
      boolean replayed,
      boolean dispatchAllowed) {
    public StartResult {
      requestSha256 = requestSha256.clone();
    }

    @Override
    public byte[] requestSha256() {
      return requestSha256.clone();
    }
  }

  public static final class StaleLeaseException extends RuntimeException {}

  public static final class AttemptsExhaustedException extends RuntimeException {}

  public static final class UnresolvedAttemptException extends RuntimeException {}

  public static final class AttemptConflictException extends RuntimeException {}

  public static final class InvalidAttemptContextException extends RuntimeException {}

  public static final class AttemptNotAllowedException extends RuntimeException {
    private final String reason;

    public AttemptNotAllowedException(String reason) {
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }
}
