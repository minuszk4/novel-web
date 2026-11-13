package com.novelweb.translation.glossary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Loads only the active immutable glossary revision visible to the authenticated actor. */
@Repository
@Profile("postgres")
public class GlossaryRepository {

  private static final String ACTIVE_REVISION_QUERY =
      """
          SELECT g.id AS glossary_id, g.name, g.scope_type, g.source_language, g.target_language,
                 g.version AS glossary_version, g.active_revision_id,
                 r.id AS revision_id, r.revision_no, r.parent_revision_id, r.created_at AS revision_created_at,
                 t.id AS term_id, t.ordinal AS term_ordinal, t.source_term, t.target_term,
                 t.priority, t.case_policy, t.match_mode, t.do_not_translate, t.context_note,
                 a.ordinal AS alias_ordinal, a.alias_text
          FROM glossaries g
          JOIN users actor ON actor.id = ? AND actor.status = 'active'
          JOIN glossary_revisions r ON r.glossary_id = g.id AND r.id = g.active_revision_id
          LEFT JOIN glossary_terms t ON t.revision_id = r.id
          LEFT JOIN glossary_aliases a ON a.revision_id = t.revision_id AND a.term_id = t.id
          WHERE g.id = ? AND g.deleted_at IS NULL
            AND ((g.scope_type = 'USER_GLOBAL' AND g.owner_user_id = ?)
                 OR (g.library_id IS NOT NULL AND can_access_library(g.library_id, ?, FALSE)))
          ORDER BY t.ordinal, a.ordinal
          """;

  private final DataSource dataSource;

  public GlossaryRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  /** Returns empty for a missing glossary, missing active revision, inactive actor or no access. */
  public Optional<GlossarySnapshot> loadActiveRevision(UUID actorUserId, UUID glossaryId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(glossaryId, "glossary id is required");

    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        Optional<GlossarySnapshot> result = readSnapshot(connection, actorUserId, glossaryId);
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the active glossary revision", exception);
    }
  }

  private static Optional<GlossarySnapshot> readSnapshot(
      Connection connection, UUID actorUserId, UUID glossaryId) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement(ACTIVE_REVISION_QUERY)) {
      select.setObject(1, actorUserId);
      select.setObject(2, glossaryId);
      select.setObject(3, actorUserId);
      select.setObject(4, actorUserId);
      try (ResultSet rows = select.executeQuery()) {
        SnapshotMetadata metadata = null;
        Map<UUID, TermAccumulator> terms = new LinkedHashMap<>();
        while (rows.next()) {
          if (metadata == null) metadata = metadata(rows);
          UUID termId = rows.getObject("term_id", UUID.class);
          if (termId == null) continue;

          TermAccumulator term = terms.get(termId);
          if (term == null) {
            term = term(rows);
            terms.put(termId, term);
          }
          String alias = rows.getString("alias_text");
          if (alias != null) term.aliases.add(alias);
        }
        if (metadata == null) return Optional.empty();

        List<TermRecord> termRecords =
            terms.values().stream().map(TermAccumulator::toRecord).toList();
        return Optional.of(metadata.toSnapshot(termRecords));
      }
    }
  }

  private static SnapshotMetadata metadata(ResultSet row) throws SQLException {
    return new SnapshotMetadata(
        row.getObject("glossary_id", UUID.class),
        row.getString("name"),
        GlossaryMatcher.Scope.valueOf(row.getString("scope_type")),
        row.getString("source_language"),
        row.getString("target_language"),
        row.getLong("glossary_version"),
        row.getObject("active_revision_id", UUID.class),
        row.getObject("revision_id", UUID.class),
        row.getInt("revision_no"),
        row.getObject("parent_revision_id", UUID.class),
        row.getObject("revision_created_at", OffsetDateTime.class).toInstant());
  }

  private static TermAccumulator term(ResultSet row) throws SQLException {
    return new TermAccumulator(
        row.getObject("term_id", UUID.class),
        row.getInt("term_ordinal"),
        row.getString("source_term"),
        row.getString("target_term"),
        row.getInt("priority"),
        GlossaryMatcher.CasePolicy.valueOf(row.getString("case_policy")),
        GlossaryMatcher.MatchMode.valueOf(row.getString("match_mode")),
        row.getBoolean("do_not_translate"),
        row.getString("context_note"));
  }

  public record GlossarySnapshot(
      UUID glossaryId,
      String name,
      GlossaryMatcher.Scope scope,
      String sourceLanguage,
      String targetLanguage,
      long version,
      UUID activeRevisionId,
      int revisionNumber,
      UUID parentRevisionId,
      Instant revisionCreatedAt,
      List<TermRecord> terms) {
    public GlossarySnapshot {
      Objects.requireNonNull(glossaryId, "glossary id is required");
      Objects.requireNonNull(name, "glossary name is required");
      Objects.requireNonNull(scope, "glossary scope is required");
      Objects.requireNonNull(sourceLanguage, "source language is required");
      Objects.requireNonNull(targetLanguage, "target language is required");
      Objects.requireNonNull(activeRevisionId, "active revision id is required");
      Objects.requireNonNull(revisionCreatedAt, "revision creation time is required");
      terms = List.copyOf(terms);
    }

    public GlossaryRevisionPreviewService.GlossaryRevision asDomainRevision() {
      List<GlossaryMatcher.Term> matcherTerms =
          terms.stream()
              .map(
                  term ->
                      new GlossaryMatcher.Term(
                          term.id(),
                          activeRevisionId,
                          sourceLanguage,
                          term.sourceTerm(),
                          term.targetTerm(),
                          term.aliases(),
                          scope,
                          term.priority(),
                          term.casePolicy(),
                          term.matchMode(),
                          term.doNotTranslate()))
              .toList();
      return new GlossaryRevisionPreviewService.GlossaryRevision(
          glossaryId, activeRevisionId, parentRevisionId, matcherTerms);
    }
  }

  public record TermRecord(
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
    public TermRecord {
      Objects.requireNonNull(id, "term id is required");
      Objects.requireNonNull(sourceTerm, "source term is required");
      Objects.requireNonNull(casePolicy, "case policy is required");
      Objects.requireNonNull(matchMode, "match mode is required");
      aliases = List.copyOf(aliases);
    }
  }

  private record SnapshotMetadata(
      UUID glossaryId,
      String name,
      GlossaryMatcher.Scope scope,
      String sourceLanguage,
      String targetLanguage,
      long version,
      UUID activeRevisionId,
      UUID revisionId,
      int revisionNumber,
      UUID parentRevisionId,
      Instant revisionCreatedAt) {

    private GlossarySnapshot toSnapshot(List<TermRecord> terms) {
      return new GlossarySnapshot(
          glossaryId,
          name,
          scope,
          sourceLanguage,
          targetLanguage,
          version,
          activeRevisionId,
          revisionNumber,
          parentRevisionId,
          revisionCreatedAt,
          terms);
    }
  }

  private static final class TermAccumulator {
    private final UUID id;
    private final int ordinal;
    private final String sourceTerm;
    private final String targetTerm;
    private final int priority;
    private final GlossaryMatcher.CasePolicy casePolicy;
    private final GlossaryMatcher.MatchMode matchMode;
    private final boolean doNotTranslate;
    private final String contextNote;
    private final List<String> aliases = new ArrayList<>();

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
      this.id = id;
      this.ordinal = ordinal;
      this.sourceTerm = sourceTerm;
      this.targetTerm = targetTerm;
      this.priority = priority;
      this.casePolicy = casePolicy;
      this.matchMode = matchMode;
      this.doNotTranslate = doNotTranslate;
      this.contextNote = contextNote;
    }

    private TermRecord toRecord() {
      return new TermRecord(
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
}
