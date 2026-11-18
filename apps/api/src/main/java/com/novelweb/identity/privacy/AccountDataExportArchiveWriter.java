package com.novelweb.identity.privacy;

import com.novelweb.identity.privacy.AccountDataExportRepository.ClaimedJob;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.util.StreamUtils;
import tools.jackson.databind.ObjectMapper;

/** Creates bounded, consistent JSONL/ZIP account snapshots from explicit actor-scoped queries. */
@Component
@Profile("postgres")
public class AccountDataExportArchiveWriter {

  private static final List<Dataset> DATASETS = datasets();
  private static final List<String> OMITTED =
      List.of(
          "Shared workspace memberships and workspace-only content; other users' records are not exported.",
          "Provider-generated translation candidates; user-authored translation edits and released translations for owned works are included.",
          "Import staging uploads and external binary assets; committed owned story/chapter text and safe asset metadata are included.",
          "Session tokens, session hashes, provider subject identifiers, CSRF keys, rate-limit state and export storage keys.");

  private final DataSource dataSource;
  private final ObjectMapper objectMapper;
  private final Path root;
  private final long maxArtifactBytes;
  private final long maxUncompressedBytes;

  public AccountDataExportArchiveWriter(
      DataSource dataSource,
      ObjectMapper objectMapper,
      @Value("${novel.account-data-export.root}") Path root,
      @Value("${novel.account-data-export.max-artifact-bytes:268435456}") long maxArtifactBytes,
      @Value("${novel.account-data-export.max-uncompressed-bytes:1073741824}")
          long maxUncompressedBytes) {
    this.dataSource = dataSource;
    this.objectMapper = objectMapper;
    this.root = root.toAbsolutePath().normalize();
    if (maxArtifactBytes < 1024 || maxArtifactBytes > 2_147_483_647L) {
      throw new IllegalArgumentException(
          "Account export byte limit is outside the supported range");
    }
    this.maxArtifactBytes = maxArtifactBytes;
    if (maxUncompressedBytes < 1024 || maxUncompressedBytes > 17_179_869_184L) {
      throw new IllegalArgumentException(
          "Account export uncompressed byte limit is outside the supported range");
    }
    this.maxUncompressedBytes = maxUncompressedBytes;
  }

  public Artifact create(ClaimedJob claim, UUID storageKey) throws IOException {
    Path tempFile = resolve(storageKey.toString() + ".tmp-" + UUID.randomUUID());
    Path artifactFile = resolve(storageKey + ".zip");
    try {
      Files.createDirectories(root);
      if (Files.exists(artifactFile))
        throw new IOException("Account export artifact already exists");
      MessageDigest digest = sha256();
      CountingOutputStream bounded =
          new CountingOutputStream(Files.newOutputStream(tempFile), maxArtifactBytes);
      UncompressedByteLimit uncompressed = new UncompressedByteLimit(maxUncompressedBytes);
      try (ZipOutputStream zip =
          new ZipOutputStream(
              new DigestOutputStream(new BufferedOutputStream(bounded), digest),
              StandardCharsets.UTF_8)) {
        ExportManifest manifest = writeSnapshot(zip, claim.userId(), uncompressed);
        byte[] manifestBytes = objectMapper.writeValueAsBytes(manifest);
        uncompressed.reserve(manifestBytes.length);
        writeEntry(zip, "manifest.json", manifestBytes);
      }
      try {
        Files.move(tempFile, artifactFile, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException exception) {
        Files.move(tempFile, artifactFile);
      }
      return new Artifact(storageKey, artifactFile, digest.digest(), Files.size(artifactFile));
    } catch (SizeLimitExceededException exception) {
      deleteQuietly(tempFile);
      throw exception;
    } catch (SQLException exception) {
      deleteQuietly(tempFile);
      throw new IOException("Account export snapshot could not be read", exception);
    } catch (IOException | RuntimeException exception) {
      deleteQuietly(tempFile);
      throw exception;
    }
  }

  public Path path(UUID storageKey) {
    return resolve(storageKey + ".zip");
  }

  public boolean delete(UUID storageKey) {
    Path file = path(storageKey);
    try {
      if (Files.isSymbolicLink(file)) return false;
      return !Files.exists(file) || Files.deleteIfExists(file);
    } catch (IOException exception) {
      return false;
    }
  }

  private ExportManifest writeSnapshot(
      ZipOutputStream zip, UUID userId, UncompressedByteLimit uncompressed)
      throws SQLException, IOException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        List<DatasetCount> counts = new ArrayList<>();
        for (Dataset dataset : DATASETS) {
          counts.add(writeDataset(connection, zip, dataset, userId, uncompressed));
        }
        connection.commit();
        return new ExportManifest(
            1,
            Instant.now(),
            userId,
            "Novel Web does not guarantee that a JSON export reproduces application behavior or ownership rights.",
            counts,
            OMITTED);
      } catch (SQLException | IOException | RuntimeException exception) {
        try {
          connection.rollback();
        } catch (SQLException rollbackException) {
          exception.addSuppressed(rollbackException);
        }
        throw exception;
      }
    }
  }

  private static DatasetCount writeDataset(
      Connection connection,
      ZipOutputStream zip,
      Dataset dataset,
      UUID userId,
      UncompressedByteLimit uncompressed)
      throws SQLException, IOException {
    zip.putNextEntry(new ZipEntry(dataset.name() + ".jsonl"));
    long count = 0;
    try (PreparedStatement select = connection.prepareStatement(dataset.sql())) {
      for (int index = 1; index <= dataset.actorParameterCount(); index++) {
        select.setObject(index, userId);
      }
      select.setFetchSize(250);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          byte[] json = rows.getString("record_json").getBytes(StandardCharsets.UTF_8);
          uncompressed.reserve((long) json.length + 1);
          zip.write(json);
          zip.write('\n');
          count++;
        }
      }
    }
    zip.closeEntry();
    return new DatasetCount(dataset.name(), count);
  }

  private Path resolve(String fileName) {
    Path result = root.resolve(fileName).normalize();
    if (!result.startsWith(root)) throw new IllegalArgumentException("Invalid export artifact key");
    return result;
  }

  private static void writeEntry(ZipOutputStream zip, String name, byte[] content)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    StreamUtils.copy(content, zip);
    zip.closeEntry();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void deleteQuietly(Path path) {
    FileSystemUtils.deleteRecursively(path.toFile());
  }

  private static List<Dataset> datasets() {
    return List.of(
        dataset(
            "profile",
            """
                SELECT jsonb_build_object(
                  'id', id, 'displayName', display_name, 'contactEmail', contact_email,
                  'emailVerifiedAt', email_verified_at, 'avatarUrl', avatar_url,
                  'status', status, 'createdAt', created_at, 'updatedAt', updated_at
                )::text AS record_json
                FROM users WHERE id = ? AND status = 'active'
                """),
        dataset(
            "linked_providers",
            """
                SELECT jsonb_build_object(
                  'provider', provider, 'providerEmail', provider_email,
                  'providerEmailVerified', provider_email_verified,
                  'linkedAt', linked_at, 'lastSeenAt', last_seen_at
                )::text AS record_json
                FROM external_identities WHERE user_id = ? ORDER BY provider
                """),
        dataset(
            "policy_acceptances",
            """
              SELECT to_jsonb(p)::text AS record_json FROM policy_acceptances p WHERE user_id = ? ORDER BY recorded_at, id
            """),
        dataset(
            "authentication_events",
            """
                SELECT jsonb_build_object(
                  'provider', provider, 'eventType', event_type, 'result', result_code,
                  'occurredAt', occurred_at
                )::text AS record_json
                FROM auth_events WHERE user_id = ? ORDER BY occurred_at, event_id
                """),
        dataset(
            "security_roles",
            """
              SELECT jsonb_build_object('role', role_code, 'assignedAt' , assigned_at, 'revokedAt' , revoked_at, 'reasonCode' ,
              reason_code, 'revocationReasonCode' , revocation_reason_code)::text AS record_json FROM security_role_assignments WHERE
              user_id = ? ORDER BY assigned_at, id
            """),
        dataset(
            "security_role_history",
            """
              SELECT jsonb_build_object('role', role_code, 'action' , action_code, 'reasonCode' , reason_code, 'occurredAt' ,
              occurred_at)::text AS record_json FROM security_role_assignment_events WHERE target_user_id = ? ORDER BY occurred_at, id
            """),
        dataset(
            "personal_libraries",
            """
              SELECT to_jsonb(l)::text AS record_json FROM libraries l WHERE owner_user_id = ? AND workspace_id IS NULL ORDER BY created_at, id
            """),
        dataset(
            "owned_works",
            """
              SELECT (to_jsonb(w) - 'source_hash')::text AS record_json FROM works w WHERE owner_user_id = ? ORDER BY created_at, id
            """),
        dataset(
            "owned_volumes",
            """
              SELECT to_jsonb(v)::text AS record_json FROM volumes v JOIN works w ON w.id = v.work_id WHERE w.owner_user_id = ? ORDER BY
              v.work_id, v.ordinal
            """),
        dataset(
            "owned_chapters",
            """
              SELECT to_jsonb(c)::text AS record_json FROM chapters c JOIN works w ON w.id = c.work_id WHERE w.owner_user_id = ? ORDER BY
              c.work_id, c.ordinal
            """),
        dataset(
            "owned_chapter_revisions",
            """
              SELECT (to_jsonb(r) - 'content_hash' )::text AS record_json FROM chapter_revisions r JOIN chapters c ON c.id = r.chapter_id
              JOIN works w ON w.id = c.work_id WHERE w.owner_user_id = ? ORDER BY c.work_id, c.ordinal, r.revision_no
            """),
        dataset(
            "owned_chapter_segments",
            """
              SELECT (to_jsonb(s) - 'checksum' )::text AS record_json FROM segments s JOIN chapter_revisions r ON r.id =
              s.chapter_revision_id JOIN chapters c ON c.id = r.chapter_id JOIN works w ON w.id = c.work_id WHERE w.owner_user_id = ?
              ORDER BY c.work_id, c.ordinal, r.revision_no, s.ordinal
            """),
        dataset(
            "owned_assets",
            """
              SELECT (to_jsonb(a) - 'storage_key' - 'content_hash' )::text AS record_json FROM assets a WHERE a.owner_user_id = ? ORDER BY
              created_at, id
            """),
        dataset(
            "owned_tags",
            """
              SELECT to_jsonb(t)::text AS record_json FROM tags t WHERE owner_user_id = ? ORDER BY slug, id
            """),
        dataset(
            "owned_work_tags",
            """
              SELECT to_jsonb(wt)::text AS record_json FROM work_tags wt WHERE owner_user_id = ? ORDER BY work_id, tag_id
            """),
        dataset(
            "library_reading_states",
            """
              SELECT to_jsonb(s)::text AS record_json FROM library_reading_states s WHERE user_id = ? ORDER BY updated_at NULLS FIRST, work_id
            """),
        dataset(
            "personal_collections",
            """
              SELECT to_jsonb(c)::text AS record_json FROM library_collections c WHERE owner_user_id = ? ORDER BY created_at, id
            """),
        dataset(
            "personal_collection_works",
            """
              SELECT to_jsonb(cw)::text AS record_json FROM library_collection_works cw JOIN library_collections c ON c.id =
              cw.collection_id AND c.owner_user_id = cw.owner_user_id WHERE cw.owner_user_id = ? ORDER BY collection_id, work_id
            """),
        dataset(
            "reader_preferences",
            """
              SELECT to_jsonb(p)::text AS record_json FROM reader_preferences p WHERE user_id = ?
            """),
        dataset(
            "reader_progress",
            """
              SELECT to_jsonb(p)::text AS record_json FROM reader_progress p WHERE user_id = ? ORDER BY updated_at NULLS FIRST, work_id
            """),
        dataset(
            "reader_bookmarks",
            """
              SELECT to_jsonb(b)::text AS record_json FROM reader_bookmarks b WHERE user_id = ? ORDER BY created_at, id
            """),
        dataset(
            "reader_annotations",
            """
              SELECT to_jsonb(a)::text AS record_json FROM reader_annotations a WHERE user_id = ? ORDER BY created_at, id
            """),
        dataset(
            "cultivation_profiles",
            """
              SELECT to_jsonb(p)::text AS record_json FROM cultivation_profiles p WHERE user_id = ?
            """),
        dataset(
            "cultivation_daily_activity",
            """
              SELECT to_jsonb(a)::text AS record_json FROM cultivation_daily_active_time a WHERE user_id = ? ORDER BY activity_date
            """),
        dataset(
            "cultivation_daily_xp",
            """
              SELECT to_jsonb(a)::text AS record_json FROM cultivation_daily_xp_aggregates a WHERE user_id = ? ORDER BY activity_date
            """),
        dataset(
            "cultivation_xp_ledger",
            """
              SELECT to_jsonb(l)::text AS record_json FROM cultivation_xp_ledger l WHERE user_id = ? ORDER BY activity_at, id
            """),
        dataset(
            "cultivation_reading_sessions",
            """
              SELECT to_jsonb(s)::text AS record_json FROM cultivation_reading_sessions s WHERE user_id = ? ORDER BY started_at, id
            """),
        dataset(
            "cultivation_reading_session_events",
            """
              SELECT (to_jsonb(e) - 'idempotency_key' - 'request_sha256' )::text AS record_json FROM cultivation_reading_session_events e
              JOIN cultivation_reading_sessions s ON s.id = e.session_id WHERE s.user_id = ? ORDER BY e.received_at, e.sequence
            """),
        dataset(
            "owned_glossaries",
            """
              SELECT (to_jsonb(g) - 'owner_user_id')::text AS record_json FROM glossaries g WHERE g.owner_user_id = ? OR
              EXISTS (SELECT 1 FROM libraries l WHERE l.id = g.library_id AND l.owner_user_id = ? AND l.workspace_id IS
              NULL) ORDER BY g.created_at, g.id
            """,
            2),
        dataset(
            "owned_glossary_revisions",
            """
              SELECT to_jsonb(r)::text AS record_json FROM glossary_revisions r JOIN glossaries g ON g.id = r.glossary_id
              WHERE g.owner_user_id = ? OR EXISTS (SELECT 1 FROM libraries l WHERE l.id = g.library_id AND l.owner_user_id
              = ? AND l.workspace_id IS NULL) ORDER BY r.glossary_id, r.revision_no
            """,
            2),
        dataset(
            "owned_glossary_terms",
            """
              SELECT to_jsonb(t)::text AS record_json FROM glossary_terms t JOIN glossary_revisions r ON r.id =
              t.revision_id JOIN glossaries g ON g.id = r.glossary_id WHERE g.owner_user_id = ? OR EXISTS (SELECT 1 FROM
              libraries l WHERE l.id = g.library_id AND l.owner_user_id = ? AND l.workspace_id IS NULL) ORDER BY
              r.glossary_id, r.revision_no, t.ordinal
            """,
            2),
        dataset(
            "owned_glossary_aliases",
            """
              SELECT to_jsonb(a)::text AS record_json FROM glossary_aliases a JOIN glossary_revisions r ON r.id =
              a.revision_id JOIN glossaries g ON g.id = r.glossary_id WHERE g.owner_user_id = ? OR EXISTS (SELECT 1 FROM
              libraries l WHERE l.id = g.library_id AND l.owner_user_id = ? AND l.workspace_id IS NULL) ORDER BY
              r.glossary_id, r.revision_no, a.ordinal
            """,
            2),
        dataset(
            "work_subscriptions",
            """
              SELECT to_jsonb(s)::text AS record_json FROM work_subscriptions s WHERE actor_user_id = ? ORDER BY created_at, id
            """),
        dataset(
            "notifications",
            """
              SELECT to_jsonb(n)::text AS record_json FROM notifications n WHERE actor_user_id = ? ORDER BY created_at, id
            """),
        dataset(
            "translation_jobs",
            """
              SELECT (to_jsonb(j) - 'profile_snapshot' - 'glossary_snapshot' - 'source_sha256' - 'request_sha256')::text
              AS record_json FROM translation_jobs j JOIN works w ON w.id = j.work_id WHERE j.requested_by_user_id = ? AND
              w.owner_user_id = ? ORDER BY j.created_at, j.id
            """,
            2),
        dataset(
            "authored_translation_edits",
            """
              SELECT (to_jsonb(e) - 'translated_sha256' - 'idempotency_key_sha256' - 'request_sha256')::text AS
              record_json FROM translation_candidate_segment_edits e JOIN translation_candidates c ON c.id =
              e.candidate_id JOIN translation_jobs j ON j.id = c.job_id JOIN works w ON w.id = j.work_id WHERE
              e.editor_user_id = ? AND j.requested_by_user_id = ? AND w.owner_user_id = ? ORDER BY e.created_at,
              e.candidate_id, e.source_segment_id, e.edit_revision
            """,
            3),
        dataset(
            "released_translation_versions",
            """
              SELECT to_jsonb(v)::text AS record_json FROM translation_versions v JOIN works w ON w.id = v.work_id WHERE w.owner_user_id =
              ? ORDER BY v.created_at, v.id
            """),
        dataset(
            "released_translation_segments",
            """
              SELECT (to_jsonb(s) - 'translated_sha256' )::text AS record_json FROM translation_version_segments s JOIN
              translation_versions v ON v.id = s.translation_version_id JOIN works w ON w.id = v.work_id WHERE w.owner_user_id = ? ORDER
              BY v.id, s.segment_ordinal
            """),
        dataset(
            "credit_top_up_orders_TEST",
            """
              SELECT (to_jsonb(o) - 'idempotency_key' - 'request_id' )::text AS record_json FROM credit_top_up_orders o WHERE user_id = ?
              AND environment = 'TEST' ORDER BY created_at, id
            """),
        dataset(
            "momo_test_settlement_events",
            """
              SELECT jsonb_build_object('orderId', e.credit_top_up_order_id, 'transactionId' , e.provider_transaction_id, 'amountVnd' ,
              e.amount_vnd, 'resultCode' , e.result_code, 'outcome' , e.processing_outcome, 'receivedAt' , e.received_at, 'environment' ,
              'TEST' )::text AS record_json FROM momo_test_ipn_events e JOIN credit_top_up_orders o ON o.id = e.credit_top_up_order_id
              WHERE o.user_id = ? ORDER BY e.received_at, e.event_hash
            """),
        dataset(
            "credit_ledger",
            """
              SELECT to_jsonb(l)::text AS record_json FROM credit_ledger_entries l WHERE user_id = ? ORDER BY created_at, id
            """),
        dataset(
            "credit_balance",
            """
              SELECT to_jsonb(b)::text AS record_json FROM credit_balance_projection b WHERE user_id = ? ORDER BY bucket, expires_at NULLS FIRST
            """),
        dataset(
            "content_entitlements",
            """
              SELECT to_jsonb(e)::text AS record_json FROM content_entitlements e WHERE user_id = ? ORDER BY granted_at, id
            """),
        dataset(
            "import_rights_acceptances",
            """
              SELECT (to_jsonb(a) - 'idempotency_key' - 'statement_sha256' )::text AS record_json FROM import_rights_acceptances a WHERE
              user_id = ? ORDER BY accepted_at, id
            """),
        dataset(
            "import_jobs",
            """
              SELECT jsonb_build_object('id', j.id, 'libraryId' , j.library_id, 'rightsStatementVersion' , j.rights_statement_version,
              'originalFilename' , j.original_filename, 'declaredContentType' , j.declared_content_type, 'declaredSizeBytes' ,
              j.declared_size_bytes, 'status' , j.status, 'createdAt' , j.created_at, 'rightsConfirmedAt' , j.rights_confirmed_at,
              'expiresAt' , j.expires_at, 'uploadedAt' , j.uploaded_at, 'actualSizeBytes' , j.actual_size_bytes, 'contentSha256' , CASE
              WHEN j.content_sha256 IS NULL THEN NULL ELSE encode(j.content_sha256, 'hex' ) END)::text AS record_json FROM import_jobs j
              WHERE j.user_id = ? ORDER BY j.created_at, j.id
            """),
        dataset(
            "momo_test_reconciliation_events",
            """
              SELECT jsonb_build_object('orderId', e.credit_top_up_order_id, 'attempt' , e.attempt_number, 'outcome' , e.outcome,
              'providerTransactionId' , e.provider_transaction_id, 'reviewCode' , e.review_code, 'queriedAt' , e.queried_at, 'environment'
              , 'TEST' )::text AS record_json FROM momo_test_reconciliation_events e JOIN credit_top_up_orders o ON o.id =
              e.credit_top_up_order_id WHERE o.user_id = ? ORDER BY e.queried_at, e.query_request_id
            """),
        dataset(
            "publication_rights_acceptances",
            """
              SELECT (to_jsonb(a) - 'idempotency_key' - 'policy_sha256' )::text AS record_json FROM publication_rights_policy_acceptances
              a JOIN works w ON w.id = a.work_id AND w.owner_user_id = a.user_id WHERE a.user_id = ? ORDER BY a.accepted_at, a.id
            """),
        dataset(
            "publication_requests",
            """
              SELECT jsonb_build_object('id', r.id, 'workId' , r.work_id, 'requestVersion' , r.request_version, 'supersedesRequestId' ,
              r.supersedes_request_id, 'policyVersion' , r.policy_version, 'submittedAt' , r.submitted_at)::text AS record_json FROM
              publication_requests r WHERE r.owner_user_id = ? ORDER BY r.work_id, r.request_version
            """),
        dataset(
            "publication_review_history",
            """
              SELECT jsonb_build_object('requestId', e.request_id, 'sequence' , e.event_sequence, 'action' , e.action_code, 'fromState' ,
              e.from_state, 'toState' , e.to_state, 'reasonCode' , e.reason_code, 'occurredAt' , e.created_at)::text AS record_json FROM
              publication_review_events e JOIN publication_requests r ON r.id = e.request_id WHERE r.owner_user_id = ? ORDER BY
              e.request_id, e.event_sequence
            """),
        dataset(
            "account_export_history",
            """
                SELECT jsonb_build_object(
                  'id', e.id, 'status', e.status, 'requestedAt', e.requested_at,
                  'startedAt', e.started_at, 'readyAt', e.ready_at, 'expiresAt', e.expires_at,
                  'failureCode', e.failure_code
                )::text AS record_json
                FROM account_data_exports e WHERE e.user_id = ? ORDER BY e.requested_at, e.id
                """));
  }

  private static Dataset dataset(String name, String sql) {
    return new Dataset(name, sql, 1);
  }

  private static Dataset dataset(String name, String sql, int actorParameterCount) {
    return new Dataset(name, sql, actorParameterCount);
  }

  private record Dataset(String name, String sql, int actorParameterCount) {}

  public record DatasetCount(String name, long records) {}

  public record ExportManifest(
      int schemaVersion,
      Instant generatedAt,
      UUID accountId,
      String disclaimer,
      List<DatasetCount> datasets,
      List<String> omittedAreas) {}

  public record Artifact(UUID storageKey, Path path, byte[] sha256, long size) {
    public Artifact {
      sha256 = sha256.clone();
    }

    @Override
    public byte[] sha256() {
      return sha256.clone();
    }

    public String sha256Hex() {
      return HexFormat.of().formatHex(sha256);
    }
  }

  public static final class SizeLimitExceededException extends IOException {
    private static final long serialVersionUID = 1L;

    private SizeLimitExceededException() {
      super("Account export exceeds a configured byte limit");
    }
  }

  private static final class UncompressedByteLimit {
    private final long maximum;
    private long count;

    private UncompressedByteLimit(long maximum) {
      this.maximum = maximum;
    }

    private void reserve(long requested) throws SizeLimitExceededException {
      if (requested < 0 || count + requested > maximum) {
        throw new SizeLimitExceededException();
      }
      count += requested;
    }
  }

  private static final class CountingOutputStream extends FilterOutputStream {
    private final long maximum;
    private long count;

    private CountingOutputStream(OutputStream output, long maximum) {
      super(output);
      this.maximum = maximum;
    }

    @Override
    public void write(int value) throws IOException {
      ensureCapacity(1);
      out.write(value);
      count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      ensureCapacity(length);
      out.write(bytes, offset, length);
      count += length;
    }

    private void ensureCapacity(int requested) throws SizeLimitExceededException {
      if (requested < 0 || count + requested > maximum) throw new SizeLimitExceededException();
    }
  }
}
