package com.novelweb.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Actor-owned persistence for the optional email digest schedule. */
@Repository
@Profile("postgres")
public class NotificationPreferencesRepository {

  private final DataSource dataSource;

  public NotificationPreferencesRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public State find(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT email_digest_enabled, digest_frequency, time_zone, send_local_time, version
                    FROM notification_preferences WHERE actor_user_id = ?
                    """)) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? state(row) : State.absent();
      }
    } catch (SQLException exception) {
      throw new StoreUnavailableException(exception);
    }
  }

  public State save(UUID actorUserId, long expectedVersion, Schedule schedule) {
    Objects.requireNonNull(actorUserId, "actor is required");
    Objects.requireNonNull(schedule, "schedule is required");
    if (expectedVersion < 0) throw new IllegalArgumentException("expected version is invalid");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        State current = lockCurrent(connection, actorUserId);
        if (matches(current, schedule)) {
          connection.commit();
          return current;
        }
        if (!current.exists() && expectedVersion != 0
            || current.exists() && current.version() != expectedVersion) {
          throw new StalePreferencesException();
        }
        State updated =
            current.exists()
                ? update(connection, actorUserId, current.version(), schedule)
                : insert(connection, actorUserId, schedule);
        connection.commit();
        return updated;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        if (exception instanceof SQLException sqlException
            && "23505".equals(sqlException.getSQLState())) {
          throw new StalePreferencesException();
        }
        throw exception;
      }
    } catch (SQLException exception) {
      throw new StoreUnavailableException(exception);
    }
  }

  private static State lockCurrent(Connection connection, UUID actorUserId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT email_digest_enabled, digest_frequency, time_zone, send_local_time, version
                FROM notification_preferences WHERE actor_user_id = ? FOR UPDATE
                """)) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? state(row) : State.absent();
      }
    }
  }

  private static State insert(Connection connection, UUID actorUserId, Schedule schedule)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO notification_preferences (
                    actor_user_id, email_digest_enabled, digest_frequency, time_zone,
                    send_local_time, version)
                VALUES (?, FALSE, ?, ?, ?, 1)
                """)) {
      insert.setObject(1, actorUserId);
      insert.setString(2, schedule.frequency());
      insert.setString(3, schedule.timeZone());
      insert.setObject(4, schedule.sendLocalTime());
      insert.executeUpdate();
      return new State(
          true, false, schedule.frequency(), schedule.timeZone(), schedule.sendLocalTime(), 1);
    }
  }

  private static State update(
      Connection connection, UUID actorUserId, long expectedVersion, Schedule schedule)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE notification_preferences
                SET email_digest_enabled = FALSE,
                    digest_frequency = ?, time_zone = ?, send_local_time = ?,
                    email_consent_policy_version = NULL, email_consented_at = NULL,
                    version = version + 1, updated_at = clock_timestamp()
                WHERE actor_user_id = ? AND version = ?
                """)) {
      update.setString(1, schedule.frequency());
      update.setString(2, schedule.timeZone());
      update.setObject(3, schedule.sendLocalTime());
      update.setObject(4, actorUserId);
      update.setLong(5, expectedVersion);
      if (update.executeUpdate() != 1) throw new StalePreferencesException();
      return new State(
          true,
          false,
          schedule.frequency(),
          schedule.timeZone(),
          schedule.sendLocalTime(),
          expectedVersion + 1);
    }
  }

  private static State state(ResultSet row) throws SQLException {
    return new State(
        true,
        row.getBoolean("email_digest_enabled"),
        row.getString("digest_frequency"),
        row.getString("time_zone"),
        row.getObject("send_local_time", LocalTime.class),
        row.getLong("version"));
  }

  private static boolean matches(State current, Schedule schedule) {
    return !current.emailDigestEnabled()
        && (!current.exists()
            ? schedule.frequency() == null
                && schedule.timeZone() == null
                && schedule.sendLocalTime() == null
            : Objects.equals(current.frequency(), schedule.frequency())
                && Objects.equals(current.timeZone(), schedule.timeZone())
                && Objects.equals(current.sendLocalTime(), schedule.sendLocalTime()));
  }

  private static void rollback(Connection connection, Throwable failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  public record State(
      boolean exists,
      boolean emailDigestEnabled,
      String frequency,
      String timeZone,
      LocalTime sendLocalTime,
      long version) {
    static State absent() {
      return new State(false, false, null, null, null, 0);
    }
  }

  public record Schedule(String frequency, String timeZone, LocalTime sendLocalTime) {}

  public static final class StalePreferencesException extends RuntimeException {}

  public static final class StoreUnavailableException extends RuntimeException {
    StoreUnavailableException(Throwable cause) {
      super("Notification preferences are unavailable", cause);
    }
  }
}
