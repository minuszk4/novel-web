package com.novelweb.library.publication;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Delivers public chapter release events to current, opted-in in-app followers. */
@Service
@Profile("postgres")
public class ChapterPublishedNotificationDeliveryService {

  private static final int BATCH_SIZE = 20;
  private static final int MAX_ATTEMPTS = 8;
  private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
  private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(15);

  private final DataSource dataSource;

  public ChapterPublishedNotificationDeliveryService(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  /** Runs one bounded, database-only delivery batch. */
  public int processDueBatch() {
    return processDueEvents(null);
  }

  /** Work-scoped entry point for deterministic integration checks and operations. */
  public int processDueEventsForWork(UUID workId) {
    return processDueEvents(Objects.requireNonNull(workId, "work id is required"));
  }

  private int processDueEvents(UUID workId) {
    List<ClaimedEvent> claimed = claimDueEvents(workId);
    for (ClaimedEvent event : claimed) {
      try {
        deliver(event);
      } catch (RuntimeException exception) {
        recordFailure(event);
      }
    }
    return claimed.size();
  }

  private List<ClaimedEvent> claimDueEvents(UUID workId) {
    List<ClaimedEvent> claimed = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement failExpired =
            connection.prepareStatement(
                """
                    WITH exhausted AS (
                        SELECT id
                          FROM chapter_published_outbox_events
                         WHERE status = 'leased' AND lease_expires_at <= clock_timestamp()
                           AND attempts >= ?
                         ORDER BY lease_expires_at, id
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED
                    )
                    UPDATE chapter_published_outbox_events event
                       SET status = 'failed', lease_token = NULL, lease_expires_at = NULL,
                           last_safe_failure_code = 'LEASE_EXPIRED'
                      FROM exhausted
                     WHERE event.id = exhausted.id
                    """)) {
          failExpired.setInt(1, MAX_ATTEMPTS);
          failExpired.setInt(2, BATCH_SIZE);
          failExpired.executeUpdate();
        }

        String workFilter = workId == null ? "" : " AND work_id = ?";
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, work_id, attempts, status
                      FROM chapter_published_outbox_events
                     WHERE ((status IN ('queued', 'retry') AND next_attempt_at <= clock_timestamp())
                        OR (status = 'leased' AND lease_expires_at <= clock_timestamp()))
                       AND attempts < ?
                    """
                    + workFilter
                    + " ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED")) {
          select.setInt(1, MAX_ATTEMPTS);
          if (workId != null) select.setObject(2, workId);
          select.setInt(workId == null ? 2 : 3, BATCH_SIZE);
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
              claimed.add(
                  new ClaimedEvent(
                      rows.getObject("id", UUID.class),
                      rows.getObject("work_id", UUID.class),
                      rows.getInt("attempts") + 1,
                      UUID.randomUUID()));
            }
          }
        }

        for (ClaimedEvent event : claimed) {
          try (PreparedStatement update =
              connection.prepareStatement(
                  """
                      UPDATE chapter_published_outbox_events
                         SET status = 'leased', attempts = ?, lease_token = ?,
                             lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                             last_safe_failure_code = CASE
                                 WHEN status = 'leased' THEN 'LEASE_EXPIRED'
                                 ELSE last_safe_failure_code
                             END
                       WHERE id = ?
                      """)) {
            update.setInt(1, event.attemptNumber());
            update.setObject(2, event.leaseToken());
            update.setLong(3, LEASE_DURATION.toMillis());
            update.setObject(4, event.id());
            if (update.executeUpdate() != 1) {
              throw new SQLException("Notification outbox lease was not recorded");
            }
          }
        }
        connection.commit();
        return List.copyOf(claimed);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to claim chapter notification events", exception);
    }
  }

  private void deliver(ClaimedEvent claimed) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Event event = lockClaim(connection, claimed);
        if (event == null) {
          connection.commit();
          return;
        }

        lockWork(connection, event.workId());
        if (isCurrentlyPublic(connection, event)) {
          insertNotificationsForCurrentFollowers(connection, event);
        }
        complete(connection, claimed);
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw new IllegalStateException("Chapter notification delivery failed", exception);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Chapter notification delivery failed", exception);
    }
  }

  private static Event lockClaim(Connection connection, ClaimedEvent claimed) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT event.release_event_id, event.work_id, event.chapter_id
                  FROM chapter_published_outbox_events event
                 WHERE event.id = ? AND event.status = 'leased' AND event.lease_token = ?
                   AND event.lease_expires_at > clock_timestamp()
                 FOR UPDATE OF event
                """)) {
      select.setObject(1, claimed.id());
      select.setObject(2, claimed.leaseToken());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return null;
        return new Event(
            row.getObject("release_event_id", UUID.class),
            row.getObject("work_id", UUID.class),
            row.getObject("chapter_id", UUID.class));
      }
    }
  }

  private static void lockWork(Connection connection, UUID workId) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT id FROM works WHERE id = ? FOR SHARE")) {
      lock.setObject(1, workId);
      try (ResultSet row = lock.executeQuery()) {
        if (!row.next()) throw new SQLException("Notification work no longer exists");
      }
    }
  }

  private static boolean isCurrentlyPublic(Connection connection, Event event) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT publication_scope_access_is_current(?, 'PUBLIC_METADATA')
                   AND EXISTS (
                       SELECT 1 FROM chapters chapter
                        WHERE chapter.work_id = ? AND chapter.id = ?
                          AND chapter.status = 'ready' AND chapter.deleted_at IS NULL
                   )
                """)) {
      select.setObject(1, event.workId());
      select.setObject(2, event.workId());
      select.setObject(3, event.chapterId());
      try (ResultSet row = select.executeQuery()) {
        return row.next() && row.getBoolean(1);
      }
    }
  }

  private static void insertNotificationsForCurrentFollowers(Connection connection, Event event)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                WITH recipients AS MATERIALIZED (
                    SELECT actor_user_id
                      FROM work_subscriptions
                     WHERE work_id = ? AND active = TRUE AND notifications_enabled = TRUE
                     ORDER BY actor_user_id
                     FOR UPDATE
                )
                INSERT INTO notifications (
                    id, actor_user_id, event_id, work_id, chapter_id,
                    notification_type, channel)
                SELECT gen_random_uuid(), recipient.actor_user_id, ?, ?, ?,
                       'CHAPTER_PUBLISHED', 'IN_APP'
                  FROM recipients recipient
                ON CONFLICT (actor_user_id, event_id, channel) DO NOTHING
                """)) {
      insert.setObject(1, event.workId());
      insert.setObject(2, event.id());
      insert.setObject(3, event.workId());
      insert.setObject(4, event.chapterId());
      insert.executeUpdate();
    }
  }

  private static void complete(Connection connection, ClaimedEvent claimed) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE chapter_published_outbox_events
                   SET status = 'completed', lease_token = NULL, lease_expires_at = NULL,
                       last_safe_failure_code = NULL
                 WHERE id = ? AND status = 'leased' AND lease_token = ?
                """)) {
      update.setObject(1, claimed.id());
      update.setObject(2, claimed.leaseToken());
      if (update.executeUpdate() != 1) throw new SQLException("Notification lease was lost");
    }
  }

  private void recordFailure(ClaimedEvent claimed) {
    boolean terminal = claimed.attemptNumber() >= MAX_ATTEMPTS;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE chapter_published_outbox_events
                       SET status = ?, lease_token = NULL, lease_expires_at = NULL,
                           next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                           last_safe_failure_code = 'DELIVERY_FAILED'
                     WHERE id = ? AND status = 'leased' AND lease_token = ?
                    """)) {
      update.setString(1, terminal ? "failed" : "retry");
      update.setLong(2, retryDelay(claimed.attemptNumber()).toMillis());
      update.setObject(3, claimed.id());
      update.setObject(4, claimed.leaseToken());
      update.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to record chapter notification failure", exception);
    }
  }

  private static Duration retryDelay(int attemptNumber) {
    long seconds = Math.min(15L * 60L, 10L << Math.max(0, attemptNumber - 1));
    return Duration.ofSeconds(seconds);
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private record ClaimedEvent(UUID id, UUID workId, int attemptNumber, UUID leaseToken) {}

  private record Event(UUID id, UUID workId, UUID chapterId) {}
}
