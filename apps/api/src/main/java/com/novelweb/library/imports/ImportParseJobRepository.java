package com.novelweb.library.imports;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** JDBC persistence for the durable, private parser queue and its single bounded result. */
@Repository
@Profile("postgres")
public class ImportParseJobRepository {

  private static final int STATUS_TITLE_LIMIT = 300;
  private static final int STATUS_AUTHOR_LIMIT = 200;

  private final DataSource dataSource;

  public ImportParseJobRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Optional<UploadSource> findUploadSource(UUID userId, UUID importId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT status, created_at, original_filename, declared_content_type, actual_size_bytes,
                            content_sha256, expires_at, (expires_at > clock_timestamp()) AS live
                     FROM import_jobs
                     WHERE id = ? AND user_id = ?
                     """)) {
      select.setObject(1, importId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new UploadSource(
                importId,
                row.getString("status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getString("original_filename"),
                row.getString("declared_content_type"),
                row.getLong("actual_size_bytes"),
                row.getBytes("content_sha256"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getBoolean("live")));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to inspect the owned import upload", exception);
    }
  }

  public QueueResult enqueue(UUID userId, UUID importId, String parserVersion) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<UploadSource> source = lockUploadSource(connection, userId, importId);
        if (source.isEmpty()) {
          connection.commit();
          return QueueResult.notFound();
        }
        if (!source.orElseThrow().live()) {
          connection.commit();
          return QueueResult.expired();
        }
        if (!"uploaded".equals(source.orElseThrow().status())) {
          connection.commit();
          return QueueResult.notUploaded(source.orElseThrow().status());
        }
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                        INSERT INTO import_parse_jobs (import_id, status, attempt, parser_version)
                        VALUES (?, 'queued', 0, ?)
                        ON CONFLICT (import_id) DO NOTHING
                        """)) {
          insert.setObject(1, importId);
          insert.setString(2, parserVersion);
          insert.executeUpdate();
        }
        ParseState state = readParseState(connection, importId);
        connection.commit();
        return QueueResult.accepted(toStatus(source.orElseThrow(), state));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to queue the private import parser", exception);
    }
  }

  public StatusResult findStatus(UUID userId, UUID importId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT j.status AS upload_status, j.created_at, j.expires_at,
                            (j.expires_at > clock_timestamp()) AS live,
                            p.status AS parse_status, p.warnings::text AS warnings,
                            p.failure_code,
                            p.preview_data #>> '{metadata,title}' AS preview_title,
                            p.preview_data #> '{metadata,creators}' ->> 0 AS preview_author,
                            COALESCE(jsonb_array_length(p.preview_data -> 'chapters'), 0) AS chapter_count
                     FROM import_jobs AS j
                     LEFT JOIN import_parse_jobs AS p ON p.import_id = j.id
                     WHERE j.id = ? AND j.user_id = ?
                     """)) {
      select.setObject(1, importId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return StatusResult.notFound();
        String uploadStatus = row.getString("upload_status");
        String parseStatus = row.getString("parse_status");
        boolean live = row.getBoolean("live");
        String status =
            !live && Set.of("upload_pending", "uploaded").contains(uploadStatus)
                ? "expired"
                : parseStatus == null ? uploadStatus : parseStatus;
        PreviewSummary summary =
            previewSummary(
                row.getString("preview_title"),
                row.getString("preview_author"),
                row.getInt("chapter_count"));
        return StatusResult.found(
            new ImportStatus(
                importId,
                status,
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                live,
                row.getString("warnings"),
                row.getString("failure_code"),
                summary));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read the owned import status", exception);
    }
  }

  public PreviewLookup findPreview(UUID userId, UUID importId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT (j.expires_at > clock_timestamp()) AS live,
                            p.status AS parse_status, p.preview_data::text AS preview_json,
                            p.preview_etag, p.preview_version, p.warnings::text AS warnings
                     FROM import_jobs AS j
                     LEFT JOIN import_parse_jobs AS p ON p.import_id = j.id
                     WHERE j.id = ? AND j.user_id = ?
                     """)) {
      select.setObject(1, importId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return PreviewLookup.notFound();
        if (!row.getBoolean("live")) return PreviewLookup.expired();
        if (!"ready_to_commit".equals(row.getString("parse_status"))) {
          return PreviewLookup.notReady(row.getString("parse_status"));
        }
        return PreviewLookup.ready(
            new PreviewRecord(
                row.getString("preview_json"),
                row.getBytes("preview_etag"),
                row.getInt("preview_version"),
                row.getString("warnings")));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read the owned private import preview", exception);
    }
  }

  public PreviewUpdateResult updatePreview(
      UUID userId, UUID importId, byte[] expectedEtag, String previewJson, byte[] newEtag) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                     UPDATE import_parse_jobs AS p
                     SET preview_data = ?::jsonb, preview_etag = ?, preview_version = preview_version + 1,
                         updated_at = clock_timestamp()
                     FROM import_jobs AS j
                     WHERE p.import_id = ? AND j.id = p.import_id AND j.user_id = ?
                       AND p.status = 'ready_to_commit' AND j.expires_at > clock_timestamp()
                       AND p.preview_etag = ? AND p.preview_version < 2147483647
                     RETURNING p.preview_data::text AS preview_json, p.preview_etag,
                               p.preview_version, p.warnings::text AS warnings
                     """)) {
      update.setString(1, previewJson);
      update.setBytes(2, newEtag);
      update.setObject(3, importId);
      update.setObject(4, userId);
      update.setBytes(5, expectedEtag);
      try (ResultSet row = update.executeQuery()) {
        if (row.next()) {
          return PreviewUpdateResult.updated(
              new PreviewRecord(
                  row.getString("preview_json"),
                  row.getBytes("preview_etag"),
                  row.getInt("preview_version"),
                  row.getString("warnings")));
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to update the private import preview", exception);
    }

    PreviewLookup latest = findPreview(userId, importId);
    return switch (latest.outcome()) {
      case NOT_FOUND -> PreviewUpdateResult.notFound();
      case EXPIRED -> PreviewUpdateResult.expired();
      case NOT_READY -> PreviewUpdateResult.notReady(latest.parseStatus());
      case READY -> PreviewUpdateResult.stale(latest.preview());
    };
  }

  public Optional<ParseClaim> claimNext() {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        ParseClaim candidate = null;
        try (PreparedStatement select =
                connection.prepareStatement(
                    """
                        SELECT p.import_id, p.attempt, p.parser_version, j.original_filename, j.declared_content_type,
                               j.actual_size_bytes, j.content_sha256
                        FROM import_parse_jobs AS p
                        JOIN import_jobs AS j ON j.id = p.import_id
                        WHERE (p.status = 'queued'
                               OR (p.status = 'parsing' AND p.lease_expires_at <= clock_timestamp()))
                          AND j.status = 'uploaded' AND j.expires_at > clock_timestamp()
                        ORDER BY p.created_at, p.import_id
                        LIMIT 1
                        FOR UPDATE OF p SKIP LOCKED
                        """);
            ResultSet row = select.executeQuery()) {
          if (row.next()) {
            candidate =
                new ParseClaim(
                    row.getObject("import_id", UUID.class),
                    row.getInt("attempt") + 1,
                    row.getString("parser_version"),
                    row.getString("original_filename"),
                    row.getString("declared_content_type"),
                    row.getLong("actual_size_bytes"),
                    row.getBytes("content_sha256"));
          }
        }
        if (candidate == null) {
          connection.commit();
          return Optional.empty();
        }
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                        UPDATE import_parse_jobs
                        SET status = 'parsing', attempt = ?, lease_expires_at = clock_timestamp() + INTERVAL '5 minutes',
                            updated_at = clock_timestamp()
                        WHERE import_id = ?
                        """)) {
          update.setInt(1, candidate.attempt());
          update.setObject(2, candidate.importId());
          if (update.executeUpdate() != 1)
            throw new SQLException("Parser job changed before claim");
        }
        connection.commit();
        return Optional.of(candidate);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to claim a private import parse job", exception);
    }
  }

  public boolean completeSuccess(
      ParseClaim claim, String previewJson, byte[] etag, String warningsJson) {
    return updateCompleted(claim, "ready_to_commit", previewJson, etag, warningsJson, null);
  }

  public boolean completeFailure(ParseClaim claim, String failureCode, String warningsJson) {
    return updateCompleted(claim, "failed", null, null, warningsJson, failureCode);
  }

  private boolean updateCompleted(
      ParseClaim claim,
      String status,
      String previewJson,
      byte[] etag,
      String warningsJson,
      String failureCode) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement update =
          connection.prepareStatement(
              """
                    UPDATE import_parse_jobs AS p
                    SET status = ?, preview_data = ?::jsonb, preview_etag = ?,
                        preview_version = CASE WHEN ? = 'ready_to_commit' THEN 1 ELSE NULL END,
                        warnings = ?::jsonb,
                        failure_code = ?, lease_expires_at = NULL,
                        updated_at = clock_timestamp(), completed_at = clock_timestamp()
                    FROM import_jobs AS j
                    WHERE p.import_id = ? AND j.id = p.import_id
                      AND p.status = 'parsing' AND p.attempt = ?
                      AND p.lease_expires_at > clock_timestamp()
                      AND j.status = 'uploaded' AND j.expires_at > clock_timestamp()
                    """)) {
        update.setString(1, status);
        update.setString(2, previewJson);
        update.setBytes(3, etag);
        update.setString(4, status);
        update.setString(5, warningsJson);
        update.setString(6, failureCode);
        update.setObject(7, claim.importId());
        update.setInt(8, claim.attempt());
        boolean changed = update.executeUpdate() == 1;
        connection.commit();
        return changed;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to persist the bounded import parse result", exception);
    }
  }

  private static Optional<UploadSource> lockUploadSource(
      Connection connection, UUID userId, UUID importId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT status, created_at, original_filename, declared_content_type, actual_size_bytes,
                       content_sha256, expires_at, (expires_at > clock_timestamp()) AS live
                FROM import_jobs
                WHERE id = ? AND user_id = ?
                FOR SHARE
                """)) {
      select.setObject(1, importId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new UploadSource(
                importId,
                row.getString("status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getString("original_filename"),
                row.getString("declared_content_type"),
                row.getLong("actual_size_bytes"),
                row.getBytes("content_sha256"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getBoolean("live")));
      }
    }
  }

  private static ParseState readParseState(Connection connection, UUID importId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT status, attempt, warnings::text, failure_code,
                       preview_data #>> '{metadata,title}' AS preview_title,
                       preview_data #> '{metadata,creators}' ->> 0 AS preview_author,
                       COALESCE(jsonb_array_length(preview_data -> 'chapters'), 0) AS chapter_count
                FROM import_parse_jobs WHERE import_id = ? FOR UPDATE
                """)) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SQLException("Queued parser job disappeared");
        return new ParseState(
            row.getString("status"),
            row.getInt("attempt"),
            row.getString("warnings"),
            row.getString("failure_code"),
            previewSummary(
                row.getString("preview_title"),
                row.getString("preview_author"),
                row.getInt("chapter_count")));
      }
    }
  }

  private static PreviewSummary previewSummary(String title, String author, int chapterCount) {
    return title == null
        ? null
        : new PreviewSummary(
            truncate(title, STATUS_TITLE_LIMIT),
            truncate(author, STATUS_AUTHOR_LIMIT),
            chapterCount);
  }

  private static String truncate(String value, int maxCodePoints) {
    if (value == null || value.codePointCount(0, value.length()) <= maxCodePoints) return value;
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
  }

  private static ImportStatus toStatus(UploadSource source, ParseState parseState) {
    return new ImportStatus(
        source.importId(),
        parseState.status(),
        source.createdAt(),
        source.expiresAt(),
        source.live(),
        parseState.warningsJson(),
        parseState.failureCode(),
        parseState.previewSummary());
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  public record UploadSource(
      UUID importId,
      String status,
      Instant createdAt,
      String filename,
      String contentType,
      long sizeBytes,
      byte[] sha256,
      Instant expiresAt,
      boolean live) {}

  public record ParseClaim(
      UUID importId,
      int attempt,
      String parserVersion,
      String filename,
      String contentType,
      long sizeBytes,
      byte[] sha256) {}

  public record PreviewSummary(String title, String author, int chapterCount) {}

  public enum PreviewLookupOutcome {
    READY,
    NOT_FOUND,
    NOT_READY,
    EXPIRED
  }

  public record PreviewRecord(String previewJson, byte[] etag, int version, String warningsJson) {}

  public record PreviewLookup(
      PreviewLookupOutcome outcome, PreviewRecord preview, String parseStatus) {
    static PreviewLookup ready(PreviewRecord preview) {
      return new PreviewLookup(PreviewLookupOutcome.READY, preview, "ready_to_commit");
    }

    static PreviewLookup notFound() {
      return new PreviewLookup(PreviewLookupOutcome.NOT_FOUND, null, null);
    }

    static PreviewLookup expired() {
      return new PreviewLookup(PreviewLookupOutcome.EXPIRED, null, null);
    }

    static PreviewLookup notReady(String status) {
      return new PreviewLookup(PreviewLookupOutcome.NOT_READY, null, status);
    }
  }

  public enum PreviewUpdateOutcome {
    UPDATED,
    NOT_FOUND,
    NOT_READY,
    EXPIRED,
    STALE
  }

  public record PreviewUpdateResult(
      PreviewUpdateOutcome outcome, PreviewRecord preview, String parseStatus) {
    static PreviewUpdateResult updated(PreviewRecord preview) {
      return new PreviewUpdateResult(PreviewUpdateOutcome.UPDATED, preview, "ready_to_commit");
    }

    static PreviewUpdateResult notFound() {
      return new PreviewUpdateResult(PreviewUpdateOutcome.NOT_FOUND, null, null);
    }

    static PreviewUpdateResult expired() {
      return new PreviewUpdateResult(PreviewUpdateOutcome.EXPIRED, null, null);
    }

    static PreviewUpdateResult notReady(String status) {
      return new PreviewUpdateResult(PreviewUpdateOutcome.NOT_READY, null, status);
    }

    static PreviewUpdateResult stale(PreviewRecord preview) {
      return new PreviewUpdateResult(PreviewUpdateOutcome.STALE, preview, "ready_to_commit");
    }
  }

  public record ImportStatus(
      UUID id,
      String status,
      Instant createdAt,
      Instant expiresAt,
      boolean live,
      String warningsJson,
      String failureCode,
      PreviewSummary previewSummary) {}

  public record StatusResult(boolean found, ImportStatus status) {
    static StatusResult notFound() {
      return new StatusResult(false, null);
    }

    static StatusResult found(ImportStatus status) {
      return new StatusResult(true, status);
    }
  }

  public record QueueResult(QueueOutcome outcome, ImportStatus status, String uploadStatus) {
    static QueueResult accepted(ImportStatus status) {
      return new QueueResult(QueueOutcome.ACCEPTED, status, null);
    }

    static QueueResult notFound() {
      return new QueueResult(QueueOutcome.NOT_FOUND, null, null);
    }

    static QueueResult expired() {
      return new QueueResult(QueueOutcome.EXPIRED, null, null);
    }

    static QueueResult notUploaded(String uploadStatus) {
      return new QueueResult(QueueOutcome.NOT_UPLOADED, null, uploadStatus);
    }
  }

  public enum QueueOutcome {
    ACCEPTED,
    NOT_FOUND,
    EXPIRED,
    NOT_UPLOADED
  }

  private record ParseState(
      String status,
      int attempt,
      String warningsJson,
      String failureCode,
      PreviewSummary previewSummary) {}
}
