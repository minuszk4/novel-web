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

/** Loads validated publication candidates and atomically promotes immutable glossary revisions. */
@Repository
@Profile("postgres")
public class GlossaryRevisionPublicationRepository {

  private final DataSource dataSource;

  public GlossaryRevisionPublicationRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public Candidate loadCandidate(UUID actorUserId, UUID glossaryId, UUID revisionId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(glossaryId, "glossary id is required");
    Objects.requireNonNull(revisionId, "revision id is required");

    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        requireActiveActorReadOnly(connection, actorUserId);
        GlossaryState glossary = writableGlossary(connection, actorUserId, glossaryId);
        RevisionMetadata proposedMetadata = revisionMetadata(connection, glossaryId, revisionId);
        if (proposedMetadata == null) throw new GlossaryNotFoundException();
        boolean alreadyActive = revisionId.equals(glossary.activeRevisionId());
        if (!alreadyActive && !revisionId.equals(latestRevisionId(connection, glossaryId))) {
          throw new RevisionNotLatestException(glossary.version());
        }
        var proposed =
            revision(
                connection,
                glossaryId,
                proposedMetadata,
                glossary.scope(),
                glossary.sourceLanguage());
        var base =
            proposedMetadata.parentRevisionId() == null
                ? null
                : revision(
                    connection,
                    glossaryId,
                    revisionMetadata(connection, glossaryId, proposedMetadata.parentRevisionId()),
                    glossary.scope(),
                    glossary.sourceLanguage());
        connection.commit();
        return new Candidate(
            glossary.version(),
            glossary.activeRevisionId(),
            proposedMetadata.revisionNumber(),
            base,
            proposed);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (GlossaryNotFoundException | RevisionNotLatestException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load glossary publication candidate", exception);
    }
  }

  public Published publishRevision(
      UUID actorUserId, UUID glossaryId, UUID revisionId, long expectedVersion) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(glossaryId, "glossary id is required");
    Objects.requireNonNull(revisionId, "revision id is required");
    if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");

    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        requireActiveActor(connection, actorUserId);
        GlossaryState glossary = lockWritableGlossary(connection, actorUserId, glossaryId);
        if (revisionId.equals(glossary.activeRevisionId())) {
          int currentRevisionNumber = revisionNumber(connection, glossaryId, revisionId);
          connection.commit();
          return new Published(
              glossaryId, revisionId, currentRevisionNumber, glossary.version(), null, true);
        }
        if (glossary.version() != expectedVersion) {
          throw new VersionConflictException(glossary.version());
        }
        if (!revisionId.equals(latestRevisionId(connection, glossaryId))) {
          throw new RevisionNotLatestException(glossary.version());
        }
        int revisionNumber = revisionNumber(connection, glossaryId, revisionId);
        UUID previousRevisionId = glossary.activeRevisionId();
        long nextVersion =
            updateActiveRevision(connection, glossaryId, revisionId, expectedVersion);
        insertPublicationEvent(
            connection, glossaryId, revisionId, previousRevisionId, actorUserId, nextVersion);
        connection.commit();
        return new Published(
            glossaryId, revisionId, revisionNumber, nextVersion, previousRevisionId, false);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (GlossaryNotFoundException
        | VersionConflictException
        | RevisionNotLatestException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to publish glossary revision", exception);
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

  private static void requireActiveActorReadOnly(Connection connection, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT id FROM users WHERE id = ? AND status = 'active'")) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new GlossaryNotFoundException();
      }
    }
  }

  private static GlossaryState writableGlossary(
      Connection connection, UUID actorUserId, UUID glossaryId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT g.version, g.scope_type, g.owner_user_id, g.library_id, g.source_language,
                       g.active_revision_id
                FROM glossaries g
                WHERE g.id = ? AND g.deleted_at IS NULL
                """)) {
      select.setObject(1, glossaryId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new GlossaryNotFoundException();
        String scope = row.getString("scope_type");
        UUID owner = row.getObject("owner_user_id", UUID.class);
        UUID libraryId = row.getObject("library_id", UUID.class);
        if (!isOwnerGlobal(scope, owner, actorUserId)
            && (libraryId == null || !canWriteLibrary(connection, libraryId, actorUserId))) {
          throw new GlossaryNotFoundException();
        }
        return new GlossaryState(
            row.getLong("version"),
            GlossaryMatcher.Scope.valueOf(scope),
            row.getString("source_language"),
            row.getObject("active_revision_id", UUID.class));
      }
    }
  }

  private static GlossaryState lockWritableGlossary(
      Connection connection, UUID actorUserId, UUID glossaryId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT g.version, g.scope_type, g.owner_user_id, g.library_id, g.source_language,
                       g.active_revision_id
                FROM glossaries g
                WHERE g.id = ? AND g.deleted_at IS NULL
                FOR UPDATE
                """)) {
      select.setObject(1, glossaryId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new GlossaryNotFoundException();
        String scope = row.getString("scope_type");
        UUID owner = row.getObject("owner_user_id", UUID.class);
        UUID libraryId = row.getObject("library_id", UUID.class);
        if (!isOwnerGlobal(scope, owner, actorUserId)
            && (libraryId == null || !canWriteLibrary(connection, libraryId, actorUserId))) {
          throw new GlossaryNotFoundException();
        }
        return new GlossaryState(
            row.getLong("version"),
            GlossaryMatcher.Scope.valueOf(scope),
            row.getString("source_language"),
            row.getObject("active_revision_id", UUID.class));
      }
    }
  }

  private static boolean isOwnerGlobal(String scope, UUID owner, UUID actorUserId) {
    return "USER_GLOBAL".equals(scope) && actorUserId.equals(owner);
  }

  private static boolean canWriteLibrary(Connection connection, UUID libraryId, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT can_access_library(?, ?, TRUE)")) {
      select.setObject(1, libraryId);
      select.setObject(2, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() && row.getBoolean(1);
      }
    }
  }

  private static RevisionMetadata revisionMetadata(
      Connection connection, UUID glossaryId, UUID revisionId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, revision_no, parent_revision_id
                FROM glossary_revisions WHERE glossary_id = ? AND id = ?
                """)) {
      select.setObject(1, glossaryId);
      select.setObject(2, revisionId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return null;
        return new RevisionMetadata(
            row.getObject("id", UUID.class),
            row.getInt("revision_no"),
            row.getObject("parent_revision_id", UUID.class));
      }
    }
  }

  private static UUID latestRevisionId(Connection connection, UUID glossaryId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id FROM glossary_revisions
                WHERE glossary_id = ? ORDER BY revision_no DESC LIMIT 1
                """)) {
      select.setObject(1, glossaryId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? row.getObject(1, UUID.class) : null;
      }
    }
  }

  private static GlossaryRevisionPreviewService.GlossaryRevision revision(
      Connection connection,
      UUID glossaryId,
      RevisionMetadata revision,
      GlossaryMatcher.Scope scope,
      String sourceLanguage)
      throws SQLException {
    if (revision == null) return null;
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
      select.setObject(1, revision.revisionId());
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          UUID termId = rows.getObject("id", UUID.class);
          TermAccumulator term = terms.get(termId);
          if (term == null) {
            term = accumulator(rows, termId);
            terms.put(termId, term);
          }
          String alias = rows.getString("alias_text");
          if (alias != null) term.aliases().add(alias);
        }
      }
    }
    List<GlossaryMatcher.Term> matcherTerms =
        terms.values().stream()
            .map(
                term ->
                    new GlossaryMatcher.Term(
                        term.id(),
                        revision.revisionId(),
                        sourceLanguage,
                        term.sourceTerm(),
                        term.targetTerm(),
                        term.aliases(),
                        scope,
                        term.priority(),
                        term.casePolicy(),
                        term.matchMode(),
                        term.doNotTranslate(),
                        term.ordinal(),
                        term.contextNote()))
            .toList();
    return new GlossaryRevisionPreviewService.GlossaryRevision(
        glossaryId, revision.revisionId(), revision.parentRevisionId(), matcherTerms);
  }

  private static TermAccumulator accumulator(ResultSet row, UUID termId) throws SQLException {
    return new TermAccumulator(
        termId,
        row.getInt("ordinal"),
        row.getString("source_term"),
        row.getString("target_term"),
        row.getInt("priority"),
        GlossaryMatcher.CasePolicy.valueOf(row.getString("case_policy")),
        GlossaryMatcher.MatchMode.valueOf(row.getString("match_mode")),
        row.getBoolean("do_not_translate"),
        row.getString("context_note"),
        new ArrayList<>());
  }

  private static int revisionNumber(Connection connection, UUID glossaryId, UUID revisionId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT revision_no FROM glossary_revisions WHERE glossary_id = ? AND id = ?")) {
      select.setObject(1, glossaryId);
      select.setObject(2, revisionId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new GlossaryNotFoundException();
        return row.getInt(1);
      }
    }
  }

  private static long updateActiveRevision(
      Connection connection, UUID glossaryId, UUID revisionId, long expectedVersion)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE glossaries SET active_revision_id = ?, version = version + 1,
                    updated_at = clock_timestamp()
                WHERE id = ? AND version = ? AND deleted_at IS NULL
                RETURNING version
                """)) {
      update.setObject(1, revisionId);
      update.setObject(2, glossaryId);
      update.setLong(3, expectedVersion);
      try (ResultSet row = update.executeQuery()) {
        if (!row.next()) throw new SQLException("Glossary version changed while locked");
        return row.getLong(1);
      }
    }
  }

  private static void insertPublicationEvent(
      Connection connection,
      UUID glossaryId,
      UUID revisionId,
      UUID previousRevisionId,
      UUID actorUserId,
      long glossaryVersion)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO glossary_publication_events
                    (id, glossary_id, revision_id, previous_revision_id,
                     published_by_user_id, glossary_version)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, glossaryId);
      insert.setObject(3, revisionId);
      insert.setObject(4, previousRevisionId);
      insert.setObject(5, actorUserId);
      insert.setLong(6, glossaryVersion);
      insert.executeUpdate();
    }
  }

  public record Candidate(
      long glossaryVersion,
      UUID activeRevisionId,
      int revisionNumber,
      GlossaryRevisionPreviewService.GlossaryRevision base,
      GlossaryRevisionPreviewService.GlossaryRevision proposed) {}

  public record Published(
      UUID glossaryId,
      UUID revisionId,
      int revisionNumber,
      long glossaryVersion,
      UUID previousRevisionId,
      boolean alreadyActive) {}

  private record GlossaryState(
      long version, GlossaryMatcher.Scope scope, String sourceLanguage, UUID activeRevisionId) {}

  private record RevisionMetadata(UUID revisionId, int revisionNumber, UUID parentRevisionId) {}

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
      List<String> aliases) {}

  public static class GlossaryNotFoundException extends RuntimeException {}

  public static final class VersionConflictException extends RuntimeException {
    private final long currentVersion;

    public VersionConflictException(long currentVersion) {
      this.currentVersion = currentVersion;
    }

    public long currentVersion() {
      return currentVersion;
    }
  }

  public static final class RevisionNotLatestException extends RuntimeException {
    private final long currentVersion;

    public RevisionNotLatestException(long currentVersion) {
      this.currentVersion = currentVersion;
    }

    public long currentVersion() {
      return currentVersion;
    }
  }
}
