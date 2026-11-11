package com.novelweb.library.reader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Persists per-user, per-work Reader progress with transactional optimistic concurrency. */
@Repository
@Profile("postgres")
public class ReaderProgressRepository {

  private final DataSource dataSource;

  public ReaderProgressRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public ProgressRecord find(UUID actorUserId, UUID workId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      requireAccessibleWork(connection, actorUserId, workId, false);
      Optional<ProgressRecord> progress = find(connection, actorUserId, workId, false);
      return progress.orElseGet(() -> ProgressRecord.empty(workId));
    } catch (ProgressNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load Reader progress", exception);
    }
  }

  public ProgressRecord save(
      UUID actorUserId,
      UUID workId,
      long expectedVersion,
      UUID chapterId,
      String segmentAnchor,
      double scrollRatio) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        // Serialize writes for a work and keep its access check stable through the transaction.
        requireAccessibleWork(connection, actorUserId, workId, true);
        try (PreparedStatement initialize =
            connection.prepareStatement(
                "INSERT INTO reader_progress (user_id, work_id) VALUES (?, ?) "
                    + "ON CONFLICT (user_id, work_id) DO NOTHING")) {
          initialize.setObject(1, actorUserId);
          initialize.setObject(2, workId);
          initialize.executeUpdate();
        }

        ProgressRecord current = find(connection, actorUserId, workId, true).orElseThrow();
        if (current.version() != expectedVersion) {
          if (samePosition(current, chapterId, segmentAnchor, scrollRatio)) {
            connection.commit();
            return current;
          }
          throw new ProgressVersionConflictException(current);
        }
        validatePosition(connection, actorUserId, workId, chapterId, segmentAnchor);
        if (samePosition(current, chapterId, segmentAnchor, scrollRatio)) {
          connection.commit();
          return current;
        }

        try (PreparedStatement update =
            connection.prepareStatement(
                "UPDATE reader_progress SET chapter_id = ?, segment_anchor = ?, scroll_ratio = ?, "
                    + "version = version + 1, updated_at = clock_timestamp() "
                    + "WHERE user_id = ? AND work_id = ?")) {
          update.setObject(1, chapterId);
          update.setString(2, segmentAnchor);
          update.setDouble(3, scrollRatio);
          update.setObject(4, actorUserId);
          update.setObject(5, workId);
          if (update.executeUpdate() != 1)
            throw new SQLException("Reader progress row disappeared");
        }
        ProgressRecord saved = find(connection, actorUserId, workId, false).orElseThrow();
        connection.commit();
        return saved;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (ProgressRepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to save Reader progress", exception);
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
        if (!row.next()) throw new ProgressNotFoundException();
      }
    }
  }

  private static Optional<ProgressRecord> find(
      Connection connection, UUID actorUserId, UUID workId, boolean lock) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT work_id, chapter_id, segment_anchor, scroll_ratio, version, updated_at "
                + "FROM reader_progress WHERE user_id = ? AND work_id = ?"
                + (lock ? " FOR UPDATE" : ""))) {
      select.setObject(1, actorUserId);
      select.setObject(2, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        OffsetDateTime updatedAt = row.getObject("updated_at", OffsetDateTime.class);
        return Optional.of(
            new ProgressRecord(
                row.getObject("work_id", UUID.class),
                row.getObject("chapter_id", UUID.class),
                row.getString("segment_anchor"),
                row.getDouble("scroll_ratio"),
                row.getLong("version"),
                updatedAt == null ? null : updatedAt.toInstant()));
      }
    }
  }

  private static void validatePosition(
      Connection connection, UUID actorUserId, UUID workId, UUID chapterId, String segmentAnchor)
      throws SQLException {
    if (chapterId == null) return;
    try (PreparedStatement chapter =
        connection.prepareStatement(
            "SELECT 1 FROM chapters c JOIN works w ON w.id = c.work_id "
                + "WHERE c.id = ? AND c.work_id = ? AND c.status = 'ready' "
                + "AND c.deleted_at IS NULL AND w.deleted_at IS NULL "
                + "AND can_access_library(w.library_id, ?, FALSE)")) {
      chapter.setObject(1, chapterId);
      chapter.setObject(2, workId);
      chapter.setObject(3, actorUserId);
      try (ResultSet row = chapter.executeQuery()) {
        if (!row.next()) throw new InvalidProgressException();
      }
    }
    if (segmentAnchor == null) return;
    try (PreparedStatement anchor =
        connection.prepareStatement(
            "SELECT 1 FROM segments s JOIN chapter_revisions r ON r.id = s.chapter_revision_id "
                + "WHERE r.chapter_id = ? AND s.stable_anchor = ? "
                + "AND s.segment_type IN ('paragraph', 'heading', 'quote') LIMIT 1")) {
      anchor.setObject(1, chapterId);
      anchor.setString(2, segmentAnchor);
      try (ResultSet row = anchor.executeQuery()) {
        // Retained revisions preserve a valid old anchor so clients can fall back to scrollRatio
        // if that exact anchor no longer exists in the current revision.
        if (!row.next()) throw new InvalidProgressException();
      }
    }
  }

  private static boolean samePosition(
      ProgressRecord current, UUID chapterId, String segmentAnchor, double scrollRatio) {
    return Objects.equals(current.chapterId(), chapterId)
        && Objects.equals(current.segmentAnchor(), segmentAnchor)
        && Double.compare(current.scrollRatio(), scrollRatio) == 0;
  }

  public record ProgressRecord(
      UUID workId,
      UUID chapterId,
      String segmentAnchor,
      double scrollRatio,
      long version,
      Instant updatedAt) {
    public static ProgressRecord empty(UUID workId) {
      return new ProgressRecord(workId, null, null, 0, 0, null);
    }
  }

  public abstract static class ProgressRepositoryException extends RuntimeException {}

  public static final class ProgressNotFoundException extends ProgressRepositoryException {}

  public static final class InvalidProgressException extends ProgressRepositoryException {}

  public static final class ProgressVersionConflictException extends ProgressRepositoryException {
    private final ProgressRecord latestProgress;

    public ProgressVersionConflictException(ProgressRecord latestProgress) {
      this.latestProgress = latestProgress;
    }

    public ProgressRecord latestProgress() {
      return latestProgress;
    }
  }
}
