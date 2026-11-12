package com.novelweb.library.imports;

import com.novelweb.library.imports.EpubPackageParser.Chapter;
import com.novelweb.library.imports.EpubPackageParser.Preview;
import com.novelweb.library.imports.EpubPackageParser.SourceSegment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Commits a private, reviewed EPUB preview and its complete source as one database transaction. */
@Service
@Profile("postgres")
public class ImportCommitService {

  private static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9._~-]{16,128}";

  private final DataSource dataSource;
  private final ObjectMapper objectMapper;
  private final ImportStagingFileStore fileStore;

  public ImportCommitService(
      DataSource dataSource, ObjectMapper objectMapper, ImportStagingFileStore fileStore) {
    this.dataSource = dataSource;
    this.objectMapper = objectMapper;
    this.fileStore = fileStore;
  }

  public CommitResult commit(UUID userId, UUID importId, String ifMatch, String idempotencyKey) {
    if (ifMatch == null || ifMatch.isBlank())
      return CommitResult.outcome(Outcome.PRECONDITION_REQUIRED);
    byte[] expectedEtag = parseStrongEtag(ifMatch);
    if (expectedEtag == null) return CommitResult.outcome(Outcome.INVALID_ETAG);
    if (idempotencyKey == null || !idempotencyKey.matches(IDEMPOTENCY_KEY_PATTERN)) {
      return CommitResult.outcome(Outcome.INVALID_IDEMPOTENCY_KEY);
    }

    byte[] requestHash = sha256((importId + "\n" + ifMatch).getBytes(StandardCharsets.UTF_8));
    CommitResult result;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<ImportJob> maybeJob = lockImportJob(connection, userId, importId);
        if (maybeJob.isEmpty()) return finish(connection, CommitResult.outcome(Outcome.NOT_FOUND));
        ImportJob job = maybeJob.orElseThrow();

        Optional<CommitRow> byKey = findCommitByKey(connection, userId, idempotencyKey);
        if (byKey.isPresent()) {
          CommitRow prior = byKey.orElseThrow();
          if (!prior.importId().equals(importId)
              || !MessageDigest.isEqual(prior.requestHash(), requestHash)) {
            return finish(connection, CommitResult.outcome(Outcome.IDEMPOTENCY_KEY_REUSED));
          }
          CommitResult replay = findCommittedWork(connection, prior.workId());
          result = finish(connection, replay);
          deleteStagingAfterCommit(importId);
          return result;
        }

        if ("committed".equals(job.status())) {
          return finish(connection, CommitResult.outcome(Outcome.ALREADY_COMMITTED));
        }
        if ("cancelled".equals(job.status())) {
          return finish(connection, CommitResult.outcome(Outcome.CANCELLED));
        }
        if (!job.live()) return finish(connection, CommitResult.outcome(Outcome.EXPIRED));
        if (!"uploaded".equals(job.status())) {
          return finish(connection, CommitResult.outcome(Outcome.NOT_UPLOADED));
        }

        Optional<ParseRecord> maybeParse = lockParseJob(connection, importId);
        if (maybeParse.isEmpty() || !"ready_to_commit".equals(maybeParse.orElseThrow().status())) {
          return finish(connection, CommitResult.outcome(Outcome.NOT_READY));
        }
        ParseRecord parse = maybeParse.orElseThrow();
        if (!MessageDigest.isEqual(parse.previewEtag(), expectedEtag)) {
          return finish(connection, CommitResult.outcome(Outcome.STALE_ETAG));
        }
        if (!supportedParser(parse.parserVersion())) {
          return finish(connection, CommitResult.outcome(Outcome.CONTENT_UNAVAILABLE));
        }

        PolicyState policy = policyState(connection, userId);
        if (policy == PolicyState.NOT_CONFIGURED) {
          return finish(connection, CommitResult.outcome(Outcome.POLICY_NOT_CONFIGURED));
        }
        if (policy == PolicyState.REQUIRED) {
          return finish(connection, CommitResult.outcome(Outcome.POLICY_REQUIRED));
        }
        Optional<String> currentRightsVersion = currentRightsVersion(connection);
        if (currentRightsVersion.isEmpty()) {
          return finish(connection, CommitResult.outcome(Outcome.RIGHTS_NOT_CONFIGURED));
        }
        if (!currentRightsVersion.orElseThrow().equals(job.rightsStatementVersion())
            || !receiptIsConsumedForJob(connection, userId, job)) {
          return finish(connection, CommitResult.outcome(Outcome.RIGHTS_STALE));
        }
        if (!hasWriteAccess(connection, userId, job.libraryId())) {
          return finish(connection, CommitResult.outcome(Outcome.LIBRARY_NOT_FOUND));
        }

        Preview preview = readPreview(parse.previewJson());
        if (!supportedParser(preview.parserVersion())
            || !parse.parserVersion().equals(preview.parserVersion())
            || preview.chapters().isEmpty()
            || preview.chapters().size() > 2_000) {
          return finish(connection, CommitResult.outcome(Outcome.CONTENT_UNAVAILABLE));
        }

        UUID workId = UUID.randomUUID();
        Work work = insertPrivateWork(connection, workId, userId, job, preview);
        int persistedChapters = 0;
        CommitBudget budget = new CommitBudget();
        for (int index = 0; index < preview.chapters().size(); index++) {
          Chapter chapter = preview.chapters().get(index);
          persistChapter(
              connection,
              userId,
              workId,
              index + 1,
              chapter,
              preview.metadata().language(),
              preview.parserVersion(),
              budget);
          persistedChapters++;
        }

        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                        INSERT INTO import_commits (import_id, user_id, idempotency_key, request_sha256, work_id)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
          insert.setObject(1, importId);
          insert.setObject(2, userId);
          insert.setString(3, idempotencyKey);
          insert.setBytes(4, requestHash);
          insert.setObject(5, workId);
          insert.executeUpdate();
        }
        transitionParseJobToCommitted(connection, importId, parse);
        transitionImportJobToCommitted(connection, importId);
        connection.commit();
        result =
            CommitResult.committed(
                new CommittedWork(
                    work.id(),
                    work.libraryId(),
                    work.title(),
                    work.author(),
                    work.language(),
                    "private",
                    persistedChapters,
                    work.createdAt()));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        if (exception instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
          return CommitResult.outcome(Outcome.COMMIT_CONFLICT);
        }
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to commit the private import", exception);
    }

    if (result.outcome() == Outcome.COMMITTED) deleteStagingAfterCommit(importId);
    return result;
  }

  private static Optional<ImportJob> lockImportJob(
      Connection connection, UUID userId, UUID importId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, library_id, rights_acceptance_id, rights_statement_version, content_sha256,
                       status, (expires_at > clock_timestamp()) AS live
                FROM import_jobs
                WHERE id = ? AND user_id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, importId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new ImportJob(
                row.getObject("id", UUID.class),
                row.getObject("library_id", UUID.class),
                row.getObject("rights_acceptance_id", UUID.class),
                row.getString("rights_statement_version"),
                row.getBytes("content_sha256"),
                row.getString("status"),
                row.getBoolean("live")));
      }
    }
  }

  private static Optional<ParseRecord> lockParseJob(Connection connection, UUID importId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT status, parser_version, preview_data::text AS preview_json, preview_etag
                FROM import_parse_jobs
                WHERE import_id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new ParseRecord(
                    row.getString("status"),
                    row.getString("parser_version"),
                    row.getString("preview_json"),
                    row.getBytes("preview_etag")))
            : Optional.empty();
      }
    }
  }

  private static Optional<CommitRow> findCommitByKey(Connection connection, UUID userId, String key)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT import_id, request_sha256, work_id
                FROM import_commits
                WHERE user_id = ? AND idempotency_key = ?
                """)) {
      select.setObject(1, userId);
      select.setString(2, key);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new CommitRow(
                    row.getObject("import_id", UUID.class),
                    row.getBytes("request_sha256"),
                    row.getObject("work_id", UUID.class)))
            : Optional.empty();
      }
    }
  }

  private static CommitResult findCommittedWork(Connection connection, UUID workId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT w.id, w.library_id, w.title, w.author, w.source_language, w.visibility,
                       w.created_at, COUNT(c.id)::integer AS chapter_count
                FROM works w
                LEFT JOIN chapters c ON c.work_id = w.id AND c.deleted_at IS NULL
                WHERE w.id = ?
                GROUP BY w.id
                """)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("The committed private work is unavailable");
        return CommitResult.committed(
            new CommittedWork(
                row.getObject("id", UUID.class),
                row.getObject("library_id", UUID.class),
                row.getString("title"),
                row.getString("author"),
                row.getString("source_language"),
                row.getString("visibility"),
                row.getInt("chapter_count"),
                row.getObject("created_at", OffsetDateTime.class).toInstant()));
      }
    }
  }

  private static Work insertPrivateWork(
      Connection connection, UUID workId, UUID actorUserId, ImportJob job, Preview preview)
      throws SQLException {
    String title = requiredText(preview.metadata().title(), 300, "Imported work title");
    String author =
        preview.metadata().creators() == null || preview.metadata().creators().isEmpty()
            ? ""
            : truncate(preview.metadata().creators().get(0).trim(), 200);
    String language = normalizeLanguage(preview.metadata().language());
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO works (id, library_id, owner_user_id, title, author, source_language,
                                   source_kind, visibility, status, source_hash)
                SELECT ?, l.id, l.owner_user_id, ?, ?, ?, 'imported', 'private', 'active', ?
                FROM libraries l
                WHERE l.id = ? AND l.deleted_at IS NULL
                RETURNING id, library_id, title, author, source_language, created_at
                """)) {
      insert.setObject(1, workId);
      insert.setString(2, title);
      insert.setString(3, author);
      insert.setString(4, language);
      insert.setBytes(5, job.sourceHash());
      insert.setObject(6, job.libraryId());
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("The destination library is unavailable");
        return new Work(
            row.getObject("id", UUID.class),
            row.getObject("library_id", UUID.class),
            row.getString("title"),
            row.getString("author"),
            row.getString("source_language"),
            row.getObject("created_at", OffsetDateTime.class).toInstant());
      }
    }
  }

  private static void persistChapter(
      Connection connection,
      UUID actorUserId,
      UUID workId,
      int ordinal,
      Chapter chapter,
      String workLanguage,
      String parserVersion,
      CommitBudget budget)
      throws SQLException {
    UUID chapterId = UUID.randomUUID();
    String title =
        chapter.title() == null || chapter.title().isBlank()
            ? "Chapter " + ordinal
            : requiredText(chapter.title(), 300, "Chapter title");
    String language = normalizeLanguage(workLanguage);
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO chapters (id, work_id, ordinal, title, source_language, status)
                VALUES (?, ?, ?, ?, ?, 'draft')
                """)) {
      insert.setObject(1, chapterId);
      insert.setObject(2, workId);
      insert.setInt(3, ordinal);
      insert.setString(4, title);
      insert.setString(5, language);
      insert.executeUpdate();
    }

    List<SourceSegment> sourceSegments =
        chapter.segments() == null ? List.of() : chapter.segments();
    if (sourceSegments.isEmpty()) return;
    List<NormalizedSegment> segments = normalizeSegments(sourceSegments, budget);
    String normalizedContent =
        String.join("\n\n", segments.stream().map(NormalizedSegment::text).toList());
    byte[] contentHash = sha256(normalizedContent.getBytes(StandardCharsets.UTF_8));
    UUID revisionId = UUID.randomUUID();
    String sourceFormat =
        switch (parserVersion) {
          case "txt-v1" -> "plain_text";
          case "html-v1" -> "xhtml";
          case "markdown-v1" -> "markdown";
          default -> "epub_xhtml";
        };
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO chapter_revisions
                    (id, chapter_id, revision_no, source_format, normalized_content, content_hash, created_by_user_id)
                VALUES (?, ?, 1, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, revisionId);
      insert.setObject(2, chapterId);
      insert.setString(3, sourceFormat);
      insert.setString(4, normalizedContent);
      insert.setBytes(5, contentHash);
      insert.setObject(6, actorUserId);
      insert.executeUpdate();
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO segments
                    (id, chapter_revision_id, ordinal, segment_type, source_text, stable_anchor, checksum)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
      for (int index = 0; index < segments.size(); index++) {
        NormalizedSegment segment = segments.get(index);
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, revisionId);
        insert.setInt(3, index + 1);
        insert.setString(4, segment.type());
        insert.setString(5, segment.text());
        String anchorPrefix =
            switch (parserVersion) {
              case "txt-v1" -> "txt";
              case "html-v1" -> "html";
              case "markdown-v1" -> "md";
              default -> "epub";
            };
        insert.setString(
            6, anchorPrefix + "-c" + Math.max(0, chapter.index()) + "-s" + (index + 1));
        insert.setBytes(7, sha256(segment.text().getBytes(StandardCharsets.UTF_8)));
        insert.addBatch();
      }
      insert.executeBatch();
    }
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE chapters
                SET source_revision_id = ?, status = 'ready', updated_at = clock_timestamp()
                WHERE id = ? AND work_id = ?
                """)) {
      update.setObject(1, revisionId);
      update.setObject(2, chapterId);
      update.setObject(3, workId);
      if (update.executeUpdate() != 1) throw new SQLException("The imported chapter disappeared");
    }
  }

  private static boolean supportedParser(String parserVersion) {
    return "epub3-v2".equals(parserVersion)
        || "txt-v1".equals(parserVersion)
        || "html-v1".equals(parserVersion)
        || "markdown-v1".equals(parserVersion);
  }

  private static List<NormalizedSegment> normalizeSegments(
      List<SourceSegment> input, CommitBudget budget) throws SQLException {
    List<NormalizedSegment> result = new ArrayList<>(input.size());
    for (SourceSegment segment : input) {
      if (segment == null || segment.text() == null || segment.text().isBlank()) {
        throw new SQLException("The staged source segment is invalid");
      }
      String text = segment.text();
      int size = text.getBytes(StandardCharsets.UTF_8).length;
      if (size > 16_384 || text.codePointCount(0, text.length()) > 4_000) {
        throw new SQLException("The staged source segment exceeds its accepted bound");
      }
      budget.segmentCount++;
      budget.sourceBytes += size;
      if (budget.segmentCount > 5_000 || budget.sourceBytes > 384L * 1024) {
        throw new SQLException("The staged source text exceeds its accepted bound");
      }
      result.add(new NormalizedSegment(segmentType(segment.kind()), text));
    }
    return List.copyOf(result);
  }

  private static String segmentType(String kind) {
    if (kind == null) return "paragraph";
    String tag = kind.toLowerCase(Locale.ROOT);
    if (tag.matches("h[1-6]")) return "heading";
    if ("blockquote".equals(tag)) return "quote";
    return "paragraph";
  }

  private Preview readPreview(String json) {
    try {
      return objectMapper.readValue(json, Preview.class);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("The staged private EPUB preview is invalid", exception);
    }
  }

  private static void transitionParseJobToCommitted(
      Connection connection, UUID importId, ParseRecord parse) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE import_parse_jobs
                SET status = 'committed', preview_data = NULL, preview_etag = NULL, preview_version = NULL,
                    updated_at = clock_timestamp()
                WHERE import_id = ? AND status = 'ready_to_commit' AND preview_etag = ?
                """)) {
      update.setObject(1, importId);
      update.setBytes(2, parse.previewEtag());
      if (update.executeUpdate() != 1)
        throw new SQLException("The reviewed import preview changed");
    }
  }

  private static void transitionImportJobToCommitted(Connection connection, UUID importId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE import_jobs
                SET status = 'committed'
                WHERE id = ? AND status = 'uploaded' AND expires_at > clock_timestamp()
                """)) {
      update.setObject(1, importId);
      if (update.executeUpdate() != 1)
        throw new SQLException("The uploaded import is no longer live");
    }
  }

  private static boolean receiptIsConsumedForJob(Connection connection, UUID userId, ImportJob job)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT 1
                FROM import_rights_acceptances
                WHERE id = ? AND user_id = ? AND statement_version = ? AND consumed_at IS NOT NULL
                FOR SHARE
                """)) {
      select.setObject(1, job.rightsAcceptanceId());
      select.setObject(2, userId);
      select.setString(3, job.rightsStatementVersion());
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static PolicyState policyState(Connection connection, UUID userId) throws SQLException {
    Map<String, String> current = new java.util.HashMap<>();
    try (PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT DISTINCT ON (policy_type) policy_type, version
                FROM policy_documents
                WHERE effective_at <= clock_timestamp()
                ORDER BY policy_type, effective_at DESC, version DESC
                """);
        ResultSet rows = select.executeQuery()) {
      while (rows.next()) current.put(rows.getString("policy_type"), rows.getString("version"));
    }
    String terms = current.get("TERMS");
    String privacy = current.get("PRIVACY_NOTICE");
    if (terms == null || privacy == null) return PolicyState.NOT_CONFIGURED;
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT
                  EXISTS (SELECT 1 FROM policy_acceptances
                          WHERE user_id = ? AND policy_type = 'TERMS' AND version = ?
                            AND decision = 'TERMS_ACCEPTED') AS terms_accepted,
                  EXISTS (SELECT 1 FROM policy_acceptances
                          WHERE user_id = ? AND policy_type = 'PRIVACY_NOTICE' AND version = ?
                            AND decision = 'PRIVACY_NOTICE_ACKNOWLEDGED') AS privacy_acknowledged
                """)) {
      select.setObject(1, userId);
      select.setString(2, terms);
      select.setObject(3, userId);
      select.setString(4, privacy);
      try (ResultSet row = select.executeQuery()) {
        row.next();
        return row.getBoolean("terms_accepted") && row.getBoolean("privacy_acknowledged")
            ? PolicyState.COMPLETE
            : PolicyState.REQUIRED;
      }
    }
  }

  private static Optional<String> currentRightsVersion(Connection connection) throws SQLException {
    try (PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT version
                FROM import_rights_statements
                WHERE status = 'APPROVED' AND effective_at <= clock_timestamp()
                ORDER BY effective_at DESC, version DESC
                LIMIT 1
                """);
        ResultSet row = select.executeQuery()) {
      return row.next() ? Optional.of(row.getString("version")) : Optional.empty();
    }
  }

  private static boolean hasWriteAccess(Connection connection, UUID userId, UUID libraryId)
      throws SQLException {
    UUID workspaceId;
    UUID ownerUserId;
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT owner_user_id, workspace_id
                FROM libraries
                WHERE id = ? AND deleted_at IS NULL
                FOR SHARE
                """)) {
      select.setObject(1, libraryId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return false;
        ownerUserId = row.getObject("owner_user_id", UUID.class);
        workspaceId = row.getObject("workspace_id", UUID.class);
      }
    }
    if (workspaceId == null) return userId.equals(ownerUserId);
    try (PreparedStatement member =
        connection.prepareStatement(
            """
                SELECT 1
                FROM workspace_members
                WHERE workspace_id = ? AND user_id = ? AND status = 'active'
                  AND role IN ('owner', 'admin', 'editor')
                FOR SHARE
                """)) {
      member.setObject(1, workspaceId);
      member.setObject(2, userId);
      try (ResultSet active = member.executeQuery()) {
        return active.next();
      }
    }
  }

  private void deleteStagingAfterCommit(UUID importId) {
    try {
      fileStore.delete(importId);
    } catch (IOException exception) {
      return;
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                     UPDATE import_jobs
                     SET staging_file_deleted_at = clock_timestamp()
                     WHERE id = ? AND status = 'committed' AND staging_file_deleted_at IS NULL
                     """)) {
      update.setObject(1, importId);
      update.executeUpdate();
    } catch (SQLException ignored) {
      // The janitor retries the one-time marker after it observes a committed job without a marker.
    }
  }

  private static CommitResult finish(Connection connection, CommitResult result)
      throws SQLException {
    connection.commit();
    return result;
  }

  private static String requiredText(String value, int maxCodePoints, String label)
      throws SQLException {
    if (value == null || value.isBlank()) throw new SQLException(label + " is missing");
    String normalized = value.trim();
    if (normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
      throw new SQLException(label + " exceeds its accepted length");
    }
    return normalized;
  }

  private static String truncate(String value, int maxCodePoints) {
    if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints)
      return value == null ? "" : value;
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
  }

  private static String normalizeLanguage(String value) throws SQLException {
    String language = value == null || value.isBlank() ? "und" : value.trim();
    if (language.length() < 2 || language.length() > 35)
      throw new SQLException("The language value is invalid");
    return language;
  }

  public static byte[] parseStrongEtag(String value) {
    if (value == null
        || value.length() != 45
        || value.charAt(0) != '"'
        || value.charAt(44) != '"'
        || value.startsWith("W/")) return null;
    String encoded = value.substring(1, 44);
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(encoded);
      return decoded.length == 32
              && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)
          ? decoded
          : null;
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private enum PolicyState {
    COMPLETE,
    REQUIRED,
    NOT_CONFIGURED
  }

  private record ImportJob(
      UUID id,
      UUID libraryId,
      UUID rightsAcceptanceId,
      String rightsStatementVersion,
      byte[] sourceHash,
      String status,
      boolean live) {}

  private record ParseRecord(
      String status, String parserVersion, String previewJson, byte[] previewEtag) {}

  private record CommitRow(UUID importId, byte[] requestHash, UUID workId) {}

  private record Work(
      UUID id, UUID libraryId, String title, String author, String language, Instant createdAt) {}

  private record NormalizedSegment(String type, String text) {}

  private static final class CommitBudget {
    private int segmentCount;
    private long sourceBytes;
  }

  public record CommittedWork(
      UUID id,
      UUID libraryId,
      String title,
      String author,
      String language,
      String visibility,
      int chapterCount,
      Instant createdAt) {}

  public record CommitResult(Outcome outcome, CommittedWork work) {
    static CommitResult outcome(Outcome outcome) {
      return new CommitResult(outcome, null);
    }

    static CommitResult committed(CommittedWork work) {
      return new CommitResult(Outcome.COMMITTED, work);
    }
  }

  public enum Outcome {
    COMMITTED,
    NOT_FOUND,
    EXPIRED,
    CANCELLED,
    NOT_UPLOADED,
    NOT_READY,
    STALE_ETAG,
    PRECONDITION_REQUIRED,
    INVALID_ETAG,
    INVALID_IDEMPOTENCY_KEY,
    IDEMPOTENCY_KEY_REUSED,
    ALREADY_COMMITTED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    RIGHTS_NOT_CONFIGURED,
    RIGHTS_STALE,
    LIBRARY_NOT_FOUND,
    CONTENT_UNAVAILABLE,
    COMMIT_CONFLICT
  }
}
