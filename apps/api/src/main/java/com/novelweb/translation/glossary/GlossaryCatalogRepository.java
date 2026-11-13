package com.novelweb.translation.glossary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Creates glossary metadata and lists only glossary records visible to the authenticated actor. */
@Repository
@Profile("postgres")
public class GlossaryCatalogRepository {

  private final DataSource dataSource;

  public GlossaryCatalogRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public GlossaryRecord createGlossary(UUID actorUserId, NewGlossary requested) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(requested, "glossary request is required");
    validateScopeTargets(requested);
    UUID glossaryId = UUID.randomUUID();

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireActiveActor(connection, actorUserId);
        if (requested.scope() != GlossaryMatcher.Scope.USER_GLOBAL
            && !canWriteTarget(connection, actorUserId, requested)) {
          throw new GlossaryNotFoundException();
        }
        GlossaryRecord created = insertGlossary(connection, actorUserId, glossaryId, requested);
        connection.commit();
        return created;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (GlossaryNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23503".equals(exception.getSQLState()) || "23514".equals(exception.getSQLState())) {
        throw new GlossaryNotFoundException();
      }
      throw new IllegalStateException("Unable to create glossary metadata", exception);
    }
  }

  public Slice findVisibleGlossaries(
      UUID actorUserId,
      GlossaryMatcher.Scope scope,
      UUID libraryId,
      String normalizedQuery,
      GlossaryCursorCodec.Position position,
      int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(normalizedQuery, "normalized query is required");
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1–100");

    StringBuilder sql =
        new StringBuilder(
            """
                SELECT g.id, g.name, g.scope_type, g.source_language, g.target_language,
                       g.version, g.active_revision_id, g.created_at
                FROM glossaries g
                JOIN users actor ON actor.id = ? AND actor.status = 'active'
                WHERE g.deleted_at IS NULL
                  AND ((g.scope_type = 'USER_GLOBAL' AND g.owner_user_id = ?)
                       OR (g.library_id IS NOT NULL AND can_access_library(g.library_id, ?, FALSE)))
                """);
    if (scope != null) sql.append(" AND g.scope_type = ?");
    if (libraryId != null) sql.append(" AND g.library_id = ?");
    if (!normalizedQuery.isEmpty()) sql.append(" AND lower(g.name) LIKE ? ESCAPE '\\'");
    if (position != null) {
      sql.append(" AND (g.created_at < ? OR (g.created_at = ? AND g.id < ?))");
    }
    sql.append(" ORDER BY g.created_at DESC, g.id DESC LIMIT ?");

    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
        int parameter = 1;
        select.setObject(parameter++, actorUserId);
        select.setObject(parameter++, actorUserId);
        select.setObject(parameter++, actorUserId);
        if (scope != null) select.setString(parameter++, scope.name());
        if (libraryId != null) select.setObject(parameter++, libraryId);
        if (!normalizedQuery.isEmpty()) {
          select.setString(parameter++, likePattern(normalizedQuery));
        }
        if (position != null) {
          OffsetDateTime createdAt =
              OffsetDateTime.ofInstant(position.createdAt(), java.time.ZoneOffset.UTC);
          select.setObject(parameter++, createdAt);
          select.setObject(parameter++, createdAt);
          select.setObject(parameter++, position.glossaryId());
        }
        select.setInt(parameter, limit + 1);

        List<GlossaryRecord> records = new ArrayList<>(limit + 1);
        try (ResultSet rows = select.executeQuery()) {
          while (rows.next()) records.add(glossary(rows));
        }
        connection.commit();
        boolean hasMore = records.size() > limit;
        if (hasMore) records.remove(records.size() - 1);
        GlossaryCursorCodec.Position nextPosition =
            hasMore && !records.isEmpty() ? position(records.get(records.size() - 1)) : null;
        return new Slice(records, hasMore, nextPosition);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to list visible glossaries", exception);
    }
  }

  private static GlossaryRecord insertGlossary(
      Connection connection, UUID actorUserId, UUID glossaryId, NewGlossary glossary)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO glossaries
                    (id, owner_user_id, name, scope_type, library_id, work_id, volume_id, chapter_id,
                     source_language, target_language)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, name, scope_type, source_language, target_language,
                          version, active_revision_id, created_at
                """)) {
      int parameter = 1;
      insert.setObject(parameter++, glossaryId);
      insert.setObject(
          parameter++, glossary.scope() == GlossaryMatcher.Scope.USER_GLOBAL ? actorUserId : null);
      insert.setString(parameter++, glossary.name());
      insert.setString(parameter++, glossary.scope().name());
      insert.setObject(parameter++, glossary.libraryId());
      insert.setObject(parameter++, glossary.workId());
      insert.setObject(parameter++, glossary.volumeId());
      insert.setObject(parameter++, glossary.chapterId());
      insert.setString(parameter++, glossary.sourceLanguage());
      insert.setString(parameter, glossary.targetLanguage());
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Glossary insert returned no record");
        return glossary(row);
      }
    }
  }

  private static void requireActiveActor(Connection connection, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM users WHERE id = ? AND status = 'active' FOR SHARE")) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new GlossaryNotFoundException();
      }
    }
  }

  private static boolean canWriteTarget(
      Connection connection, UUID actorUserId, NewGlossary requested) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT EXISTS (
                    SELECT 1
                    FROM libraries l
                    LEFT JOIN works w ON w.id = ? AND w.library_id = l.id AND w.deleted_at IS NULL
                    LEFT JOIN volumes v ON v.id = ? AND v.work_id = w.id
                    LEFT JOIN chapters c ON c.id = ? AND c.work_id = w.id AND c.deleted_at IS NULL
                    WHERE l.id = ? AND can_access_library(l.id, ?, TRUE)
                      AND CASE ?
                        WHEN 'LIBRARY' THEN TRUE
                        WHEN 'WORK' THEN w.id IS NOT NULL
                        WHEN 'VOLUME' THEN w.id IS NOT NULL AND v.id IS NOT NULL
                        WHEN 'CHAPTER' THEN w.id IS NOT NULL AND c.id IS NOT NULL
                        ELSE FALSE
                      END
                )
                """)) {
      select.setObject(1, requested.workId());
      select.setObject(2, requested.volumeId());
      select.setObject(3, requested.chapterId());
      select.setObject(4, requested.libraryId());
      select.setObject(5, actorUserId);
      select.setString(6, requested.scope().name());
      try (ResultSet row = select.executeQuery()) {
        return row.next() && row.getBoolean(1);
      }
    }
  }

  private static void validateScopeTargets(NewGlossary glossary) {
    Objects.requireNonNull(glossary.scope(), "scope is required");
    boolean valid =
        switch (glossary.scope()) {
          case USER_GLOBAL ->
              glossary.libraryId() == null
                  && glossary.workId() == null
                  && glossary.volumeId() == null
                  && glossary.chapterId() == null;
          case LIBRARY ->
              glossary.libraryId() != null
                  && glossary.workId() == null
                  && glossary.volumeId() == null
                  && glossary.chapterId() == null;
          case WORK ->
              glossary.libraryId() != null
                  && glossary.workId() != null
                  && glossary.volumeId() == null
                  && glossary.chapterId() == null;
          case VOLUME ->
              glossary.libraryId() != null
                  && glossary.workId() != null
                  && glossary.volumeId() != null
                  && glossary.chapterId() == null;
          case CHAPTER ->
              glossary.libraryId() != null
                  && glossary.workId() != null
                  && glossary.volumeId() == null
                  && glossary.chapterId() != null;
        };
    if (!valid) throw new IllegalArgumentException("scope target IDs do not match glossary scope");
  }

  private static String likePattern(String query) {
    String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static GlossaryRecord glossary(ResultSet row) throws SQLException {
    return new GlossaryRecord(
        row.getObject("id", UUID.class),
        row.getString("name"),
        GlossaryMatcher.Scope.valueOf(row.getString("scope_type")),
        row.getString("source_language"),
        row.getString("target_language"),
        row.getLong("version"),
        row.getObject("active_revision_id", UUID.class),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static GlossaryCursorCodec.Position position(GlossaryRecord record) {
    return new GlossaryCursorCodec.Position(record.createdAt(), record.id());
  }

  public record NewGlossary(
      String name,
      GlossaryMatcher.Scope scope,
      String sourceLanguage,
      String targetLanguage,
      UUID libraryId,
      UUID workId,
      UUID volumeId,
      UUID chapterId) {}

  public record GlossaryRecord(
      UUID id,
      String name,
      GlossaryMatcher.Scope scope,
      String sourceLanguage,
      String targetLanguage,
      long version,
      UUID activeRevisionId,
      Instant createdAt) {}

  public record Slice(
      List<GlossaryRecord> items, boolean hasMore, GlossaryCursorCodec.Position nextPosition) {
    public Slice {
      items = List.copyOf(items);
    }
  }

  public static class GlossaryNotFoundException extends RuntimeException {}
}
