package com.novelweb.library.publication;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for scheduled publication and its bounded worker lease. */
@Repository
@Profile("postgres")
public class PublicationScheduleRepository {

  private static final int BATCH_SIZE = 20;
  private static final int LEASE_SECONDS = 60;

  private final DataSource dataSource;

  public PublicationScheduleRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public List<ScheduleClaim> claimDueBatch() {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement claim =
          connection.prepareStatement(
              """
                  WITH due AS (
                      SELECT id
                        FROM publication_release_schedules
                       WHERE (state = 'SCHEDULED' AND scheduled_at <= clock_timestamp())
                          OR (state = 'PROCESSING' AND lease_until <= clock_timestamp())
                       ORDER BY scheduled_at, id
                       LIMIT ?
                       FOR UPDATE SKIP LOCKED
                  )
                  UPDATE publication_release_schedules schedule
                     SET state = 'PROCESSING',
                         attempt_count = schedule.attempt_count + 1,
                         lease_token = gen_random_uuid(),
                         lease_until = clock_timestamp() + (? * INTERVAL '1 second')
                    FROM due
                   WHERE schedule.id = due.id
                  RETURNING schedule.id, schedule.work_id, schedule.request_id,
                            schedule.owner_user_id, schedule.readiness_etag,
                            schedule.lease_token
                  """)) {
        claim.setInt(1, BATCH_SIZE);
        claim.setInt(2, LEASE_SECONDS);
        List<ScheduleClaim> claims = new ArrayList<>();
        try (ResultSet rows = claim.executeQuery()) {
          while (rows.next()) {
            claims.add(
                new ScheduleClaim(
                    rows.getObject("id", UUID.class),
                    rows.getObject("work_id", UUID.class),
                    rows.getObject("request_id", UUID.class),
                    rows.getObject("owner_user_id", UUID.class),
                    rows.getString("readiness_etag"),
                    rows.getObject("lease_token", UUID.class)));
          }
        }
        connection.commit();
        return List.copyOf(claims);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to claim due publication schedules", exception);
    }
  }

  public void markPublished(ScheduleClaim claim, UUID publicationEventId) {
    updateTerminalState(claim, "PUBLISHED", publicationEventId, null);
  }

  public void markBlocked(ScheduleClaim claim, String failureCode) {
    updateTerminalState(claim, "BLOCKED", null, failureCode);
  }

  private void updateTerminalState(
      ScheduleClaim claim, String state, UUID publicationEventId, String failureCode) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement update =
          connection.prepareStatement(
              """
                  UPDATE publication_release_schedules
                     SET state = ?, lease_token = NULL, lease_until = NULL,
                         publication_event_id = ?, failure_code = ?
                   WHERE id = ? AND state = 'PROCESSING' AND lease_token = ?
                   RETURNING work_id
                  """)) {
        update.setString(1, state);
        update.setObject(2, publicationEventId);
        update.setString(3, failureCode);
        update.setObject(4, claim.scheduleId());
        update.setObject(5, claim.leaseToken());
        try (ResultSet row = update.executeQuery()) {
          if (!row.next()) {
            connection.commit();
            return;
          }
          insertEvent(
              connection,
              claim.scheduleId(),
              row.getObject("work_id", UUID.class),
              null,
              state,
              failureCode);
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to finalize a publication schedule", exception);
    }
  }

  public CancelOutcome cancel(UUID actorId, UUID workId, UUID scheduleId) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement select =
          connection.prepareStatement(
              """
                  SELECT state FROM publication_release_schedules
                   WHERE id = ? AND work_id = ? AND owner_user_id = ?
                   FOR UPDATE
                  """)) {
        select.setObject(1, scheduleId);
        select.setObject(2, workId);
        select.setObject(3, actorId);
        String state;
        try (ResultSet row = select.executeQuery()) {
          if (!row.next()) {
            connection.commit();
            return CancelOutcome.NOT_FOUND;
          }
          state = row.getString("state");
        }
        if ("CANCELLED".equals(state)) {
          connection.commit();
          return CancelOutcome.ALREADY_CANCELLED;
        }
        if (!"SCHEDULED".equals(state)) {
          connection.commit();
          return CancelOutcome.TOO_LATE;
        }
        UUID work = scheduleWorkId(connection, scheduleId);
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE publication_release_schedules
                       SET state = 'CANCELLED'
                     WHERE id = ? AND state = 'SCHEDULED'
                    """)) {
          update.setObject(1, scheduleId);
          if (update.executeUpdate() != 1) throw new SQLException("Schedule state changed");
        }
        insertEvent(connection, scheduleId, work, actorId, "CANCELLED", null);
        connection.commit();
        return CancelOutcome.CANCELLED;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to cancel publication schedule", exception);
    }
  }

  public Optional<ScheduleView> findForOwner(UUID actorId, UUID workId, UUID scheduleId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, work_id, request_id, scheduled_at, state, failure_code,
                           attempt_count, created_at, updated_at
                      FROM publication_release_schedules
                     WHERE id = ? AND work_id = ? AND owner_user_id = ?
                    """)) {
      select.setObject(1, scheduleId);
      select.setObject(2, workId);
      select.setObject(3, actorId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readView(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read owner publication schedule", exception);
    }
  }

  public Optional<ScheduleView> findActiveForOwner(UUID actorId, UUID workId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, work_id, request_id, scheduled_at, state, failure_code,
                           attempt_count, created_at, updated_at
                      FROM publication_release_schedules
                     WHERE owner_user_id = ? AND work_id = ? AND state IN ('SCHEDULED', 'PROCESSING')
                     ORDER BY created_at DESC, id DESC
                     LIMIT 1
                    """)) {
      select.setObject(1, actorId);
      select.setObject(2, workId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readView(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to read active owner publication schedule", exception);
    }
  }

  private static ScheduleView readView(ResultSet row) throws SQLException {
    return new ScheduleView(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getObject("request_id", UUID.class),
        row.getObject("scheduled_at", OffsetDateTime.class).toInstant(),
        row.getString("state"),
        row.getString("failure_code"),
        row.getInt("attempt_count"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        row.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static UUID scheduleWorkId(Connection connection, UUID scheduleId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT work_id FROM publication_release_schedules WHERE id = ?")) {
      select.setObject(1, scheduleId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("The locked publication schedule disappeared");
        return row.getObject("work_id", UUID.class);
      }
    }
  }

  private static void insertEvent(
      Connection connection,
      UUID scheduleId,
      UUID workId,
      UUID actorId,
      String action,
      String failureCode)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO publication_release_schedule_events (
                    id, schedule_id, work_id, actor_user_id, action_code, safe_failure_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, scheduleId);
      insert.setObject(3, workId);
      insert.setObject(4, actorId);
      insert.setString(5, action);
      insert.setString(6, failureCode);
      insert.executeUpdate();
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  public enum CancelOutcome {
    CANCELLED,
    ALREADY_CANCELLED,
    TOO_LATE,
    NOT_FOUND
  }

  public record ScheduleClaim(
      UUID scheduleId,
      UUID workId,
      UUID requestId,
      UUID ownerId,
      String readinessEtag,
      UUID leaseToken) {}

  public record ScheduleView(
      UUID scheduleId,
      UUID workId,
      UUID requestId,
      java.time.Instant scheduledAt,
      String state,
      String failureCode,
      int attemptCount,
      java.time.Instant createdAt,
      java.time.Instant updatedAt) {}
}
