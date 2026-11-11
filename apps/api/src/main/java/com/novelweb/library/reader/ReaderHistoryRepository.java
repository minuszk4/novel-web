package com.novelweb.library.reader;

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
import org.springframework.stereotype.Repository;

/** Reads the current actor's recent readable positions from the canonical progress table. */
@Repository
@Profile("postgres")
public class ReaderHistoryRepository {

  private final DataSource dataSource;

  public ReaderHistoryRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Page findRecent(UUID actorUserId, ReaderCursorCodec.KeysetPosition after, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    String keysetPredicate =
        after == null ? "" : " AND (p.updated_at < ? OR (p.updated_at = ? AND p.work_id < ?))";
    String sql =
        "SELECT w.id AS work_id, w.library_id, w.title AS work_title, w.author, "
            + "w.source_language, w.visibility, w.created_at AS work_created_at, "
            + "(SELECT count(*) FROM chapters all_chapters "
            + " WHERE all_chapters.work_id = w.id AND all_chapters.deleted_at IS NULL) "
            + " AS chapter_count, c.id AS chapter_id, c.ordinal AS chapter_ordinal, "
            + "c.title AS chapter_title, p.segment_anchor, p.scroll_ratio, p.version, p.updated_at "
            + "FROM reader_progress p "
            + "JOIN users u ON u.id = p.user_id AND u.status = 'active' "
            + "JOIN works w ON w.id = p.work_id "
            + "JOIN chapters c ON c.id = p.chapter_id AND c.work_id = p.work_id "
            + "WHERE p.user_id = ? AND p.version > 0 AND p.updated_at >= "
            + "clock_timestamp() - INTERVAL '365 days' "
            + "AND w.deleted_at IS NULL AND c.status = 'ready' "
            + "AND c.source_revision_id IS NOT NULL AND c.deleted_at IS NULL "
            + "AND can_access_library(w.library_id, ?, FALSE)"
            + keysetPredicate
            + " ORDER BY p.updated_at DESC, p.work_id DESC LIMIT ?";
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(sql)) {
      int index = 1;
      select.setObject(index++, actorUserId);
      select.setObject(index++, actorUserId);
      if (after != null) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(after.timestamp(), ZoneOffset.UTC);
        select.setObject(index++, timestamp);
        select.setObject(index++, timestamp);
        select.setObject(index++, after.id());
      }
      select.setInt(index, limit + 1);
      try (ResultSet rows = select.executeQuery()) {
        List<HistoryRecord> result = new ArrayList<>(limit + 1);
        while (rows.next()) result.add(historyRecord(rows));
        boolean hasMore = result.size() > limit;
        List<HistoryRecord> items =
            hasMore ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
        return new Page(items, hasMore);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load private Reader history", exception);
    }
  }

  /**
   * Deleting history also clears this actor's resume position, regardless of current work access.
   */
  public void deleteWork(UUID actorUserId, UUID workId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM reader_progress WHERE user_id = ? AND work_id = ?")) {
      delete.setObject(1, actorUserId);
      delete.setObject(2, workId);
      delete.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to delete Reader history item", exception);
    }
  }

  public void deleteAll(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement("DELETE FROM reader_progress WHERE user_id = ?")) {
      delete.setObject(1, actorUserId);
      delete.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to clear Reader history", exception);
    }
  }

  private static HistoryRecord historyRecord(ResultSet row) throws SQLException {
    Instant updatedAt = instant(row, "updated_at");
    UUID workId = row.getObject("work_id", UUID.class);
    UUID chapterId = row.getObject("chapter_id", UUID.class);
    return new HistoryRecord(
        new HistoryWork(
            workId,
            row.getObject("library_id", UUID.class),
            row.getString("work_title"),
            row.getString("author"),
            row.getString("source_language"),
            row.getString("visibility"),
            row.getInt("chapter_count"),
            instant(row, "work_created_at"),
            updatedAt),
        new HistoryChapter(
            chapterId, row.getInt("chapter_ordinal"), row.getString("chapter_title")),
        new ReaderProgressRepository.ProgressRecord(
            workId,
            chapterId,
            row.getString("segment_anchor"),
            row.getDouble("scroll_ratio"),
            row.getLong("version"),
            updatedAt));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public record HistoryWork(
      UUID id,
      UUID libraryId,
      String title,
      String author,
      String language,
      String visibility,
      int chapterCount,
      Instant createdAt,
      Instant lastReadAt) {}

  public record HistoryChapter(UUID id, int ordinal, String title) {}

  public record HistoryRecord(
      HistoryWork work, HistoryChapter chapter, ReaderProgressRepository.ProgressRecord progress) {}

  public record Page(List<HistoryRecord> items, boolean hasMore) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
