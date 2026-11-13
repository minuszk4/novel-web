package com.novelweb.translation.glossary;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Creates complete immutable glossary draft revisions with actor and version checks. */
@Repository
@Profile("postgres")
public class GlossaryRevisionWriteRepository {

  public static final int MAX_DRAFT_TERMS = 1000;
  public static final int MAX_ALIASES_PER_TERM = 25;
  public static final int MAX_DRAFT_ALIASES = 5000;

  private final DataSource dataSource;

  public GlossaryRevisionWriteRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  /**
   * Creates a new immutable draft snapshot. This does not change the glossary's active revision.
   * The glossary version serializes writers and acts as the If-Match token for this command.
   */
  public DraftCreated createDraftRevision(
      UUID actorUserId, UUID glossaryId, long expectedVersion, List<TermDraft> requestedTerms) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(glossaryId, "glossary id is required");
    if (expectedVersion < 1) throw new IllegalArgumentException("expectedVersion must be positive");
    List<TermDraft> terms = List.copyOf(requestedTerms);
    validateTerms(terms);

    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        requireActiveActor(connection, actorUserId);
        GlossaryState glossary = lockWritableGlossary(connection, actorUserId, glossaryId);
        if (glossary.version() != expectedVersion) {
          throw new VersionConflictException(glossary.version());
        }

        RevisionParent parent = latestRevision(connection, glossaryId);
        int revisionNumber = Math.addExact(parent.revisionNumber(), 1);
        UUID revisionId = UUID.randomUUID();
        insertRevision(
            connection, revisionId, glossaryId, revisionNumber, parent.revisionId(), actorUserId);
        insertTerms(connection, revisionId, terms);
        long newVersion = incrementGlossaryVersion(connection, glossaryId, expectedVersion);
        connection.commit();
        return new DraftCreated(
            glossaryId, revisionId, revisionNumber, parent.revisionId(), newVersion);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (GlossaryNotFoundException | VersionConflictException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to create glossary draft revision", exception);
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

  private static GlossaryState lockWritableGlossary(
      Connection connection, UUID actorUserId, UUID glossaryId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, version, scope_type, owner_user_id, library_id
                FROM glossaries
                WHERE id = ? AND deleted_at IS NULL
                FOR UPDATE
                """)) {
      select.setObject(1, glossaryId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new GlossaryNotFoundException();
        String scope = row.getString("scope_type");
        UUID ownerUserId = row.getObject("owner_user_id", UUID.class);
        UUID libraryId = row.getObject("library_id", UUID.class);
        boolean isOwnerGlobal = "USER_GLOBAL".equals(scope) && actorUserId.equals(ownerUserId);
        if (!isOwnerGlobal
            && (libraryId == null || !canWriteLibrary(connection, libraryId, actorUserId))) {
          throw new GlossaryNotFoundException();
        }
        return new GlossaryState(row.getLong("version"));
      }
    }
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

  private static RevisionParent latestRevision(Connection connection, UUID glossaryId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, revision_no FROM glossary_revisions
                WHERE glossary_id = ? ORDER BY revision_no DESC LIMIT 1
                """)) {
      select.setObject(1, glossaryId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return new RevisionParent(null, 0);
        return new RevisionParent(row.getObject("id", UUID.class), row.getInt("revision_no"));
      }
    }
  }

  private static void insertRevision(
      Connection connection,
      UUID revisionId,
      UUID glossaryId,
      int revisionNumber,
      UUID parentRevisionId,
      UUID actorUserId)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO glossary_revisions
                    (id, glossary_id, revision_no, parent_revision_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, revisionId);
      insert.setObject(2, glossaryId);
      insert.setInt(3, revisionNumber);
      insert.setObject(4, parentRevisionId);
      insert.setObject(5, actorUserId);
      insert.executeUpdate();
    }
  }

  private static void insertTerms(Connection connection, UUID revisionId, List<TermDraft> terms)
      throws SQLException {
    if (terms.isEmpty()) return;
    try (PreparedStatement insertTerm =
            connection.prepareStatement(
                """
                    INSERT INTO glossary_terms
                        (id, revision_id, ordinal, source_term, target_term, priority,
                         case_policy, match_mode, do_not_translate, context_note)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
        PreparedStatement insertAlias =
            connection.prepareStatement(
                """
                    INSERT INTO glossary_aliases (revision_id, term_id, ordinal, alias_text)
                    VALUES (?, ?, ?, ?)
                    """)) {
      for (int termIndex = 0; termIndex < terms.size(); termIndex++) {
        TermDraft term = terms.get(termIndex);
        insertTerm.setObject(1, term.id());
        insertTerm.setObject(2, revisionId);
        insertTerm.setInt(3, termIndex + 1);
        insertTerm.setString(4, term.sourceTerm());
        insertTerm.setString(5, term.targetTerm());
        insertTerm.setInt(6, term.priority());
        insertTerm.setString(7, term.casePolicy().name());
        insertTerm.setString(8, term.matchMode().name());
        insertTerm.setBoolean(9, term.doNotTranslate());
        insertTerm.setString(10, term.contextNote());
        insertTerm.addBatch();

        for (int aliasIndex = 0; aliasIndex < term.aliases().size(); aliasIndex++) {
          insertAlias.setObject(1, revisionId);
          insertAlias.setObject(2, term.id());
          insertAlias.setInt(3, aliasIndex + 1);
          insertAlias.setString(4, term.aliases().get(aliasIndex));
          insertAlias.addBatch();
        }
      }
      insertTerm.executeBatch();
      insertAlias.executeBatch();
    }
  }

  private static long incrementGlossaryVersion(
      Connection connection, UUID glossaryId, long expectedVersion) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE glossaries
                SET version = version + 1, updated_at = clock_timestamp()
                WHERE id = ? AND version = ? AND deleted_at IS NULL
                RETURNING version
                """)) {
      update.setObject(1, glossaryId);
      update.setLong(2, expectedVersion);
      try (ResultSet row = update.executeQuery()) {
        if (!row.next()) throw new SQLException("Glossary version changed while locked");
        return row.getLong(1);
      }
    }
  }

  private static void validateTerms(List<TermDraft> terms) {
    if (terms.size() > MAX_DRAFT_TERMS) {
      throw new IllegalArgumentException("a glossary draft may contain at most 1000 terms");
    }
    Set<UUID> ids = new HashSet<>();
    long aliasCount = 0;
    for (TermDraft term : terms) {
      Objects.requireNonNull(term, "term is required");
      Objects.requireNonNull(term.id(), "term id is required");
      if (!ids.add(term.id())) throw new IllegalArgumentException("term IDs must be unique");
      requireBoundedText(term.sourceTerm(), "source term", 512);
      if (term.doNotTranslate()) {
        if (term.targetTerm() != null) requireLength(term.targetTerm(), "target term", 4096);
      } else {
        requireBoundedText(term.targetTerm(), "target term", 4096);
      }
      if (term.priority() < -1_000_000 || term.priority() > 1_000_000) {
        throw new IllegalArgumentException("priority is outside the supported range");
      }
      Objects.requireNonNull(term.casePolicy(), "case policy is required");
      Objects.requireNonNull(term.matchMode(), "match mode is required");
      if (term.contextNote() != null) requireLength(term.contextNote(), "context note", 2000);
      if (term.aliases().size() > MAX_ALIASES_PER_TERM) {
        throw new IllegalArgumentException("a glossary term may have at most 25 aliases");
      }
      aliasCount += term.aliases().size();
      if (aliasCount > MAX_DRAFT_ALIASES) {
        throw new IllegalArgumentException("a glossary draft may contain at most 5000 aliases");
      }
      for (String alias : term.aliases()) requireBoundedText(alias, "alias", 512);
    }
  }

  private static void requireBoundedText(String value, String field, int maximumCodePoints) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    requireLength(value, field, maximumCodePoints);
  }

  private static void requireLength(String value, String field, int maximumCodePoints) {
    if (value.codePointCount(0, value.length()) > maximumCodePoints) {
      throw new IllegalArgumentException(field + " exceeds " + maximumCodePoints + " characters");
    }
  }

  public record TermDraft(
      UUID id,
      String sourceTerm,
      String targetTerm,
      int priority,
      GlossaryMatcher.CasePolicy casePolicy,
      GlossaryMatcher.MatchMode matchMode,
      boolean doNotTranslate,
      String contextNote,
      List<String> aliases) {
    public TermDraft {
      aliases = List.copyOf(aliases);
    }
  }

  public record DraftCreated(
      UUID glossaryId,
      UUID revisionId,
      int revisionNumber,
      UUID parentRevisionId,
      long glossaryVersion) {}

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

  private record GlossaryState(long version) {}

  private record RevisionParent(UUID revisionId, int revisionNumber) {}
}
