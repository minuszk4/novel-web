package com.novelweb.library.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Creates an actor-owned, private upload job only after all policy and rights gates pass. */
@Service
@Profile("postgres")
public class ImportCreationService {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> PLAIN_TYPES = Set.of("text/plain", "application/octet-stream");
  private static final Set<String> MARKDOWN_TYPES =
      Set.of("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream");
  private static final Set<String> HTML_TYPES =
      Set.of("text/html", "application/xhtml+xml", "application/octet-stream");
  private static final Set<String> EPUB_TYPES =
      Set.of("application/epub+zip", "application/zip", "application/octet-stream");

  private final DataSource dataSource;
  private final ImportStagingProperties properties;
  private final ImportStagingFileStore fileStore;
  private final ImportContentValidator contentValidator;
  private final ImportStagingRateLimiter rateLimiter;

  public ImportCreationService(
      DataSource dataSource,
      ImportStagingProperties properties,
      ImportStagingFileStore fileStore,
      ImportContentValidator contentValidator,
      ImportStagingRateLimiter rateLimiter) {
    this.dataSource = dataSource;
    this.properties = properties;
    this.fileStore = fileStore;
    this.contentValidator = contentValidator;
    this.rateLimiter = rateLimiter;
  }

  public CreateResult create(UUID userId, CreateCommand command) {
    Objects.requireNonNull(userId, "user id is required");
    ImportStagingRateLimiter.Decision rateLimit =
        rateLimiter.tryAcquire(userId, ImportStagingRateLimiter.Action.CREATE);
    if (!rateLimit.allowed()) return CreateResult.rateLimited(rateLimit.retryAfterSeconds());
    Validation validation = validate(command, properties.maxUploadBytes());
    if (validation.outcome() != Outcome.CREATED) return CreateResult.failure(validation.outcome());
    NormalizedCommand normalized = validation.normalized();

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        lockTable(connection, "policy_documents");
        PolicyState policy = policyState(connection, userId);
        if (policy == PolicyState.NOT_CONFIGURED) {
          connection.commit();
          return CreateResult.failure(Outcome.POLICY_NOT_CONFIGURED);
        }
        if (policy == PolicyState.REQUIRED) {
          connection.commit();
          return CreateResult.failure(Outcome.POLICY_REQUIRED);
        }

        lockTable(connection, "import_rights_statements");
        Optional<String> currentVersion = currentRightsVersion(connection);
        if (currentVersion.isEmpty()) {
          connection.commit();
          return CreateResult.failure(Outcome.RIGHTS_NOT_CONFIGURED);
        }
        if (!currentVersion.orElseThrow().equals(normalized.rightsStatementVersion())) {
          connection.commit();
          return CreateResult.failure(Outcome.RIGHTS_STALE);
        }

        if (!hasWriteAccess(connection, userId, normalized.libraryId())) {
          connection.commit();
          return CreateResult.failure(Outcome.LIBRARY_NOT_FOUND);
        }

        Optional<Receipt> receipt =
            lockReceipt(connection, userId, normalized.rightsAcceptanceId());
        if (receipt.isEmpty()
            || !receipt
                .orElseThrow()
                .statementVersion()
                .equals(normalized.rightsStatementVersion())) {
          connection.commit();
          return CreateResult.failure(Outcome.RIGHTS_STALE);
        }

        Receipt value = receipt.orElseThrow();
        if (value.consumedAt() != null) {
          Optional<Job> existing =
              findJobByReceipt(connection, userId, normalized.rightsAcceptanceId());
          if (existing.isEmpty() || !matches(existing.orElseThrow(), normalized)) {
            connection.commit();
            return CreateResult.failure(Outcome.RECEIPT_ALREADY_USED);
          }
          if (!"upload_pending".equals(existing.orElseThrow().status())
              || !existing.orElseThrow().live()) {
            connection.commit();
            return CreateResult.failure(Outcome.RECEIPT_ALREADY_USED);
          }
          try {
            fileStore.verifyAvailable();
          } catch (IOException exception) {
            connection.commit();
            return CreateResult.failure(Outcome.STORAGE_NOT_CONFIGURED);
          }
          CreateResult rotated = rotateTarget(connection, existing.orElseThrow());
          connection.commit();
          return rotated;
        }

        try {
          fileStore.verifyAvailable();
        } catch (IOException exception) {
          connection.commit();
          return CreateResult.failure(Outcome.STORAGE_NOT_CONFIGURED);
        }

        if (!hasQuotaForNewJob(connection, userId, normalized.sizeBytes())) {
          connection.commit();
          return CreateResult.failure(Outcome.QUOTA_EXCEEDED);
        }

        UUID jobId = UUID.randomUUID();
        String token = newUploadToken();
        byte[] tokenHash = sha256(token.getBytes(StandardCharsets.US_ASCII));
        Optional<Job> inserted = insertJob(connection, jobId, userId, normalized, tokenHash);
        if (inserted.isEmpty()) throw new SQLException("Import job insert returned no row");

        try (PreparedStatement consume =
            connection.prepareStatement(
                """
                        UPDATE import_rights_acceptances
                        SET consumed_at = clock_timestamp()
                        WHERE id = ? AND user_id = ? AND consumed_at IS NULL
                        RETURNING consumed_at
                        """)) {
          consume.setObject(1, normalized.rightsAcceptanceId());
          consume.setObject(2, userId);
          try (ResultSet row = consume.executeQuery()) {
            if (!row.next()) {
              connection.rollback();
              return CreateResult.failure(Outcome.RIGHTS_STALE);
            }
          }
        }

        connection.commit();
        Job job = inserted.orElseThrow();
        return CreateResult.created(job, token, job.targetExpiresAt());
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to create a private import job", exception);
    }
  }

  public UploadResult upload(UUID userId, UUID jobId, String token, java.io.InputStream input) {
    ImportStagingRateLimiter.Decision rateLimit =
        rateLimiter.tryAcquire(userId, ImportStagingRateLimiter.Action.UPLOAD);
    if (!rateLimit.allowed()) return UploadResult.rateLimited(rateLimit.retryAfterSeconds());
    if (token == null || token.length() < 40 || token.length() > 128) {
      return UploadResult.failure(Outcome.UPLOAD_TARGET_NOT_FOUND);
    }
    byte[] suppliedHash = sha256(token.getBytes(StandardCharsets.US_ASCII));
    ImportStagingFileStore.StoredFile stored = null;
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<UploadJob> pending = lockPendingUpload(connection, userId, jobId);
        if (pending.isEmpty()) {
          connection.commit();
          return UploadResult.failure(Outcome.UPLOAD_TARGET_NOT_FOUND);
        }
        UploadJob job = pending.orElseThrow();
        if (job.tokenExpiresAt() == null || !job.live()) {
          expireUpload(connection, jobId);
          connection.commit();
          return UploadResult.failure(Outcome.UPLOAD_TARGET_EXPIRED);
        }
        if (!MessageDigest.isEqual(job.tokenHash(), suppliedHash)) {
          connection.commit();
          return UploadResult.failure(Outcome.UPLOAD_TARGET_NOT_FOUND);
        }

        stored = fileStore.store(jobId, input, job.declaredSizeBytes());
        contentValidator.validate(stored.path(), job.originalFilename(), job.declaredContentType());
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                        UPDATE import_jobs
                        SET status = 'uploaded', upload_token_sha256 = NULL, upload_token_expires_at = NULL,
                            uploaded_at = clock_timestamp(), actual_size_bytes = ?, content_sha256 = ?
                        WHERE id = ? AND status = 'upload_pending'
                        """)) {
          update.setLong(1, stored.sizeBytes());
          update.setBytes(2, stored.sha256());
          update.setObject(3, jobId);
          if (update.executeUpdate() != 1)
            throw new SQLException("Import job changed during upload");
        }
        connection.commit();
        return UploadResult.uploaded(stored.sizeBytes(), stored.sha256Hex());
      } catch (ImportStagingFileStore.UploadTooLargeException exception) {
        rollback(connection, exception);
        deleteStored(jobId, exception);
        return UploadResult.failure(Outcome.FILE_TOO_LARGE);
      } catch (ImportStagingFileStore.UploadSizeMismatchException exception) {
        rollback(connection, exception);
        deleteStored(jobId, exception);
        return UploadResult.failure(Outcome.FILE_SIZE_MISMATCH);
      } catch (ImportContentValidator.InvalidContentException exception) {
        rollback(connection, exception);
        deleteStored(jobId, exception);
        return UploadResult.failure(Outcome.FILE_CONTENT_INVALID);
      } catch (IOException exception) {
        rollback(connection, exception);
        if (stored != null) deleteStored(jobId, exception);
        return UploadResult.failure(Outcome.STORAGE_NOT_CONFIGURED);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        if (stored != null) deleteStored(jobId, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      if (stored != null) deleteStored(jobId, exception);
      throw new IllegalStateException("Unable to store a private import upload", exception);
    }
  }

  public static Validation validate(CreateCommand command, long maxUploadBytes) {
    if (command == null
        || !Boolean.TRUE.equals(command.rightsConfirmed())
        || command.rightsAcceptanceId() == null
        || !ImportRightsService.isValidVersion(command.rightsStatementVersion())) {
      return Validation.failure(Outcome.RIGHTS_CONFIRMATION_REQUIRED);
    }
    if (command.libraryId() == null
        || command.source() == null
        || !"upload".equals(command.source().kind())
        || command.source().filename() == null
        || command.source().filename().length() > 255
        || command.source().sizeBytes() == null
        || command.source().sizeBytes() < 1) {
      return Validation.failure(Outcome.FILE_METADATA_INVALID);
    }
    if (command.source().sizeBytes() > maxUploadBytes) {
      return Validation.failure(Outcome.FILE_TOO_LARGE);
    }

    String filename = safeDisplayFilename(command.source().filename());
    if (filename == null || filename.length() > 255)
      return Validation.failure(Outcome.FILE_METADATA_INVALID);
    String extension = extension(filename);
    if (Set.of("html", "htm").contains(extension)
        && command.source().sizeBytes() > HtmlDocumentParser.MAX_SOURCE_BYTES) {
      return Validation.failure(Outcome.FILE_TOO_LARGE);
    }
    String contentType = normalizeContentType(command.source().contentType());
    if (contentType != null && contentType.length() > 160) {
      return Validation.failure(Outcome.FILE_METADATA_INVALID);
    }
    if (!supportedType(extension, contentType))
      return Validation.failure(Outcome.FILE_METADATA_INVALID);

    return Validation.valid(
        new NormalizedCommand(
            command.libraryId(),
            command.rightsAcceptanceId(),
            command.rightsStatementVersion(),
            filename,
            contentType,
            command.source().sizeBytes()));
  }

  private static boolean supportedType(String extension, String contentType) {
    if (extension.equals("epub")) return contentType == null || EPUB_TYPES.contains(contentType);
    if (Set.of("html", "htm").contains(extension))
      return contentType == null || HTML_TYPES.contains(contentType);
    if (extension.equals("txt")) return contentType == null || PLAIN_TYPES.contains(contentType);
    if (Set.of("md", "markdown").contains(extension))
      return contentType == null || MARKDOWN_TYPES.contains(contentType);
    return false;
  }

  private static String safeDisplayFilename(String filename) {
    String trimmed = filename.trim().replace('\\', '/');
    int lastSlash = trimmed.lastIndexOf('/');
    String basename = lastSlash >= 0 ? trimmed.substring(lastSlash + 1) : trimmed;
    if (basename.isBlank()) return null;
    for (int offset = 0; offset < basename.length(); ) {
      int codePoint = basename.codePointAt(offset);
      if (Character.isISOControl(codePoint)) return null;
      offset += Character.charCount(codePoint);
    }
    return basename;
  }

  private static String extension(String filename) {
    int dot = filename.lastIndexOf('.');
    if (dot <= 0 || dot == filename.length() - 1) return "";
    return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static String normalizeContentType(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    int parameters = normalized.indexOf(';');
    if (parameters >= 0) normalized = normalized.substring(0, parameters).trim();
    return normalized;
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

  private static Optional<Receipt> lockReceipt(Connection connection, UUID userId, UUID receiptId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT statement_version, consumed_at
                FROM import_rights_acceptances
                WHERE user_id = ? AND id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setObject(2, receiptId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        OffsetDateTime consumed = row.getObject("consumed_at", OffsetDateTime.class);
        return Optional.of(
            new Receipt(
                row.getString("statement_version"),
                consumed == null ? null : consumed.toInstant()));
      }
    }
  }

  private static boolean hasWriteAccess(Connection connection, UUID userId, UUID libraryId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT owner_user_id, workspace_id
                FROM libraries l
                WHERE l.id = ? AND l.deleted_at IS NULL
                FOR SHARE OF l
                """)) {
      select.setObject(1, libraryId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return false;
        UUID workspaceId = row.getObject("workspace_id", UUID.class);
        if (workspaceId == null) return userId.equals(row.getObject("owner_user_id", UUID.class));
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
    }
  }

  private Optional<Job> insertJob(
      Connection connection, UUID jobId, UUID userId, NormalizedCommand command, byte[] tokenHash)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO import_jobs (
                    id, user_id, library_id, rights_acceptance_id, rights_statement_version,
                    original_filename, declared_content_type, declared_size_bytes, storage_key,
                    upload_token_sha256, upload_token_expires_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    LEAST(clock_timestamp() + (? * INTERVAL '1 second'),
                          clock_timestamp() + (? * INTERVAL '1 second')),
                    clock_timestamp() + (? * INTERVAL '1 second'))
                RETURNING created_at, expires_at, upload_token_expires_at
                """)) {
      int index = 1;
      insert.setObject(index++, jobId);
      insert.setObject(index++, userId);
      insert.setObject(index++, command.libraryId());
      insert.setObject(index++, command.rightsAcceptanceId());
      insert.setString(index++, command.rightsStatementVersion());
      insert.setString(index++, command.filename());
      insert.setString(index++, command.contentType());
      insert.setLong(index++, command.sizeBytes());
      insert.setString(index++, storageKey(jobId));
      insert.setBytes(index++, tokenHash);
      insert.setLong(index++, properties.uploadTargetTtl().toSeconds());
      insert.setLong(index++, properties.jobTtl().toSeconds());
      insert.setLong(index, properties.jobTtl().toSeconds());
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new Job(
                jobId,
                "upload_pending",
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getObject("upload_token_expires_at", OffsetDateTime.class).toInstant(),
                command,
                true));
      }
    }
  }

  private boolean hasQuotaForNewJob(Connection connection, UUID userId, long incomingBytes)
      throws SQLException {
    try (PreparedStatement lockUser =
        connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR UPDATE")) {
      lockUser.setObject(1, userId);
      try (ResultSet row = lockUser.executeQuery()) {
        if (!row.next())
          throw new SQLException("Import actor disappeared while reserving staging quota");
      }
    }

    try (PreparedStatement usage =
        connection.prepareStatement(
            """
                WITH database_time AS (SELECT clock_timestamp() AS now)
                SELECT
                    COUNT(*) FILTER (
                        WHERE jobs.status IN ('upload_pending', 'uploaded') AND jobs.expires_at > database_time.now
                    ) AS active_jobs,
                    COALESCE(SUM(jobs.declared_size_bytes) FILTER (
                        WHERE (jobs.status IN ('upload_pending', 'uploaded') AND jobs.expires_at > database_time.now)
                           OR (jobs.status IN ('upload_pending', 'uploaded', 'failed', 'cancelled', 'expired', 'committed')
                               AND jobs.staging_file_deleted_at IS NULL)
                    ), 0) AS reserved_bytes
                FROM import_jobs AS jobs
                CROSS JOIN database_time
                WHERE jobs.user_id = ?
                """)) {
      usage.setObject(1, userId);
      try (ResultSet row = usage.executeQuery()) {
        if (!row.next()) throw new SQLException("Import staging quota query returned no row");
        long activeJobs = row.getLong("active_jobs");
        java.math.BigDecimal reservedBytes = row.getBigDecimal("reserved_bytes");
        java.math.BigDecimal requestedBytes =
            reservedBytes.add(java.math.BigDecimal.valueOf(incomingBytes));
        return activeJobs < properties.maxActiveJobsPerUser()
            && requestedBytes.compareTo(
                    java.math.BigDecimal.valueOf(properties.maxActiveDeclaredBytesPerUser()))
                <= 0;
      }
    }
  }

  private static Optional<Job> findJobByReceipt(Connection connection, UUID userId, UUID receiptId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, status, created_at, expires_at, upload_token_expires_at,
                       (expires_at > clock_timestamp()) AS job_live,
                       library_id, rights_acceptance_id, rights_statement_version, original_filename,
                       declared_content_type, declared_size_bytes
                FROM import_jobs
                WHERE user_id = ? AND rights_acceptance_id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setObject(2, receiptId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(jobFromRow(row));
      }
    }
  }

  private static Job jobFromRow(ResultSet row) throws SQLException {
    UUID id = row.getObject("id", UUID.class);
    return new Job(
        id,
        row.getString("status"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
        row.getObject("upload_token_expires_at", OffsetDateTime.class) == null
            ? null
            : row.getObject("upload_token_expires_at", OffsetDateTime.class).toInstant(),
        new NormalizedCommand(
            row.getObject("library_id", UUID.class),
                row.getObject("rights_acceptance_id", UUID.class),
            row.getString("rights_statement_version"), row.getString("original_filename"),
            row.getString("declared_content_type"), row.getLong("declared_size_bytes")),
        row.getBoolean("job_live"));
  }

  private static boolean matches(Job existing, NormalizedCommand command) {
    NormalizedCommand prior = existing.command();
    return prior.libraryId().equals(command.libraryId())
        && prior.filename().equals(command.filename())
        && Objects.equals(prior.contentType(), command.contentType())
        && prior.sizeBytes() == command.sizeBytes()
        && prior.rightsStatementVersion().equals(command.rightsStatementVersion());
  }

  private CreateResult rotateTarget(Connection connection, Job job) throws SQLException {
    String token = newUploadToken();
    byte[] tokenHash = sha256(token.getBytes(StandardCharsets.US_ASCII));
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE import_jobs
                SET upload_token_sha256 = ?,
                    upload_token_expires_at = LEAST(clock_timestamp() + (? * INTERVAL '1 second'), expires_at)
                WHERE id = ? AND status = 'upload_pending'
                RETURNING upload_token_expires_at
                """)) {
      update.setBytes(1, tokenHash);
      update.setLong(2, properties.uploadTargetTtl().toSeconds());
      update.setObject(3, job.id());
      try (ResultSet row = update.executeQuery()) {
        if (!row.next()) return CreateResult.failure(Outcome.RECEIPT_ALREADY_USED);
        Instant tokenExpiresAt =
            row.getObject("upload_token_expires_at", OffsetDateTime.class).toInstant();
        return CreateResult.created(job, token, tokenExpiresAt);
      }
    }
  }

  private Optional<UploadJob> lockPendingUpload(Connection connection, UUID userId, UUID jobId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT upload_token_sha256, upload_token_expires_at, declared_size_bytes,
                       original_filename, declared_content_type,
                       (upload_token_expires_at > clock_timestamp()) AS token_live
                FROM import_jobs
                WHERE id = ? AND user_id = ? AND status = 'upload_pending'
                FOR UPDATE
                """)) {
      select.setObject(1, jobId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new UploadJob(
                row.getBytes("upload_token_sha256"),
                row.getObject("upload_token_expires_at", OffsetDateTime.class).toInstant(),
                row.getLong("declared_size_bytes"),
                row.getString("original_filename"),
                row.getString("declared_content_type"),
                row.getBoolean("token_live")));
      }
    }
  }

  private static void expireUpload(Connection connection, UUID jobId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE import_jobs
                SET status = 'expired', upload_token_sha256 = NULL, upload_token_expires_at = NULL
                WHERE id = ? AND status = 'upload_pending'
                """)) {
      update.setObject(1, jobId);
      update.executeUpdate();
    }
  }

  private static void lockTable(Connection connection, String table) throws SQLException {
    if (!Set.of("policy_documents", "import_rights_statements").contains(table)) {
      throw new IllegalArgumentException("Unsupported import gate lock table");
    }
    try (PreparedStatement lock =
        connection.prepareStatement("LOCK TABLE " + table + " IN SHARE MODE")) {
      lock.execute();
    }
  }

  private static String storageKey(UUID id) {
    return "staging/" + id + ".upload";
  }

  private static String newUploadToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private void deleteStored(UUID jobId, Throwable original) {
    try {
      fileStore.delete(jobId);
    } catch (IOException exception) {
      original.addSuppressed(exception);
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  public record CreateCommand(
      UUID libraryId,
      UUID rightsAcceptanceId,
      String rightsStatementVersion,
      Boolean rightsConfirmed,
      SourceDescriptor source) {}

  public record SourceDescriptor(
      String kind, String filename, String contentType, Long sizeBytes) {}

  public record NormalizedCommand(
      UUID libraryId,
      UUID rightsAcceptanceId,
      String rightsStatementVersion,
      String filename,
      String contentType,
      long sizeBytes) {}

  public record Validation(Outcome outcome, NormalizedCommand normalized) {
    static Validation valid(NormalizedCommand command) {
      return new Validation(Outcome.CREATED, command);
    }

    static Validation failure(Outcome outcome) {
      return new Validation(outcome, null);
    }
  }

  public record Job(
      UUID id,
      String status,
      Instant createdAt,
      Instant expiresAt,
      Instant targetExpiresAt,
      NormalizedCommand command,
      boolean live) {}

  public record CreateResult(
      Outcome outcome,
      Job job,
      String uploadToken,
      Instant targetExpiresAt,
      int retryAfterSeconds) {
    static CreateResult created(Job job, String token, Instant tokenExpiresAt) {
      return new CreateResult(Outcome.CREATED, job, token, tokenExpiresAt, 0);
    }

    static CreateResult failure(Outcome outcome) {
      return new CreateResult(outcome, null, null, null, 0);
    }

    static CreateResult rateLimited(int retryAfterSeconds) {
      return new CreateResult(Outcome.RATE_LIMITED, null, null, null, retryAfterSeconds);
    }
  }

  public record UploadResult(
      Outcome outcome, long sizeBytes, String sha256, int retryAfterSeconds) {
    static UploadResult uploaded(long sizeBytes, String sha256) {
      return new UploadResult(Outcome.UPLOADED, sizeBytes, sha256, 0);
    }

    static UploadResult failure(Outcome outcome) {
      return new UploadResult(outcome, 0, null, 0);
    }

    static UploadResult rateLimited(int retryAfterSeconds) {
      return new UploadResult(Outcome.RATE_LIMITED, 0, null, retryAfterSeconds);
    }
  }

  public enum Outcome {
    CREATED,
    UPLOADED,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    RIGHTS_NOT_CONFIGURED,
    RIGHTS_CONFIRMATION_REQUIRED,
    RIGHTS_STALE,
    RECEIPT_ALREADY_USED,
    LIBRARY_NOT_FOUND,
    FILE_METADATA_INVALID,
    STORAGE_NOT_CONFIGURED,
    UPLOAD_TARGET_NOT_FOUND,
    UPLOAD_TARGET_EXPIRED,
    FILE_TOO_LARGE,
    FILE_SIZE_MISMATCH,
    FILE_CONTENT_INVALID
  }

  private enum PolicyState {
    COMPLETE,
    REQUIRED,
    NOT_CONFIGURED
  }

  private record Receipt(String statementVersion, Instant consumedAt) {}

  private record UploadJob(
      byte[] tokenHash,
      Instant tokenExpiresAt,
      long declaredSizeBytes,
      String originalFilename,
      String declaredContentType,
      boolean live) {}
}
