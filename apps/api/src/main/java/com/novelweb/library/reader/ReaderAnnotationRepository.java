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

/** Stores private, source-revision-bound Reader annotations in PostgreSQL. */
@Repository
@Profile("postgres")
public class ReaderAnnotationRepository {

  private static final String ANNOTATION_COLUMNS =
      "a.id, a.work_id, a.chapter_id, a.chapter_revision_id, a.start_segment_anchor, "
          + "a.start_codepoint_offset, a.end_segment_anchor, a.end_codepoint_offset, "
          + "a.quote_snapshot, a.note_text, a.color_key, a.version, a.created_at, a.updated_at, "
          + "c.source_revision_id AS current_revision_id, c.ordinal AS chapter_ordinal, "
          + "c.title AS chapter_title, CASE WHEN a.chapter_revision_id IS NOT NULL "
          + "AND a.chapter_revision_id = c.source_revision_id THEN 'CURRENT' ELSE 'STALE' END "
          + "AS anchor_state";

  private final DataSource dataSource;

  public ReaderAnnotationRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Page findForWork(
      UUID actorUserId,
      UUID workId,
      UUID chapterId,
      ReaderCursorCodec.KeysetPosition after,
      int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    String chapterPredicate = chapterId == null ? "" : " AND a.chapter_id = ?";
    String keysetPredicate =
        after == null ? "" : " AND (a.updated_at < ? OR (a.updated_at = ? AND a.id < ?))";
    try (Connection connection = dataSource.getConnection()) {
      requireAccessibleWork(connection, actorUserId, workId, false);
      try (PreparedStatement select =
          connection.prepareStatement(
              "SELECT "
                  + ANNOTATION_COLUMNS
                  + " FROM reader_annotations a JOIN chapters c ON c.id = a.chapter_id "
                  + "AND c.work_id = a.work_id JOIN works w ON w.id = a.work_id "
                  + "JOIN users u ON u.id = ? AND u.status = 'active' "
                  + "WHERE a.user_id = ? AND a.work_id = ? AND c.status = 'ready' "
                  + "AND c.deleted_at IS NULL AND c.source_revision_id IS NOT NULL "
                  + "AND w.deleted_at IS NULL AND can_access_library(w.library_id, ?, FALSE)"
                  + chapterPredicate
                  + keysetPredicate
                  + " ORDER BY a.updated_at DESC, a.id DESC LIMIT ?")) {
        int index = 1;
        select.setObject(index++, actorUserId);
        select.setObject(index++, actorUserId);
        select.setObject(index++, workId);
        select.setObject(index++, actorUserId);
        if (chapterId != null) select.setObject(index++, chapterId);
        if (after != null) {
          OffsetDateTime timestamp = OffsetDateTime.ofInstant(after.timestamp(), ZoneOffset.UTC);
          select.setObject(index++, timestamp);
          select.setObject(index++, timestamp);
          select.setObject(index++, after.id());
        }
        select.setInt(index, limit + 1);
        try (ResultSet rows = select.executeQuery()) {
          List<AnnotationRecord> result = new ArrayList<>(limit + 1);
          while (rows.next()) result.add(annotation(rows));
          boolean hasMore = result.size() > limit;
          List<AnnotationRecord> items =
              hasMore ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
          return new Page(items, hasMore);
        }
      }
    } catch (AnnotationRepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load private Reader annotations", exception);
    }
  }

  public CreateResult create(
      UUID actorUserId,
      UUID workId,
      UUID chapterId,
      UUID revisionId,
      String startAnchor,
      int startOffset,
      String endAnchor,
      int endOffset,
      String quoteSnapshot,
      String note,
      String color) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    Objects.requireNonNull(revisionId, "chapter revision id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireCurrentReadableChapter(connection, actorUserId, workId, chapterId, revisionId);
        validateSelectedRange(
            connection,
            chapterId,
            revisionId,
            startAnchor,
            startOffset,
            endAnchor,
            endOffset,
            quoteSnapshot);
        AnnotationRecord created =
            insertIfAbsent(
                connection,
                actorUserId,
                workId,
                chapterId,
                revisionId,
                startAnchor,
                startOffset,
                endAnchor,
                endOffset,
                quoteSnapshot,
                note,
                color);
        boolean wasCreated = created != null;
        if (!wasCreated) {
          created =
              findAtRange(
                  connection,
                  actorUserId,
                  chapterId,
                  revisionId,
                  startAnchor,
                  startOffset,
                  endAnchor,
                  endOffset);
          if (created == null) throw new SQLException("Conflicting Reader annotation disappeared");
        }
        connection.commit();
        return new CreateResult(created, wasCreated);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (AnnotationRepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to create private Reader annotation", exception);
    }
  }

  public AnnotationRecord update(
      UUID actorUserId, UUID annotationId, long expectedVersion, String note, String color) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(annotationId, "annotation id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        UUID workId = findWorkId(connection, actorUserId, annotationId);
        requireAccessibleWork(connection, actorUserId, workId, true);
        AnnotationRecord latest = findForUpdate(connection, actorUserId, annotationId);
        if (latest.version() != expectedVersion) throw new VersionConflictException(latest);
        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE reader_annotations SET note_text = ?, color_key = ?, "
                    + "version = version + 1, updated_at = clock_timestamp() "
                    + "WHERE user_id = ? AND id = ? AND version = ?")) {
          update.setString(1, note);
          update.setString(2, color);
          update.setObject(3, actorUserId);
          update.setObject(4, annotationId);
          update.setLong(5, expectedVersion);
          if (update.executeUpdate() != 1) {
            throw new VersionConflictException(
                findForUpdate(connection, actorUserId, annotationId));
          }
        }
        AnnotationRecord updated = findForUpdate(connection, actorUserId, annotationId);
        connection.commit();
        return updated;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (AnnotationRepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to update private Reader annotation", exception);
    }
  }

  public void delete(UUID actorUserId, UUID annotationId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(annotationId, "annotation id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM reader_annotations WHERE user_id = ? AND id = ?")) {
      delete.setObject(1, actorUserId);
      delete.setObject(2, annotationId);
      if (delete.executeUpdate() != 1) throw new AnnotationNotFoundException();
    } catch (AnnotationRepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to delete private Reader annotation", exception);
    }
  }

  public void deleteForWork(UUID actorUserId, UUID workId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement delete =
            connection.prepareStatement(
                "DELETE FROM reader_annotations WHERE user_id = ? AND work_id = ?")) {
      delete.setObject(1, actorUserId);
      delete.setObject(2, workId);
      delete.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to clear private Reader annotations", exception);
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
        if (!row.next()) throw new AnnotationNotFoundException();
      }
    }
  }

  private static void requireCurrentReadableChapter(
      Connection connection, UUID actorUserId, UUID workId, UUID chapterId, UUID revisionId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT c.source_revision_id FROM chapters c JOIN works w ON w.id = c.work_id "
                + "JOIN users u ON u.id = ? AND u.status = 'active' "
                + "WHERE c.id = ? AND c.work_id = ? "
                + "AND c.status = 'ready' AND c.deleted_at IS NULL AND w.deleted_at IS NULL "
                + "AND can_access_library(w.library_id, ?, FALSE) FOR UPDATE OF c, w")) {
      select.setObject(1, actorUserId);
      select.setObject(2, chapterId);
      select.setObject(3, workId);
      select.setObject(4, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new AnnotationNotFoundException();
        UUID currentRevisionId = row.getObject("source_revision_id", UUID.class);
        if (!revisionId.equals(currentRevisionId)) throw new StaleAnchorException();
      }
    }
  }

  private static void validateSelectedRange(
      Connection connection,
      UUID chapterId,
      UUID revisionId,
      String startAnchor,
      int startOffset,
      String endAnchor,
      int endOffset,
      String quoteSnapshot)
      throws SQLException {
    Segment start = findSegment(connection, chapterId, revisionId, startAnchor);
    Segment end = findSegment(connection, chapterId, revisionId, endAnchor);
    if (start == null || end == null || start.ordinal() > end.ordinal()) {
      throw new InvalidAnnotationException();
    }
    List<Segment> range =
        findSegmentsInRange(connection, chapterId, revisionId, start.ordinal(), end.ordinal());
    if (range.size() > 10 || range.size() != end.ordinal() - start.ordinal() + 1) {
      throw new InvalidAnnotationException();
    }
    StringBuilder reconstructed = new StringBuilder();
    for (Segment segment : range) {
      int[] points = segment.text().codePoints().toArray();
      int from = segment.ordinal() == start.ordinal() ? startOffset : 0;
      int to = segment.ordinal() == end.ordinal() ? endOffset : points.length;
      if (from < 0 || from > points.length || to < 0 || to > points.length || from > to) {
        throw new InvalidAnnotationException();
      }
      reconstructed.append(new String(points, from, to - from));
    }
    if (startOffset == endOffset && start.ordinal() == end.ordinal()) {
      throw new InvalidAnnotationException();
    }
    if (!quoteSnapshot.equals(reconstructed.toString())) throw new InvalidAnnotationException();
  }

  private static Segment findSegment(
      Connection connection, UUID chapterId, UUID revisionId, String anchor) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT s.ordinal, s.segment_type, s.source_text FROM segments s "
                + "JOIN chapter_revisions r ON r.id = s.chapter_revision_id "
                + "WHERE r.chapter_id = ? AND r.id = ? AND s.stable_anchor = ?")) {
      select.setObject(1, chapterId);
      select.setObject(2, revisionId);
      select.setString(3, anchor);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? segment(row) : null;
      }
    }
  }

  private static List<Segment> findSegmentsInRange(
      Connection connection, UUID chapterId, UUID revisionId, int startOrdinal, int endOrdinal)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT s.ordinal, s.segment_type, s.source_text FROM segments s "
                + "JOIN chapter_revisions r ON r.id = s.chapter_revision_id "
                + "WHERE r.chapter_id = ? AND r.id = ? AND s.ordinal BETWEEN ? AND ? "
                + "ORDER BY s.ordinal")) {
      select.setObject(1, chapterId);
      select.setObject(2, revisionId);
      select.setInt(3, startOrdinal);
      select.setInt(4, endOrdinal);
      try (ResultSet rows = select.executeQuery()) {
        List<Segment> result = new ArrayList<>();
        while (rows.next()) {
          Segment segment = segment(rows);
          if (!isAnnotatableSegment(segment.type())) throw new InvalidAnnotationException();
          result.add(segment);
        }
        return result;
      }
    }
  }

  private static boolean isAnnotatableSegment(String type) {
    return "paragraph".equals(type) || "heading".equals(type) || "quote".equals(type);
  }

  private static Segment segment(ResultSet row) throws SQLException {
    return new Segment(
        row.getInt("ordinal"), row.getString("segment_type"), row.getString("source_text"));
  }

  private static AnnotationRecord insertIfAbsent(
      Connection connection,
      UUID actorUserId,
      UUID workId,
      UUID chapterId,
      UUID revisionId,
      String startAnchor,
      int startOffset,
      String endAnchor,
      int endOffset,
      String quoteSnapshot,
      String note,
      String color)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO reader_annotations (user_id, work_id, chapter_id, chapter_revision_id, "
                + "start_segment_anchor, start_codepoint_offset, end_segment_anchor, "
                + "end_codepoint_offset, quote_snapshot, note_text, color_key) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (user_id, chapter_id, chapter_revision_id, start_segment_anchor, "
                + "start_codepoint_offset, end_segment_anchor, end_codepoint_offset) "
                + "WHERE chapter_revision_id IS NOT NULL DO NOTHING RETURNING id")) {
      insert.setObject(1, actorUserId);
      insert.setObject(2, workId);
      insert.setObject(3, chapterId);
      insert.setObject(4, revisionId);
      insert.setString(5, startAnchor);
      insert.setInt(6, startOffset);
      insert.setString(7, endAnchor);
      insert.setInt(8, endOffset);
      insert.setString(9, quoteSnapshot);
      insert.setString(10, note);
      insert.setString(11, color);
      try (ResultSet row = insert.executeQuery()) {
        return row.next()
            ? findForUpdate(connection, actorUserId, row.getObject("id", UUID.class))
            : null;
      }
    }
  }

  private static AnnotationRecord findAtRange(
      Connection connection,
      UUID actorUserId,
      UUID chapterId,
      UUID revisionId,
      String startAnchor,
      int startOffset,
      String endAnchor,
      int endOffset)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT "
                + ANNOTATION_COLUMNS
                + " FROM reader_annotations a JOIN chapters c ON c.id = a.chapter_id "
                + "AND c.work_id = a.work_id WHERE a.user_id = ? AND a.chapter_id = ? "
                + "AND a.chapter_revision_id = ? AND a.start_segment_anchor = ? "
                + "AND a.start_codepoint_offset = ? AND a.end_segment_anchor = ? "
                + "AND a.end_codepoint_offset = ? FOR UPDATE OF a")) {
      select.setObject(1, actorUserId);
      select.setObject(2, chapterId);
      select.setObject(3, revisionId);
      select.setString(4, startAnchor);
      select.setInt(5, startOffset);
      select.setString(6, endAnchor);
      select.setInt(7, endOffset);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? annotation(row) : null;
      }
    }
  }

  private static AnnotationRecord findForUpdate(
      Connection connection, UUID actorUserId, UUID annotationId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT "
                + ANNOTATION_COLUMNS
                + " FROM reader_annotations a JOIN chapters c ON c.id = a.chapter_id "
                + "AND c.work_id = a.work_id JOIN works w ON w.id = a.work_id "
                + "JOIN users u ON u.id = ? AND u.status = 'active' "
                + "WHERE a.user_id = ? AND a.id = ? AND c.status = 'ready' "
                + "AND c.deleted_at IS NULL AND c.source_revision_id IS NOT NULL "
                + "AND w.deleted_at IS NULL AND can_access_library(w.library_id, ?, FALSE) "
                + "FOR UPDATE OF a")) {
      select.setObject(1, actorUserId);
      select.setObject(2, actorUserId);
      select.setObject(3, annotationId);
      select.setObject(4, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new AnnotationNotFoundException();
        return annotation(row);
      }
    }
  }

  private static UUID findWorkId(Connection connection, UUID actorUserId, UUID annotationId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT work_id FROM reader_annotations WHERE user_id = ? AND id = ?")) {
      select.setObject(1, actorUserId);
      select.setObject(2, annotationId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new AnnotationNotFoundException();
        return row.getObject("work_id", UUID.class);
      }
    }
  }

  private static AnnotationRecord annotation(ResultSet row) throws SQLException {
    return new AnnotationRecord(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getObject("chapter_id", UUID.class),
        row.getObject("chapter_revision_id", UUID.class),
        row.getString("start_segment_anchor"),
        row.getInt("start_codepoint_offset"),
        row.getString("end_segment_anchor"),
        row.getInt("end_codepoint_offset"),
        row.getString("quote_snapshot"),
        row.getString("note_text"),
        row.getString("color_key"),
        row.getLong("version"),
        instant(row, "created_at"),
        instant(row, "updated_at"),
        row.getInt("chapter_ordinal"),
        row.getString("chapter_title"),
        row.getString("anchor_state"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private record Segment(int ordinal, String type, String text) {}

  public record AnnotationRecord(
      UUID id,
      UUID workId,
      UUID chapterId,
      UUID chapterRevisionId,
      String startAnchor,
      int startOffset,
      String endAnchor,
      int endOffset,
      String quoteSnapshot,
      String note,
      String color,
      long version,
      Instant createdAt,
      Instant updatedAt,
      int chapterOrdinal,
      String chapterTitle,
      String anchorState) {}

  public record CreateResult(AnnotationRecord annotation, boolean created) {}

  public record Page(List<AnnotationRecord> items, boolean hasMore) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public abstract static class AnnotationRepositoryException extends RuntimeException {}

  public static final class AnnotationNotFoundException extends AnnotationRepositoryException {}

  public static final class InvalidAnnotationException extends AnnotationRepositoryException {}

  public static final class StaleAnchorException extends AnnotationRepositoryException {}

  public static final class VersionConflictException extends AnnotationRepositoryException {
    private final AnnotationRecord latest;

    public VersionConflictException(AnnotationRecord latest) {
      this.latest = Objects.requireNonNull(latest, "latest annotation is required");
    }

    public AnnotationRecord latest() {
      return latest;
    }
  }
}
