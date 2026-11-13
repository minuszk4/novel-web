package com.novelweb.translation.glossary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Reads one authorized writable glossary snapshot for a non-mutating import preview. */
@Repository
@Profile("postgres")
public class GlossaryImportPreviewRepository {

  private final DataSource dataSource;

  public GlossaryImportPreviewRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public WritableSnapshot loadWritableSnapshot(UUID actorUserId, UUID glossaryId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(glossaryId, "glossary id is required");

    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        GlossaryMetadata metadata = metadata(connection, actorUserId, glossaryId);
        if (metadata == null) throw new GlossaryNotFoundException();
        List<CurrentTerm> terms =
            metadata.baseRevisionId() == null
                ? List.of()
                : terms(connection, metadata.baseRevisionId());
        connection.commit();
        return new WritableSnapshot(
            metadata.glossaryId(),
            metadata.name(),
            metadata.scope(),
            metadata.sourceLanguage(),
            metadata.targetLanguage(),
            metadata.version(),
            metadata.activeRevisionId(),
            metadata.activeRevisionNumber(),
            metadata.baseRevisionId(),
            metadata.baseRevisionNumber(),
            terms);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (GlossaryNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load glossary import preview state", exception);
    }
  }

  private static GlossaryMetadata metadata(Connection connection, UUID actorUserId, UUID glossaryId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT g.id, g.name, g.scope_type, g.source_language, g.target_language,
                       g.version, g.active_revision_id,
                       active.revision_no AS active_revision_number,
                       latest.id AS base_revision_id, latest.revision_no AS base_revision_number
                FROM glossaries g
                JOIN users actor ON actor.id = ? AND actor.status = 'active'
                LEFT JOIN glossary_revisions active
                  ON active.glossary_id = g.id AND active.id = g.active_revision_id
                LEFT JOIN LATERAL (
                  SELECT revision.id, revision.revision_no
                  FROM glossary_revisions revision
                  WHERE revision.glossary_id = g.id
                  ORDER BY revision.revision_no DESC
                  LIMIT 1
                ) latest ON TRUE
                WHERE g.id = ? AND g.deleted_at IS NULL
                  AND ((g.scope_type = 'USER_GLOBAL' AND g.owner_user_id = actor.id)
                       OR (g.library_id IS NOT NULL
                           AND can_access_library(g.library_id, actor.id, TRUE)))
                """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, glossaryId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return null;
        return new GlossaryMetadata(
            row.getObject("id", UUID.class),
            row.getString("name"),
            GlossaryMatcher.Scope.valueOf(row.getString("scope_type")),
            row.getString("source_language"),
            row.getString("target_language"),
            row.getLong("version"),
            row.getObject("active_revision_id", UUID.class),
            row.getObject("active_revision_number", Integer.class),
            row.getObject("base_revision_id", UUID.class),
            row.getObject("base_revision_number", Integer.class));
      }
    }
  }

  private static List<CurrentTerm> terms(Connection connection, UUID revisionId)
      throws SQLException {
    Map<UUID, TermAccumulator> terms = new LinkedHashMap<>();
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT t.id, t.ordinal, t.source_term, t.target_term, t.priority, t.case_policy,
                       t.match_mode, t.do_not_translate, t.context_note, a.alias_text
                FROM glossary_terms t
                LEFT JOIN glossary_aliases a ON a.revision_id = t.revision_id AND a.term_id = t.id
                WHERE t.revision_id = ?
                ORDER BY t.ordinal, a.ordinal
                """)) {
      select.setObject(1, revisionId);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          UUID id = rows.getObject("id", UUID.class);
          TermAccumulator term = terms.get(id);
          if (term == null) {
            term =
                new TermAccumulator(
                    id,
                    rows.getInt("ordinal"),
                    rows.getString("source_term"),
                    rows.getString("target_term"),
                    rows.getInt("priority"),
                    GlossaryMatcher.CasePolicy.valueOf(rows.getString("case_policy")),
                    GlossaryMatcher.MatchMode.valueOf(rows.getString("match_mode")),
                    rows.getBoolean("do_not_translate"),
                    rows.getString("context_note"));
            terms.put(id, term);
          }
          String alias = rows.getString("alias_text");
          if (alias != null) term.aliases().add(alias);
        }
      }
    }
    return terms.values().stream().map(TermAccumulator::toRecord).toList();
  }

  public record WritableSnapshot(
      UUID glossaryId,
      String name,
      GlossaryMatcher.Scope scope,
      String sourceLanguage,
      String targetLanguage,
      long version,
      UUID activeRevisionId,
      Integer activeRevisionNumber,
      UUID baseRevisionId,
      Integer baseRevisionNumber,
      List<CurrentTerm> terms) {
    public WritableSnapshot {
      terms = List.copyOf(terms);
    }
  }

  public record CurrentTerm(
      UUID id,
      int ordinal,
      String sourceTerm,
      String targetTerm,
      int priority,
      GlossaryMatcher.CasePolicy casePolicy,
      GlossaryMatcher.MatchMode matchMode,
      boolean doNotTranslate,
      String contextNote,
      List<String> aliases) {
    public CurrentTerm {
      aliases = List.copyOf(aliases);
    }
  }

  private record GlossaryMetadata(
      UUID glossaryId,
      String name,
      GlossaryMatcher.Scope scope,
      String sourceLanguage,
      String targetLanguage,
      long version,
      UUID activeRevisionId,
      Integer activeRevisionNumber,
      UUID baseRevisionId,
      Integer baseRevisionNumber) {}

  private record TermAccumulator(
      UUID id,
      int ordinal,
      String sourceTerm,
      String targetTerm,
      int priority,
      GlossaryMatcher.CasePolicy casePolicy,
      GlossaryMatcher.MatchMode matchMode,
      boolean doNotTranslate,
      String contextNote,
      List<String> aliases) {
    private TermAccumulator(
        UUID id,
        int ordinal,
        String sourceTerm,
        String targetTerm,
        int priority,
        GlossaryMatcher.CasePolicy casePolicy,
        GlossaryMatcher.MatchMode matchMode,
        boolean doNotTranslate,
        String contextNote) {
      this(
          id,
          ordinal,
          sourceTerm,
          targetTerm,
          priority,
          casePolicy,
          matchMode,
          doNotTranslate,
          contextNote,
          new ArrayList<>());
    }

    private CurrentTerm toRecord() {
      return new CurrentTerm(
          id,
          ordinal,
          sourceTerm,
          targetTerm,
          priority,
          casePolicy,
          matchMode,
          doNotTranslate,
          contextNote,
          aliases);
    }
  }

  public static class GlossaryNotFoundException extends RuntimeException {}
}
