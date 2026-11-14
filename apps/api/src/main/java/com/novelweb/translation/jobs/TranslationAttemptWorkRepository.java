package com.novelweb.translation.jobs;

import com.novelweb.translation.provider.TranslationLanguagePair;
import com.novelweb.translation.provider.TranslationRequest;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Loads a validated, immutable unit snapshot for the explicitly local fake provider. */
@Repository
@Profile("postgres")
public class TranslationAttemptWorkRepository {

  private final DataSource dataSource;
  private final TranslationChunkPlanner planner;

  public TranslationAttemptWorkRepository(DataSource dataSource, TranslationChunkPlanner planner) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.planner = Objects.requireNonNull(planner, "chunk planner is required");
  }

  public WorkSnapshot load(TranslationUnitLeaseRepository.Lease lease) {
    Objects.requireNonNull(lease, "current lease is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      connection.setAutoCommit(false);
      try {
        WorkContext context = loadWorkContext(connection, lease);
        requireCurrentLocalWork(context, lease);
        List<TranslationChunkPlanner.SourceSegment> sourceSegments =
            loadAndVerifySegments(connection, lease, context.sourceRevisionId());
        List<TranslationChunkPlanner.UnitPlan> planned =
            planner.plan(context.sourceLanguage(), context.targetLanguage(), sourceSegments);
        if (planned.size() != 1 || planned.getFirst().segments().size() != sourceSegments.size()) {
          throw new InvalidWorkSnapshotException();
        }
        List<TranslationRequest.Segment> requestSegments =
            planned.getFirst().segments().stream()
                .map(segment -> new TranslationRequest.Segment(segment.id(), segment.sourceText()))
                .toList();
        connection.commit();
        return new WorkSnapshot(
            new TranslationLanguagePair(context.sourceLanguage(), context.targetLanguage()),
            requestSegments);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load translation unit source snapshot", exception);
    }
  }

  private static WorkContext loadWorkContext(
      Connection connection, TranslationUnitLeaseRepository.Lease lease) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT job.source_revision_id, job.source_language, job.target_language,
                       job.provider_id, job.status AS job_status,
                       job.profile_snapshot->>'mode' AS profile_mode,
                       job.profile_snapshot->>'schemaVersion' AS profile_schema_version,
                       job.glossary_snapshot = '[]'::jsonb AS has_empty_glossary,
                       job.source_sha256, unit.source_revision_id AS unit_source_revision_id,
                       unit.status AS unit_status, unit.lease_owner, unit.lease_token,
                       unit.lease_until > clock_timestamp() AS lease_live,
                       chapter.status AS chapter_status, chapter.source_revision_id AS current_revision_id,
                       revision.content_hash AS current_source_sha256
                FROM translation_units unit
                JOIN translation_jobs job ON job.id = unit.job_id
                JOIN chapters chapter ON chapter.id = job.chapter_id AND chapter.work_id = job.work_id
                JOIN chapter_revisions revision
                  ON revision.chapter_id = chapter.id AND revision.id = chapter.source_revision_id
                WHERE unit.id = ? AND unit.job_id = ?
                FOR SHARE OF unit, job, chapter
                """)) {
      select.setObject(1, lease.unitId());
      select.setObject(2, lease.jobId());
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new StaleLeaseException();
        return new WorkContext(
            row.getObject("source_revision_id", UUID.class),
            row.getString("source_language"),
            row.getString("target_language"),
            row.getString("provider_id"),
            row.getString("job_status"),
            row.getString("profile_mode"),
            row.getString("profile_schema_version"),
            row.getBoolean("has_empty_glossary"),
            row.getBytes("source_sha256"),
            row.getObject("unit_source_revision_id", UUID.class),
            row.getString("unit_status"),
            row.getString("lease_owner"),
            row.getObject("lease_token", UUID.class),
            row.getBoolean("lease_live"),
            row.getString("chapter_status"),
            row.getObject("current_revision_id", UUID.class),
            row.getBytes("current_source_sha256"));
      }
    }
  }

  private static void requireCurrentLocalWork(
      WorkContext context, TranslationUnitLeaseRepository.Lease lease) {
    if (!"running".equals(context.unitStatus())
        || !lease.workerId().equals(context.leaseOwner())
        || !lease.leaseToken().equals(context.leaseToken())
        || !lease.sourceRevisionId().equals(context.unitSourceRevisionId())
        || !context.leaseLive()) {
      throw new StaleLeaseException();
    }
    if (!"translating".equals(context.jobStatus())
        || !"ready".equals(context.chapterStatus())
        || !context.sourceRevisionId().equals(context.currentRevisionId())
        || !MessageDigest.isEqual(context.sourceSha256(), context.currentSourceSha256())) {
      throw new StaleSourceException();
    }
    if (!"fake-local".equals(context.providerId())
        || !"fake-local".equals(context.profileMode())
        || !"1".equals(context.profileSchemaVersion())
        || !context.hasEmptyGlossary()) {
      throw new UnsupportedWorkSnapshotException();
    }
  }

  private static List<TranslationChunkPlanner.SourceSegment> loadAndVerifySegments(
      Connection connection, TranslationUnitLeaseRepository.Lease lease, UUID sourceRevisionId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT mapping.segment_ordinal, mapping.source_segment_id, mapping.source_sha256,
                       segment.ordinal AS source_ordinal, segment.segment_type,
                       segment.stable_anchor, segment.source_text, segment.checksum
                FROM translation_unit_segments mapping
                JOIN segments segment
                  ON segment.chapter_revision_id = mapping.source_revision_id
                 AND segment.id = mapping.source_segment_id
                WHERE mapping.unit_id = ? AND mapping.job_id = ?
                  AND mapping.source_revision_id = ?
                ORDER BY mapping.segment_ordinal
                """)) {
      select.setObject(1, lease.unitId());
      select.setObject(2, lease.jobId());
      select.setObject(3, sourceRevisionId);
      try (ResultSet rows = select.executeQuery()) {
        List<TranslationChunkPlanner.SourceSegment> segments = new ArrayList<>();
        int expectedUnitOrdinal = 1;
        while (rows.next()) {
          byte[] mappedChecksum = rows.getBytes("source_sha256");
          byte[] currentChecksum = rows.getBytes("checksum");
          if (rows.getInt("segment_ordinal") != expectedUnitOrdinal++
              || mappedChecksum == null
              || currentChecksum == null
              || !MessageDigest.isEqual(mappedChecksum, currentChecksum)) {
            throw new InvalidWorkSnapshotException();
          }
          segments.add(
              new TranslationChunkPlanner.SourceSegment(
                  rows.getObject("source_segment_id", UUID.class),
                  rows.getInt("source_ordinal"),
                  rows.getString("segment_type"),
                  rows.getString("stable_anchor"),
                  rows.getString("source_text"),
                  currentChecksum));
        }
        if (segments.isEmpty()) throw new InvalidWorkSnapshotException();
        return List.copyOf(segments);
      }
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record WorkContext(
      UUID sourceRevisionId,
      String sourceLanguage,
      String targetLanguage,
      String providerId,
      String jobStatus,
      String profileMode,
      String profileSchemaVersion,
      boolean hasEmptyGlossary,
      byte[] sourceSha256,
      UUID unitSourceRevisionId,
      String unitStatus,
      String leaseOwner,
      UUID leaseToken,
      boolean leaseLive,
      String chapterStatus,
      UUID currentRevisionId,
      byte[] currentSourceSha256) {}

  public record WorkSnapshot(
      TranslationLanguagePair languagePair, List<TranslationRequest.Segment> segments) {
    public WorkSnapshot {
      Objects.requireNonNull(languagePair, "translation languages are required");
      segments = List.copyOf(segments);
      if (segments.isEmpty()) throw new IllegalArgumentException("work snapshot is empty");
    }

    public TranslationRequest toRequest(UUID attemptId) {
      return new TranslationRequest(attemptId, languagePair, segments, List.of());
    }

    @Override
    public String toString() {
      return "WorkSnapshot[languagePair="
          + languagePair
          + ", segmentCount="
          + segments.size()
          + ", glossaryInstructionCount=0]";
    }
  }

  public static final class StaleLeaseException extends RuntimeException {}

  public static final class StaleSourceException extends RuntimeException {}

  public static final class InvalidWorkSnapshotException extends RuntimeException {}

  public static final class UnsupportedWorkSnapshotException extends RuntimeException {}
}
