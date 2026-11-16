package com.novelweb.library.reader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Owner/workspace-scoped reads for the source-only Reader. */
@Repository
@Profile("postgres")
public class ReaderRepository {

  private final DataSource dataSource;

  public ReaderRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Optional<ReaderWork> findAccessibleWork(UUID actorUserId, UUID workId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT w.id, w.library_id, w.title, w.author, w.source_language,
                           w.visibility, w.created_at, count(c.id) AS chapter_count
                    FROM works w
                    LEFT JOIN chapters c
                      ON c.work_id = w.id AND c.deleted_at IS NULL
                    WHERE w.id = ? AND w.deleted_at IS NULL
                      AND (can_access_library(w.library_id, ?, FALSE)
                           OR (w.visibility = 'public'
                               AND publication_scope_access_is_current(w.id, 'PUBLIC_METADATA')))
                    GROUP BY w.id
                    """)) {
      select.setObject(1, workId);
      select.setObject(2, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readerWork(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the Reader work manifest", exception);
    }
  }

  public Page<ReaderChapter> findChaptersAfter(
      UUID actorUserId, UUID workId, int afterOrdinal, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT c.id, c.ordinal, c.title,
                           CASE
                             WHEN c.status = 'ready' AND c.source_revision_id IS NOT NULL
                                  AND (can_access_library(w.library_id, ?, FALSE)
                                       OR (w.visibility = 'public'
                                           AND publication_scope_access_is_current(
                                               w.id, 'PUBLIC_METADATA')
                                           AND publication_scope_access_is_current(
                                               w.id, 'CHAPTER_TEXT')
                                           AND EXISTS (
                                               SELECT 1 FROM content_entitlements e
                                               WHERE e.user_id = ? AND e.chapter_id = c.id)))
                               THEN 'readable'
                             WHEN w.visibility = 'public'
                                  AND publication_scope_access_is_current(
                                      w.id, 'PUBLIC_METADATA')
                                  AND publication_scope_access_is_current(w.id, 'CHAPTER_TEXT')
                                  AND publication_scope_access_is_current(w.id, 'COMMERCIAL_SALE')
                                  AND c.status = 'ready'
                                  AND c.source_revision_id IS NOT NULL
                               THEN 'locked'
                             ELSE 'unavailable'
                           END AS access_state
                    FROM chapters c
                    JOIN works w ON w.id = c.work_id
                    WHERE c.work_id = ? AND c.ordinal > ? AND c.deleted_at IS NULL
                      AND w.deleted_at IS NULL
                      AND (can_access_library(w.library_id, ?, FALSE)
                           OR (w.visibility = 'public'
                               AND publication_scope_access_is_current(w.id, 'PUBLIC_METADATA')))
                    ORDER BY c.ordinal ASC
                    LIMIT ?
                    """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, actorUserId);
      select.setObject(3, workId);
      select.setInt(4, afterOrdinal);
      select.setObject(5, actorUserId);
      select.setInt(6, limit + 1);
      try (ResultSet rows = select.executeQuery()) {
        List<ReaderChapter> chapters = new ArrayList<>(limit + 1);
        while (rows.next()) chapters.add(readerChapter(rows));
        return page(chapters, limit, ReaderChapter::ordinal);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load Reader chapters", exception);
    }
  }

  public Optional<ReadableChapter> findReadableChapter(
      UUID actorUserId, UUID chapterId, UUID requestedRevisionId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT c.id, c.work_id, c.source_language, r.id AS revision_id
                    FROM chapters c
                    JOIN works w ON w.id = c.work_id
                    JOIN chapter_revisions r
                      ON r.chapter_id = c.id
                     AND r.id = COALESCE(CAST(? AS UUID), c.source_revision_id)
                    WHERE c.id = ? AND c.status = 'ready' AND c.deleted_at IS NULL
                      AND w.deleted_at IS NULL
                      AND (can_access_library(w.library_id, ?, FALSE)
                           OR (w.visibility = 'public'
                               AND r.id = c.source_revision_id
                               AND publication_scope_access_is_current(w.id, 'PUBLIC_METADATA')
                               AND publication_scope_access_is_current(w.id, 'CHAPTER_TEXT')
                               AND EXISTS (
                                   SELECT 1 FROM content_entitlements e
                                   WHERE e.user_id = ? AND e.chapter_id = c.id)))
                    """)) {
      select.setObject(1, requestedRevisionId);
      select.setObject(2, chapterId);
      select.setObject(3, actorUserId);
      select.setObject(4, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readableChapter(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to authorize the requested Reader chapter", exception);
    }
  }

  public Page<SourceSegment> findSegmentsAfter(
      UUID actorUserId, UUID chapterId, UUID revisionId, int afterOrdinal, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    Objects.requireNonNull(revisionId, "revision id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT s.ordinal, s.segment_type, s.stable_anchor, s.source_text
                    FROM segments s
                    JOIN chapter_revisions r ON r.id = s.chapter_revision_id
                    JOIN chapters c ON c.id = r.chapter_id
                    JOIN works w ON w.id = c.work_id
                    WHERE c.id = ? AND r.id = ? AND s.ordinal > ?
                      AND c.status = 'ready' AND c.deleted_at IS NULL AND w.deleted_at IS NULL
                      AND (can_access_library(w.library_id, ?, FALSE)
                           OR (w.visibility = 'public'
                               AND r.id = c.source_revision_id
                               AND publication_scope_access_is_current(w.id, 'PUBLIC_METADATA')
                               AND publication_scope_access_is_current(w.id, 'CHAPTER_TEXT')
                               AND EXISTS (
                                   SELECT 1 FROM content_entitlements e
                                   WHERE e.user_id = ? AND e.chapter_id = c.id)))
                      AND s.segment_type IN ('paragraph', 'heading', 'quote')
                    ORDER BY s.ordinal ASC
                    LIMIT ?
                    """)) {
      select.setObject(1, chapterId);
      select.setObject(2, revisionId);
      select.setInt(3, afterOrdinal);
      select.setObject(4, actorUserId);
      select.setObject(5, actorUserId);
      select.setInt(6, limit + 1);
      try (ResultSet rows = select.executeQuery()) {
        List<SourceSegment> segments = new ArrayList<>(limit + 1);
        while (rows.next()) segments.add(sourceSegment(rows));
        return page(segments, limit, SourceSegment::ordinal);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load Reader source segments", exception);
    }
  }

  private static ReaderWork readerWork(ResultSet row) throws SQLException {
    return new ReaderWork(
        row.getObject("id", UUID.class),
        row.getObject("library_id", UUID.class),
        row.getString("title"),
        row.getString("author"),
        row.getString("source_language"),
        row.getString("visibility"),
        instant(row, "created_at"),
        row.getInt("chapter_count"));
  }

  private static ReaderChapter readerChapter(ResultSet row) throws SQLException {
    return new ReaderChapter(
        row.getObject("id", UUID.class),
        row.getInt("ordinal"),
        row.getString("title"),
        row.getString("access_state"));
  }

  private static ReadableChapter readableChapter(ResultSet row) throws SQLException {
    return new ReadableChapter(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getString("source_language"),
        row.getObject("revision_id", UUID.class));
  }

  private static SourceSegment sourceSegment(ResultSet row) throws SQLException {
    return new SourceSegment(
        row.getInt("ordinal"),
        row.getString("segment_type"),
        row.getString("stable_anchor"),
        row.getString("source_text"));
  }

  private static <T> Page<T> page(List<T> result, int limit, Ordinal<T> ordinal) {
    boolean hasMore = result.size() > limit;
    List<T> items = hasMore ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
    Integer lastOrdinal = items.isEmpty() ? null : ordinal.get(items.get(items.size() - 1));
    return new Page<>(items, hasMore, lastOrdinal);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  @FunctionalInterface
  private interface Ordinal<T> {
    int get(T value);
  }

  public record ReaderWork(
      UUID id,
      UUID libraryId,
      String title,
      String author,
      String language,
      String visibility,
      Instant createdAt,
      int chapterCount) {}

  public record ReaderChapter(UUID id, int ordinal, String title, String accessState) {}

  public record ReadableChapter(UUID id, UUID workId, String language, UUID revisionId) {}

  public record SourceSegment(int ordinal, String type, String anchor, String sourceText) {}

  public record Page<T>(List<T> items, boolean hasMore, Integer lastOrdinal) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
