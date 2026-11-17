package com.novelweb.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Metadata-only reads of authored works whose publication visibility is public. */
@Repository
@Profile("postgres")
public class PublicCatalogRepository {

  private final DataSource dataSource;

  public PublicCatalogRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public Slice findWorks(
      String query, String language, int limit, LibraryCursorCodec.Position position) {
    StringBuilder sql =
        new StringBuilder(
            """
                SELECT w.id, w.title, w.author, w.source_language,
                       count(c.id) AS ready_chapter_count, w.created_at
                FROM works w
                JOIN libraries l ON l.id = w.library_id
                  AND l.owner_user_id = w.owner_user_id AND l.deleted_at IS NULL
                JOIN users owner ON owner.id = w.owner_user_id AND owner.status = 'active'
                JOIN chapters c ON c.work_id = w.id
                  AND c.status = 'ready' AND c.deleted_at IS NULL
                  AND c.source_revision_id IS NOT NULL
                WHERE w.source_kind = 'authored'
                  AND w.visibility = 'public'
                  AND w.status = 'active'
                  AND w.deleted_at IS NULL
                  AND publication_scope_access_is_current(w.id, 'PUBLIC_METADATA')
                """);
    if (!query.isEmpty()) {
      sql.append(" AND (lower(w.title) LIKE ? ESCAPE '!' OR lower(w.author) LIKE ? ESCAPE '!')");
    }
    if (!language.isEmpty()) sql.append(" AND lower(w.source_language) = ?");
    if (position != null) {
      sql.append(" AND (w.created_at < ? OR (w.created_at = ? AND w.id < ?))");
    }
    sql.append(" GROUP BY w.id ORDER BY w.created_at DESC, w.id DESC LIMIT ?");

    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(sql.toString())) {
      int parameter = 1;
      if (!query.isEmpty()) {
        String pattern = "%" + escapeLike(query) + "%";
        select.setString(parameter++, pattern);
        select.setString(parameter++, pattern);
      }
      if (!language.isEmpty()) select.setString(parameter++, language);
      if (position != null) {
        Timestamp createdAt = Timestamp.from(position.createdAt());
        select.setTimestamp(parameter++, createdAt);
        select.setTimestamp(parameter++, createdAt);
        select.setObject(parameter++, position.itemId());
      }
      select.setInt(parameter, limit + 1);
      try (ResultSet rows = select.executeQuery()) {
        List<WorkSummary> works = new ArrayList<>(limit + 1);
        while (rows.next()) {
          works.add(
              new WorkSummary(
                  rows.getObject("id", UUID.class),
                  rows.getString("title"),
                  rows.getString("author"),
                  rows.getString("source_language"),
                  rows.getInt("ready_chapter_count"),
                  rows.getObject("created_at", OffsetDateTime.class).toInstant()));
        }
        boolean hasMore = works.size() > limit;
        if (hasMore) works.remove(works.size() - 1);
        return new Slice(works, hasMore);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load public catalog works", exception);
    }
  }

  public Optional<PublicWork> findWork(UUID workId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT w.id, w.title, w.author, w.source_language,
                           count(c.id) AS ready_chapter_count
                    FROM works w
                    JOIN libraries l ON l.id = w.library_id
                      AND l.owner_user_id = w.owner_user_id AND l.deleted_at IS NULL
                    JOIN users owner ON owner.id = w.owner_user_id AND owner.status = 'active'
                    JOIN chapters c ON c.work_id = w.id
                      AND c.status = 'ready' AND c.deleted_at IS NULL
                      AND c.source_revision_id IS NOT NULL
                    WHERE w.id = ? AND w.source_kind = 'authored'
                      AND w.visibility = 'public' AND w.status = 'active'
                      AND w.deleted_at IS NULL
                      AND publication_scope_access_is_current(w.id, 'PUBLIC_METADATA')
                    GROUP BY w.id
                    HAVING count(c.id) > 0
                    """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new PublicWork(
                    row.getObject("id", UUID.class),
                    row.getString("title"),
                    row.getString("author"),
                    row.getString("source_language"),
                    row.getInt("ready_chapter_count")))
            : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load public work metadata", exception);
    }
  }

  public boolean isOwner(UUID workId, UUID actorUserId) {
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(actorUserId, "actor user id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT EXISTS (
                        SELECT 1 FROM works work
                        JOIN users owner ON owner.id = work.owner_user_id AND owner.status = 'active'
                        JOIN libraries library ON library.id = work.library_id
                          AND library.owner_user_id = work.owner_user_id
                          AND library.workspace_id IS NULL AND library.deleted_at IS NULL
                        WHERE work.id = ? AND work.owner_user_id = ?
                          AND work.source_kind = 'authored' AND work.visibility = 'public'
                          AND work.status = 'active' AND work.deleted_at IS NULL
                          AND publication_scope_access_is_current(work.id, 'PUBLIC_METADATA')
                    )
                    """)) {
      select.setObject(1, workId);
      select.setObject(2, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() && row.getBoolean(1);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to check public work ownership", exception);
    }
  }

  public ChapterSlice findReadyChapters(UUID workId, int afterOrdinal, int limit) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT chapter.ordinal, chapter.title
                    FROM chapters chapter
                    JOIN works work ON work.id = chapter.work_id
                    JOIN libraries library ON library.id = work.library_id
                      AND library.owner_user_id = work.owner_user_id
                      AND library.deleted_at IS NULL
                    JOIN users owner ON owner.id = work.owner_user_id AND owner.status = 'active'
                    WHERE work.id = ? AND work.source_kind = 'authored'
                      AND work.visibility = 'public' AND work.status = 'active'
                      AND work.deleted_at IS NULL
                      AND publication_scope_access_is_current(work.id, 'PUBLIC_METADATA')
                      AND chapter.status = 'ready' AND chapter.deleted_at IS NULL
                      AND chapter.source_revision_id IS NOT NULL
                      AND chapter.ordinal > ?
                    ORDER BY chapter.ordinal ASC
                    LIMIT ?
                    """)) {
      select.setObject(1, workId);
      select.setInt(2, afterOrdinal);
      select.setInt(3, limit + 1);
      try (ResultSet rows = select.executeQuery()) {
        List<PublicChapter> chapters = new ArrayList<>(limit + 1);
        while (rows.next()) {
          chapters.add(new PublicChapter(rows.getInt("ordinal"), rows.getString("title")));
        }
        boolean hasMore = chapters.size() > limit;
        if (hasMore) chapters.remove(chapters.size() - 1);
        return new ChapterSlice(chapters, hasMore);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load public chapter metadata", exception);
    }
  }

  private static String escapeLike(String query) {
    return query.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  public record WorkSummary(
      UUID id,
      String title,
      String author,
      String sourceLanguage,
      int readyChapterCount,
      java.time.Instant createdAt) {}

  public record PublicWork(
      UUID id, String title, String author, String sourceLanguage, int readyChapterCount) {}

  public record PublicChapter(int ordinal, String title) {}

  public record ChapterSlice(List<PublicChapter> items, boolean hasMore) {
    public ChapterSlice {
      items = List.copyOf(items);
    }
  }

  public record Slice(List<WorkSummary> items, boolean hasMore) {
    public Slice {
      items = List.copyOf(items);
    }
  }
}
