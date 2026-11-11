package com.novelweb.library.reader;

import java.security.MessageDigest;
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
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Stores actor-owned bookmarks and keyset pages in the PostgreSQL profile. */
@Repository
@Profile("postgres")
public class ReaderBookmarkRepository {

  private static final String BOOKMARK_COLUMNS =
      "id, work_id, chapter_id, segment_anchor, label, created_at";
  private final DataSource dataSource;

  public ReaderBookmarkRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Page findForWork(
      UUID actorUserId, UUID workId, ReaderCursorCodec.KeysetPosition after, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      requireAccessibleWork(connection, actorUserId, workId, false);
      String keysetPredicate =
          after == null ? "" : " AND (b.created_at < ? OR (b.created_at = ? AND b.id < ?))";
      try (PreparedStatement select =
          connection.prepareStatement(
              "SELECT b."
                  + BOOKMARK_COLUMNS.replace(", ", ", b.")
                  + " FROM reader_bookmarks b JOIN chapters c ON c.id = b.chapter_id"
                  + " JOIN works w ON w.id = b.work_id"
                  + " JOIN users u ON u.id = ? AND u.status = 'active'"
                  + " WHERE b.user_id = ? AND b.work_id = ? AND c.status = 'ready'"
                  + " AND c.deleted_at IS NULL AND w.deleted_at IS NULL"
                  + " AND can_access_library(w.library_id, ?, FALSE)"
                  + keysetPredicate
                  + " ORDER BY b.created_at DESC, b.id DESC LIMIT ?")) {
        int index = 1;
        select.setObject(index++, actorUserId);
        select.setObject(index++, actorUserId);
        select.setObject(index++, workId);
        select.setObject(index++, actorUserId);
        if (after != null) {
          OffsetDateTime timestamp = OffsetDateTime.ofInstant(after.timestamp(), ZoneOffset.UTC);
          select.setObject(index++, timestamp);
          select.setObject(index++, timestamp);
          select.setObject(index++, after.id());
        }
        select.setInt(index, limit + 1);
        try (ResultSet rows = select.executeQuery()) {
          List<BookmarkRecord> result = new ArrayList<>(limit + 1);
          while (rows.next()) result.add(bookmark(rows));
          boolean hasMore = result.size() > limit;
          List<BookmarkRecord> items =
              hasMore ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
          return new Page(items, hasMore);
        }
      }
    } catch (BookmarkNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load Reader bookmarks", exception);
    }
  }

  public BookmarkRecord create(
      UUID actorUserId,
      UUID workId,
      UUID chapterId,
      String segmentAnchor,
      String label,
      String idempotencyKey,
      byte[] requestHash) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement removeExpired =
            connection.prepareStatement(
                "DELETE FROM reader_bookmark_idempotency "
                    + "WHERE user_id = ? AND expires_at <= clock_timestamp()")) {
          removeExpired.setObject(1, actorUserId);
          removeExpired.executeUpdate();
        }

        boolean claimed = claimIdempotency(connection, actorUserId, idempotencyKey, requestHash);
        if (!claimed) {
          BookmarkRecord replay =
              findIdempotentResponse(connection, actorUserId, idempotencyKey, requestHash);
          requireAccessibleWork(connection, actorUserId, replay.workId(), false);
          requireReadableChapter(
              connection, actorUserId, replay.workId(), replay.chapterId(), replay.segmentAnchor());
          connection.commit();
          return replay;
        }

        requireAccessibleWork(connection, actorUserId, workId, true);
        requireReadableChapter(connection, actorUserId, workId, chapterId, segmentAnchor);
        BookmarkRecord bookmark =
            findAtPosition(connection, actorUserId, workId, chapterId, segmentAnchor)
                .orElseGet(
                    () ->
                        insertBookmark(
                            connection, actorUserId, workId, chapterId, segmentAnchor, label));
        completeIdempotency(connection, actorUserId, idempotencyKey, requestHash, bookmark);
        connection.commit();
        return bookmark;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (BookmarkRepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to save Reader bookmark", exception);
    }
  }

  public void delete(UUID actorUserId, UUID bookmarkId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(bookmarkId, "bookmark id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM reader_bookmarks WHERE user_id = ? AND id = ?")) {
      delete.setObject(1, actorUserId);
      delete.setObject(2, bookmarkId);
      if (delete.executeUpdate() != 1) throw new BookmarkNotFoundException();
    } catch (BookmarkRepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to delete Reader bookmark", exception);
    }
  }

  private static boolean claimIdempotency(
      Connection connection, UUID actorUserId, String key, byte[] requestHash) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO reader_bookmark_idempotency "
                + "(user_id, idempotency_key, request_hash, response_bookmark_id, "
                + "response_work_id, response_chapter_id, response_segment_anchor, response_label, "
                + "response_created_at, expires_at) "
                + "VALUES (?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL, "
                + "clock_timestamp() + INTERVAL '24 hours') ON CONFLICT DO NOTHING")) {
      insert.setObject(1, actorUserId);
      insert.setString(2, key);
      insert.setBytes(3, requestHash);
      return insert.executeUpdate() == 1;
    }
  }

  private static BookmarkRecord findIdempotentResponse(
      Connection connection, UUID actorUserId, String key, byte[] requestHash) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT request_hash, response_bookmark_id, response_work_id, "
                + "response_chapter_id, response_segment_anchor, response_label, "
                + "response_created_at FROM reader_bookmark_idempotency "
                + "WHERE user_id = ? AND idempotency_key = ? FOR UPDATE")) {
      select.setObject(1, actorUserId);
      select.setString(2, key);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new IllegalStateException("Bookmark idempotency claim disappeared");
        if (!MessageDigest.isEqual(row.getBytes("request_hash"), requestHash)) {
          throw new BookmarkIdempotencyConflictException();
        }
        UUID bookmarkId = row.getObject("response_bookmark_id", UUID.class);
        if (bookmarkId == null)
          throw new IllegalStateException("Bookmark idempotency claim is incomplete");
        return new BookmarkRecord(
            bookmarkId,
            row.getObject("response_work_id", UUID.class),
            row.getObject("response_chapter_id", UUID.class),
            row.getString("response_segment_anchor"),
            row.getString("response_label"),
            instant(row, "response_created_at"));
      }
    }
  }

  private static void completeIdempotency(
      Connection connection,
      UUID actorUserId,
      String key,
      byte[] requestHash,
      BookmarkRecord response)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE reader_bookmark_idempotency SET request_hash = ?, response_bookmark_id = ?, "
                + "response_work_id = ?, response_chapter_id = ?, response_segment_anchor = ?, "
                + "response_label = ?, response_created_at = ? "
                + "WHERE user_id = ? AND idempotency_key = ?")) {
      update.setBytes(1, requestHash);
      update.setObject(2, response.id());
      update.setObject(3, response.workId());
      update.setObject(4, response.chapterId());
      update.setString(5, response.segmentAnchor());
      update.setString(6, response.label());
      update.setObject(7, OffsetDateTime.ofInstant(response.createdAt(), ZoneOffset.UTC));
      update.setObject(8, actorUserId);
      update.setString(9, key);
      if (update.executeUpdate() != 1)
        throw new SQLException("Bookmark idempotency row disappeared");
    }
  }

  private static void requireAccessibleWork(
      Connection connection, UUID actorUserId, UUID workId, boolean lock) throws SQLException {
    String lockClause = lock ? " FOR UPDATE OF w" : "";
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT w.id FROM works w JOIN users u ON u.id = ? AND u.status = 'active' "
                + "WHERE w.id = ? AND w.deleted_at IS NULL "
                + "AND can_access_library(w.library_id, ?, FALSE)"
                + lockClause)) {
      select.setObject(1, actorUserId);
      select.setObject(2, workId);
      select.setObject(3, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new BookmarkNotFoundException();
      }
    }
  }

  private static void requireReadableChapter(
      Connection connection, UUID actorUserId, UUID workId, UUID chapterId, String segmentAnchor)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT c.id FROM chapters c JOIN works w ON w.id = c.work_id "
                + "WHERE c.id = ? AND c.work_id = ? AND c.status = 'ready' "
                + "AND c.source_revision_id IS NOT NULL AND c.deleted_at IS NULL "
                + "AND w.deleted_at IS NULL AND can_access_library(w.library_id, ?, FALSE) "
                + "FOR UPDATE OF c")) {
      select.setObject(1, chapterId);
      select.setObject(2, workId);
      select.setObject(3, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new InvalidBookmarkException();
      }
    }
    if (segmentAnchor == null) return;
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT 1 FROM segments s JOIN chapter_revisions r "
                + "ON r.id = s.chapter_revision_id WHERE r.chapter_id = ? "
                + "AND s.stable_anchor = ? AND s.segment_type IN ('paragraph', 'heading', 'quote') "
                + "LIMIT 1")) {
      select.setObject(1, chapterId);
      select.setString(2, segmentAnchor);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new InvalidBookmarkException();
      }
    }
  }

  private static Optional<BookmarkRecord> findAtPosition(
      Connection connection, UUID actorUserId, UUID workId, UUID chapterId, String segmentAnchor)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT "
                + BOOKMARK_COLUMNS
                + " FROM reader_bookmarks "
                + "WHERE user_id = ? AND work_id = ? AND chapter_id = ? "
                + "AND segment_anchor IS NOT DISTINCT FROM ?")) {
      select.setObject(1, actorUserId);
      select.setObject(2, workId);
      select.setObject(3, chapterId);
      select.setString(4, segmentAnchor);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(bookmark(row)) : Optional.empty();
      }
    }
  }

  private static BookmarkRecord insertBookmark(
      Connection connection,
      UUID actorUserId,
      UUID workId,
      UUID chapterId,
      String segmentAnchor,
      String label) {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO reader_bookmarks (user_id, work_id, chapter_id, segment_anchor, label) "
                + "VALUES (?, ?, ?, ?, ?) RETURNING "
                + BOOKMARK_COLUMNS)) {
      insert.setObject(1, actorUserId);
      insert.setObject(2, workId);
      insert.setObject(3, chapterId);
      insert.setString(4, segmentAnchor);
      insert.setString(5, label);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Reader bookmark insert returned no row");
        return bookmark(row);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to create Reader bookmark", exception);
    }
  }

  private static BookmarkRecord bookmark(ResultSet row) throws SQLException {
    return new BookmarkRecord(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getObject("chapter_id", UUID.class),
        row.getString("segment_anchor"),
        row.getString("label"),
        instant(row, "created_at"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public record BookmarkRecord(
      UUID id,
      UUID workId,
      UUID chapterId,
      String segmentAnchor,
      String label,
      Instant createdAt) {}

  public record Page(List<BookmarkRecord> items, boolean hasMore) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public abstract static class BookmarkRepositoryException extends RuntimeException {}

  public static final class BookmarkNotFoundException extends BookmarkRepositoryException {}

  public static final class InvalidBookmarkException extends BookmarkRepositoryException {}

  public static final class BookmarkIdempotencyConflictException
      extends BookmarkRepositoryException {}
}
