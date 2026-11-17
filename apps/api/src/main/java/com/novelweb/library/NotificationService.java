package com.novelweb.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Actor-scoped in-app inbox with current-public metadata masking. */
@Service
@Profile("postgres")
public class NotificationService {

  private static final int MAX_PAGE_SIZE = 50;
  private static final int READ_ALL_BATCH_SIZE = 500;

  private final DataSource dataSource;
  private final LibraryCursorCodec cursorCodec;

  public NotificationService(DataSource dataSource, LibraryCursorCodec cursorCodec) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursor codec is required");
  }

  public Page list(UUID actorUserId, String rawCursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (limit < 1 || limit > MAX_PAGE_SIZE) throw new InvalidLimitException();

    LibraryCursorCodec.CursorContext context = LibraryCursorCodec.notificationsContext(actorUserId);
    LibraryCursorCodec.Position position = null;
    if (rawCursor != null) {
      cursorCodec.requireConfigured();
      position = cursorCodec.decode(rawCursor, context);
      if (position.rank() != 0) throw new LibraryCursorCodec.InvalidCursorException();
    }

    List<NotificationItem> items = readPage(actorUserId, position, limit + 1);
    String nextCursor = null;
    if (items.size() > limit) {
      items.remove(items.size() - 1);
      NotificationItem visibleLast = items.get(items.size() - 1);
      nextCursor =
          cursorCodec.encode(
              context,
              new LibraryCursorCodec.Position(
                  0, visibleLast.createdAt(), visibleLast.notificationId()));
    }
    return new Page(items, nextCursor, unreadCount(actorUserId));
  }

  public ReadReceipt markRead(UUID actorUserId, UUID notificationId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(notificationId, "notification id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE notifications
                       SET read_at = COALESCE(read_at, clock_timestamp())
                     WHERE id = ? AND actor_user_id = ?
                    RETURNING id, read_at
                    """)) {
      update.setObject(1, notificationId);
      update.setObject(2, actorUserId);
      try (ResultSet row = update.executeQuery()) {
        if (!row.next()) throw new NotificationNotFoundException();
        OffsetDateTime readAt = row.getObject("read_at", OffsetDateTime.class);
        return new ReadReceipt(row.getObject("id", UUID.class), readAt.toInstant());
      }
    } catch (SQLException exception) {
      throw new NotificationStoreUnavailableException(exception);
    }
  }

  /** Marks at most 500 unread rows per call so a large inbox never creates an unbounded update. */
  public ReadAllReceipt markAllRead(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        int updated;
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                    WITH batch AS (
                        SELECT id
                          FROM notifications
                         WHERE actor_user_id = ? AND read_at IS NULL
                         ORDER BY created_at, id
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED
                    )
                    UPDATE notifications notification
                       SET read_at = clock_timestamp()
                      FROM batch
                     WHERE notification.id = batch.id
                    """)) {
          update.setObject(1, actorUserId);
          update.setInt(2, READ_ALL_BATCH_SIZE);
          updated = update.executeUpdate();
        }
        boolean hasMore = hasUnreadRows(connection, actorUserId);
        connection.commit();
        return new ReadAllReceipt(updated, hasMore);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new NotificationStoreUnavailableException(exception);
    }
  }

  private List<NotificationItem> readPage(
      UUID actorUserId, LibraryCursorCodec.Position position, int fetchSize) {
    List<NotificationItem> items = new ArrayList<>();
    String cursorPredicate =
        position == null ? "" : "AND (notification.created_at, notification.id) < (?, ?)";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT notification.id, notification.created_at, notification.read_at,
                           access.available,
                           CASE WHEN access.available THEN work.id END AS work_id,
                           CASE WHEN access.available THEN work.title END AS work_title,
                           CASE WHEN access.available THEN chapter.id END AS chapter_id,
                           CASE WHEN access.available THEN chapter.title END AS chapter_title,
                           CASE WHEN access.available THEN chapter.ordinal END AS chapter_ordinal
                      FROM notifications notification
                      JOIN works work ON work.id = notification.work_id
                      JOIN chapters chapter
                        ON chapter.work_id = notification.work_id
                       AND chapter.id = notification.chapter_id
                      CROSS JOIN LATERAL (
                          SELECT work.visibility = 'public'
                             AND work.status = 'active'
                             AND work.deleted_at IS NULL
                             AND chapter.status = 'ready'
                             AND chapter.deleted_at IS NULL
                             AND publication_scope_access_is_current(work.id, 'PUBLIC_METADATA')
                                 AS available
                      ) access
                     WHERE notification.actor_user_id = ?
                    """
                    + cursorPredicate
                    + " ORDER BY notification.created_at DESC, notification.id DESC LIMIT ?")) {
      int parameter = 1;
      select.setObject(parameter++, actorUserId);
      if (position != null) {
        select.setObject(
            parameter++, OffsetDateTime.ofInstant(position.createdAt(), ZoneOffset.UTC));
        select.setObject(parameter++, position.itemId());
      }
      select.setInt(parameter, fetchSize);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          boolean available = rows.getBoolean("available");
          OffsetDateTime readAt = rows.getObject("read_at", OffsetDateTime.class);
          Integer chapterOrdinal = rows.getObject("chapter_ordinal", Integer.class);
          items.add(
              new NotificationItem(
                  rows.getObject("id", UUID.class),
                  available ? "CHAPTER_PUBLISHED" : "UPDATE_UNAVAILABLE",
                  rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                  readAt == null ? null : readAt.toInstant(),
                  available,
                  rows.getObject("work_id", UUID.class),
                  rows.getString("work_title"),
                  rows.getObject("chapter_id", UUID.class),
                  rows.getString("chapter_title"),
                  chapterOrdinal));
        }
      }
      return items;
    } catch (SQLException exception) {
      throw new NotificationStoreUnavailableException(exception);
    }
  }

  private int unreadCount(UUID actorUserId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM notifications WHERE actor_user_id = ? AND read_at IS NULL")) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Unread notification count was unavailable");
        return row.getInt(1);
      }
    } catch (SQLException exception) {
      throw new NotificationStoreUnavailableException(exception);
    }
  }

  private static boolean hasUnreadRows(Connection connection, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM notifications WHERE actor_user_id = ? AND read_at IS NULL)")) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Unread notification state was unavailable");
        return row.getBoolean(1);
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

  public record Page(List<NotificationItem> items, String nextCursor, int unreadCount) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public record NotificationItem(
      UUID notificationId,
      String eventType,
      Instant createdAt,
      Instant readAt,
      boolean available,
      UUID workId,
      String workTitle,
      UUID chapterId,
      String chapterTitle,
      Integer chapterOrdinal) {}

  public record ReadReceipt(UUID notificationId, Instant readAt) {}

  public record ReadAllReceipt(int updatedCount, boolean hasMore) {}

  public static class InvalidLimitException extends RuntimeException {}

  public static class NotificationNotFoundException extends RuntimeException {}

  public static class NotificationStoreUnavailableException extends RuntimeException {
    NotificationStoreUnavailableException(Throwable cause) {
      super(cause);
    }
  }
}
