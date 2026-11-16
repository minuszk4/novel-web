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

  public record Slice(List<WorkSummary> items, boolean hasMore) {
    public Slice {
      items = List.copyOf(items);
    }
  }
}
