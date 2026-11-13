package com.novelweb.progression.cultivation;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class CultivationReadingSessionRepository {

  private final DataSource dataSource;

  public CultivationReadingSessionRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public StartOutcome start(
      UUID actorUserId, UUID workId, UUID chapterId, String idempotencyKey, byte[] requestHash) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");
    Objects.requireNonNull(requestHash, "request hash is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireActiveUser(connection, actorUserId, true);
        expireIdleSessions(connection, actorUserId);
        ExistingStart existing = findStartByKey(connection, actorUserId, idempotencyKey);
        if (existing != null) {
          requireSameRequest(existing.requestHash(), requestHash);
          SessionSnapshot snapshot =
              findSession(connection, actorUserId, existing.sessionId(), false);
          connection.commit();
          return new StartOutcome(snapshot, true);
        }

        ProfileCatalog profile = lockEnabledProfile(connection, actorUserId);
        requireReadableChapter(connection, actorUserId, workId, chapterId);
        if (hasActiveSession(connection, actorUserId)) {
          throw new ActiveReadingSessionExistsException();
        }
        SessionSnapshot created =
            insertSession(
                connection,
                actorUserId,
                workId,
                chapterId,
                profile.catalogVersion(),
                profile.policyVersion(),
                idempotencyKey,
                requestHash);
        connection.commit();
        return new StartOutcome(created, false);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        if (exception instanceof SQLException sqlException
            && "23505".equals(sqlException.getSQLState())) {
          throw new ActiveReadingSessionExistsException();
        }
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to start cultivation reading session", exception);
    }
  }

  public HeartbeatOutcome heartbeat(
      UUID actorUserId,
      UUID sessionId,
      long sequence,
      boolean clientActive,
      String idempotencyKey,
      byte[] requestHash) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(sessionId, "session id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireActiveUser(connection, actorUserId, false);
        SessionSnapshot session = findSession(connection, actorUserId, sessionId, true);
        EventSnapshot replay = findEvent(connection, sessionId, idempotencyKey);
        if (replay != null) {
          requireSameRequest(replay.requestHash(), requestHash);
          connection.commit();
          return new HeartbeatOutcome(replay.result(), true);
        }
        if (!"active".equals(session.state())) {
          connection.commit();
          return new HeartbeatOutcome(
              new HeartbeatResult(
                  session.id(),
                  session.state(),
                  sequence,
                  0,
                  session.totalActiveSeconds(),
                  session.lastHeartbeatAt(),
                  expiresAt(session.lastHeartbeatAt())),
              false);
        }
        if (sequence != session.lastSequence() + 1) {
          throw new HeartbeatSequenceConflictException(session.lastSequence() + 1);
        }

        Instant receivedAt = databaseNow(connection);
        ProfileCatalog profile = findEnabledProfile(connection, actorUserId);
        boolean eligible =
            profile != null
                && profile.catalogVersion().equals(session.catalogVersion())
                && profile.policyVersion().equals(session.policyVersion());
        boolean readable =
            eligible
                && isReadableChapter(
                    connection, actorUserId, session.workId(), session.chapterId());
        boolean expired =
            CultivationReadingSessionService.isExpired(session.lastHeartbeatAt(), receivedAt);

        String resultState;
        int creditedSeconds;
        if (expired) {
          resultState = "expired";
          creditedSeconds = 0;
        } else if (!readable) {
          resultState = "revoked";
          creditedSeconds = 0;
        } else {
          resultState = "active";
          creditedSeconds =
              CultivationReadingSessionService.creditableSeconds(
                  session.lastHeartbeatAt(), receivedAt, session.lastClientActive());
        }

        long totalActiveSeconds = session.totalActiveSeconds() + creditedSeconds;
        updateHeartbeat(
            connection,
            sessionId,
            sequence,
            clientActive,
            receivedAt,
            creditedSeconds,
            totalActiveSeconds,
            resultState);
        HeartbeatResult result =
            new HeartbeatResult(
                sessionId,
                resultState,
                sequence,
                creditedSeconds,
                totalActiveSeconds,
                receivedAt,
                expiresAt(receivedAt));
        insertEvent(
            connection,
            sessionId,
            idempotencyKey,
            requestHash,
            sequence,
            clientActive,
            receivedAt,
            creditedSeconds,
            totalActiveSeconds,
            resultState);
        connection.commit();
        return new HeartbeatOutcome(result, false);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to accept cultivation heartbeat", exception);
    }
  }

  public SessionSnapshot close(UUID actorUserId, UUID sessionId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(sessionId, "session id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireActiveUser(connection, actorUserId, false);
        SessionSnapshot session = findSession(connection, actorUserId, sessionId, true);
        if (!"active".equals(session.state())) {
          connection.commit();
          return session;
        }

        Instant closedAt = databaseNow(connection);
        ProfileCatalog profile = findEnabledProfile(connection, actorUserId);
        boolean eligible =
            profile != null
                && profile.catalogVersion().equals(session.catalogVersion())
                && profile.policyVersion().equals(session.policyVersion());
        boolean readable =
            eligible
                && isReadableChapter(
                    connection, actorUserId, session.workId(), session.chapterId());
        boolean expired =
            CultivationReadingSessionService.isExpired(session.lastHeartbeatAt(), closedAt);

        String state;
        int creditedSeconds;
        if (expired) {
          state = "expired";
          creditedSeconds = 0;
        } else if (!readable) {
          state = "revoked";
          creditedSeconds = 0;
        } else {
          state = "closed";
          creditedSeconds =
              CultivationReadingSessionService.creditableSeconds(
                  session.lastHeartbeatAt(), closedAt, session.lastClientActive());
        }

        long totalActiveSeconds = session.totalActiveSeconds() + creditedSeconds;
        updateClosedSession(connection, sessionId, closedAt, totalActiveSeconds, state);
        SessionSnapshot closed =
            new SessionSnapshot(
                sessionId,
                state,
                session.startedAt(),
                session.lastHeartbeatAt(),
                closedAt,
                totalActiveSeconds,
                session.lastSequence(),
                false,
                session.workId(),
                session.chapterId(),
                session.catalogVersion(),
                session.policyVersion());
        connection.commit();
        return closed;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to close cultivation reading session", exception);
    }
  }

  public int purgeExpiredSessions() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                """
                    WITH expired AS (
                        SELECT id
                        FROM cultivation_reading_sessions
                        WHERE (state <> 'active'
                               AND closed_at < clock_timestamp() - INTERVAL '24 hours')
                           OR (state = 'active'
                               AND last_heartbeat_at < clock_timestamp() - INTERVAL '24 hours')
                        ORDER BY last_heartbeat_at
                        LIMIT 500
                        FOR UPDATE SKIP LOCKED
                    )
                    DELETE FROM cultivation_reading_sessions s
                    USING expired e
                    WHERE s.id = e.id
                    """)) {
      return delete.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to purge expired cultivation sessions", exception);
    }
  }

  private static void requireActiveUser(Connection connection, UUID userId, boolean exclusive)
      throws SQLException {
    String lock = exclusive ? " FOR UPDATE" : " FOR SHARE";
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM users WHERE id = ? AND status = 'active'" + lock)) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new ReadingSessionNotFoundException();
      }
    }
  }

  private static ProfileCatalog lockEnabledProfile(Connection connection, UUID userId)
      throws SQLException {
    ProfileCatalog profile = findEnabledProfile(connection, userId);
    if (profile == null) throw new CultivationProfileNotEnabledException();
    return profile;
  }

  private static ProfileCatalog findEnabledProfile(Connection connection, UUID userId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT p.enabled, p.catalog_version, c.policy_version
                FROM cultivation_profiles p
                JOIN cultivation_rank_catalogs c ON c.version = p.catalog_version
                WHERE p.user_id = ?
                """
                + " FOR SHARE OF p")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next() || !row.getBoolean("enabled")) return null;
        return new ProfileCatalog(
            row.getString("catalog_version"), row.getString("policy_version"));
      }
    }
  }

  private static void requireReadableChapter(
      Connection connection, UUID userId, UUID workId, UUID chapterId) throws SQLException {
    if (!isReadableChapter(connection, userId, workId, chapterId)) {
      throw new ReaderResourceUnavailableException();
    }
  }

  private static boolean isReadableChapter(
      Connection connection, UUID userId, UUID workId, UUID chapterId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT c.id
                FROM chapters c
                JOIN works w ON w.id = c.work_id
                JOIN chapter_revisions r
                  ON r.chapter_id = c.id AND r.id = c.source_revision_id
                WHERE c.id = ? AND c.work_id = ?
                  AND c.status = 'ready' AND c.deleted_at IS NULL
                  AND w.deleted_at IS NULL
                  AND can_access_library(w.library_id, ?, FALSE)
                FOR SHARE OF c, w
                """)) {
      select.setObject(1, chapterId);
      select.setObject(2, workId);
      select.setObject(3, userId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static ExistingStart findStartByKey(
      Connection connection, UUID userId, String idempotencyKey) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, start_request_sha256
                FROM cultivation_reading_sessions
                WHERE user_id = ? AND start_idempotency_key = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setString(2, idempotencyKey);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? new ExistingStart(
                row.getObject("id", UUID.class), row.getBytes("start_request_sha256"))
            : null;
      }
    }
  }

  private static boolean hasActiveSession(Connection connection, UUID userId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT 1 FROM cultivation_reading_sessions WHERE user_id = ? AND state = 'active'")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static void expireIdleSessions(Connection connection, UUID userId) throws SQLException {
    try (PreparedStatement expire =
        connection.prepareStatement(
            """
                UPDATE cultivation_reading_sessions
                SET state = 'expired', closed_at = clock_timestamp(), last_client_active = FALSE
                WHERE user_id = ? AND state = 'active'
                  AND last_heartbeat_at <= clock_timestamp()
                      - (? * INTERVAL '1 second')
                """)) {
      expire.setObject(1, userId);
      expire.setInt(2, CultivationReadingSessionService.IDLE_TIMEOUT_SECONDS);
      expire.executeUpdate();
    }
  }

  private static SessionSnapshot insertSession(
      Connection connection,
      UUID userId,
      UUID workId,
      UUID chapterId,
      String catalogVersion,
      String policyVersion,
      String idempotencyKey,
      byte[] requestHash)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO cultivation_reading_sessions
                    (user_id, work_id, chapter_id, catalog_version, policy_version,
                     start_idempotency_key, start_request_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id, state, started_at, last_heartbeat_at, closed_at,
                          total_active_seconds, last_sequence, last_client_active, work_id, chapter_id,
                          catalog_version, policy_version
                """)) {
      insert.setObject(1, userId);
      insert.setObject(2, workId);
      insert.setObject(3, chapterId);
      insert.setString(4, catalogVersion);
      insert.setString(5, policyVersion);
      insert.setString(6, idempotencyKey);
      insert.setBytes(7, requestHash);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Reading session insert returned no row");
        return snapshot(row);
      }
    }
  }

  private static SessionSnapshot findSession(
      Connection connection, UUID userId, UUID sessionId, boolean lock) throws SQLException {
    String lockClause = lock ? " FOR UPDATE" : "";
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, state, started_at, last_heartbeat_at, closed_at, total_active_seconds,
                       last_sequence, last_client_active, work_id, chapter_id,
                       catalog_version, policy_version
                FROM cultivation_reading_sessions
                WHERE id = ? AND user_id = ?
                """
                + lockClause)) {
      select.setObject(1, sessionId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new ReadingSessionNotFoundException();
        return snapshot(row);
      }
    }
  }

  private static SessionSnapshot snapshot(ResultSet row) throws SQLException {
    return new SessionSnapshot(
        row.getObject("id", UUID.class),
        row.getString("state"),
        instant(row, "started_at"),
        instant(row, "last_heartbeat_at"),
        instant(row, "closed_at"),
        row.getLong("total_active_seconds"),
        row.getLong("last_sequence"),
        row.getBoolean("last_client_active"),
        row.getObject("work_id", UUID.class),
        row.getObject("chapter_id", UUID.class),
        row.getString("catalog_version"),
        row.getString("policy_version"));
  }

  private static EventSnapshot findEvent(Connection connection, UUID sessionId, String key)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT request_sha256, session_id, result_state, sequence, credited_seconds,
                       total_active_seconds, received_at
                FROM cultivation_reading_session_events
                WHERE session_id = ? AND idempotency_key = ?
                """)) {
      select.setObject(1, sessionId);
      select.setString(2, key);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return null;
        return new EventSnapshot(
            row.getBytes("request_sha256"),
            new HeartbeatResult(
                row.getObject("session_id", UUID.class),
                row.getString("result_state"),
                row.getLong("sequence"),
                row.getInt("credited_seconds"),
                row.getLong("total_active_seconds"),
                instant(row, "received_at"),
                expiresAt(instant(row, "received_at"))));
      }
    }
  }

  private static void updateHeartbeat(
      Connection connection,
      UUID sessionId,
      long sequence,
      boolean clientActive,
      Instant receivedAt,
      int creditedSeconds,
      long totalActiveSeconds,
      String state)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE cultivation_reading_sessions
                SET last_heartbeat_at = ?, last_client_active = ?, last_sequence = ?,
                    total_active_seconds = ?, state = ?,
                    closed_at = ?
                WHERE id = ?
                """)) {
      update.setTimestamp(1, Timestamp.from(receivedAt));
      update.setBoolean(2, clientActive);
      update.setLong(3, sequence);
      update.setLong(4, totalActiveSeconds);
      update.setString(5, state);
      if ("active".equals(state)) {
        update.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
      } else {
        update.setTimestamp(6, Timestamp.from(receivedAt));
      }
      update.setObject(7, sessionId);
      if (update.executeUpdate() != 1) throw new SQLException("Reading session disappeared");
    }
  }

  private static void insertEvent(
      Connection connection,
      UUID sessionId,
      String idempotencyKey,
      byte[] requestHash,
      long sequence,
      boolean clientActive,
      Instant receivedAt,
      int creditedSeconds,
      long totalActiveSeconds,
      String state)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO cultivation_reading_session_events
                    (session_id, idempotency_key, request_sha256, sequence, client_active,
                     received_at, credited_seconds, total_active_seconds, result_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, sessionId);
      insert.setString(2, idempotencyKey);
      insert.setBytes(3, requestHash);
      insert.setLong(4, sequence);
      insert.setBoolean(5, clientActive);
      insert.setTimestamp(6, Timestamp.from(receivedAt));
      insert.setInt(7, creditedSeconds);
      insert.setLong(8, totalActiveSeconds);
      insert.setString(9, state);
      insert.executeUpdate();
    }
  }

  private static void updateClosedSession(
      Connection connection,
      UUID sessionId,
      Instant closedAt,
      long totalActiveSeconds,
      String state)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE cultivation_reading_sessions
                SET state = ?, closed_at = ?, last_heartbeat_at = ?,
                    last_client_active = FALSE, total_active_seconds = ?
                WHERE id = ?
                """)) {
      update.setString(1, state);
      update.setTimestamp(2, Timestamp.from(closedAt));
      update.setTimestamp(3, Timestamp.from(closedAt));
      update.setLong(4, totalActiveSeconds);
      update.setObject(5, sessionId);
      if (update.executeUpdate() != 1) throw new SQLException("Reading session disappeared");
    }
  }

  private static Instant databaseNow(Connection connection) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement("SELECT clock_timestamp()");
        ResultSet row = select.executeQuery()) {
      if (!row.next()) throw new SQLException("PostgreSQL did not return its current time");
      return instant(row, 1);
    }
  }

  private static Instant expiresAt(Instant lastHeartbeatAt) {
    return lastHeartbeatAt.plusSeconds(CultivationReadingSessionService.IDLE_TIMEOUT_SECONDS);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static Instant instant(ResultSet row, int column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value.toInstant();
  }

  private static void requireSameRequest(byte[] stored, byte[] requested) {
    if (!MessageDigest.isEqual(stored, requested)) {
      throw new IdempotencyKeyReusedException();
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record ProfileCatalog(String catalogVersion, String policyVersion) {}

  private record ExistingStart(UUID sessionId, byte[] requestHash) {}

  private record EventSnapshot(byte[] requestHash, HeartbeatResult result) {}

  public record StartOutcome(SessionSnapshot session, boolean replayed) {}

  public record HeartbeatOutcome(HeartbeatResult result, boolean replayed) {}

  public record SessionSnapshot(
      UUID id,
      String state,
      Instant startedAt,
      Instant lastHeartbeatAt,
      Instant closedAt,
      long totalActiveSeconds,
      long lastSequence,
      boolean lastClientActive,
      UUID workId,
      UUID chapterId,
      String catalogVersion,
      String policyVersion) {}

  public record HeartbeatResult(
      UUID sessionId,
      String state,
      long sequence,
      int creditedSeconds,
      long totalActiveSeconds,
      Instant receivedAt,
      Instant expiresAt) {}

  public static final class ActiveReadingSessionExistsException extends RuntimeException {}

  public static final class CultivationProfileNotEnabledException extends RuntimeException {}

  public static final class IdempotencyKeyReusedException extends RuntimeException {}

  public static final class HeartbeatSequenceConflictException extends RuntimeException {
    private final long expectedSequence;

    public HeartbeatSequenceConflictException(long expectedSequence) {
      this.expectedSequence = expectedSequence;
    }

    public long expectedSequence() {
      return expectedSequence;
    }
  }

  public static final class ReaderResourceUnavailableException extends RuntimeException {}

  public static final class ReadingSessionNotFoundException extends RuntimeException {}
}
