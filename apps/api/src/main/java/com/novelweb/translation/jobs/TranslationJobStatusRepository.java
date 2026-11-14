package com.novelweb.translation.jobs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Reads identifier and progress metadata for jobs visible to the current library actor. */
@Repository
@Profile("postgres")
public class TranslationJobStatusRepository {

  private final DataSource dataSource;

  public TranslationJobStatusRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public Optional<JobStatus> findVisible(UUID actorUserId, UUID jobId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT job.id, job.work_id, job.chapter_id, job.source_revision_id,
                           (chapter.status = 'ready' AND
                            job.source_revision_id IS NOT DISTINCT FROM chapter.source_revision_id)
                             AS source_revision_current,
                           work.title AS work_title, chapter.title AS chapter_title,
                           chapter.ordinal AS chapter_ordinal,
                           can_access_library(library.id, actor.id, TRUE) AS can_manage,
                           job.source_language, job.target_language, job.provider_id, job.status,
                           job.total_units, job.completed_units, job.failed_units, job.failure_code,
                           job.created_at, job.updated_at, job.started_at, job.completed_at,
                           job.cancel_requested_at,
                           count(unit.id) FILTER (WHERE unit.status = 'queued')::integer AS queued_units,
                           count(unit.id) FILTER (WHERE unit.status = 'running')::integer AS running_units,
                           count(unit.id) FILTER (WHERE unit.status = 'retry_wait')::integer AS retry_wait_units,
                           count(unit.id) FILTER (WHERE unit.status = 'cancelled')::integer AS cancelled_units,
                           candidate.id AS candidate_id, candidate.status AS candidate_status
                    FROM translation_jobs job
                    JOIN works work ON work.id = job.work_id
                    JOIN libraries library ON library.id = work.library_id
                    JOIN chapters chapter ON chapter.id = job.chapter_id
                    JOIN users actor ON actor.id = ? AND actor.status = 'active'
                    LEFT JOIN translation_units unit ON unit.job_id = job.id
                    LEFT JOIN LATERAL (
                        SELECT id, status FROM translation_candidates
                        WHERE job_id = job.id ORDER BY candidate_no DESC LIMIT 1
                    ) candidate ON TRUE
                    WHERE job.id = ? AND work.deleted_at IS NULL AND library.deleted_at IS NULL
                      AND can_access_library(library.id, actor.id, FALSE)
                    GROUP BY job.id, candidate.id, candidate.status, work.title, chapter.title,
                             chapter.ordinal, chapter.status, chapter.source_revision_id,
                             library.id, actor.id
                    """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, jobId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new JobStatus(
                row.getObject("id", UUID.class),
                row.getObject("work_id", UUID.class),
                row.getObject("chapter_id", UUID.class),
                row.getObject("source_revision_id", UUID.class),
                row.getBoolean("source_revision_current"),
                row.getString("work_title"),
                row.getString("chapter_title"),
                row.getInt("chapter_ordinal"),
                row.getBoolean("can_manage"),
                row.getString("source_language"),
                row.getString("target_language"),
                row.getString("provider_id"),
                row.getString("status"),
                row.getInt("total_units"),
                row.getInt("completed_units"),
                row.getInt("failed_units"),
                row.getInt("queued_units"),
                row.getInt("running_units"),
                row.getInt("retry_wait_units"),
                row.getInt("cancelled_units"),
                row.getString("failure_code"),
                row.getObject("candidate_id", UUID.class),
                row.getString("candidate_status"),
                instant(row, "created_at"),
                instant(row, "updated_at"),
                instant(row, "started_at"),
                instant(row, "completed_at"),
                instant(row, "cancel_requested_at")));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read translation job status", exception);
    }
  }

  private static java.time.Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public record JobStatus(
      UUID id,
      UUID workId,
      UUID chapterId,
      UUID sourceRevisionId,
      boolean sourceRevisionCurrent,
      String workTitle,
      String chapterTitle,
      int chapterOrdinal,
      boolean canManage,
      String sourceLanguage,
      String targetLanguage,
      String providerId,
      String status,
      int totalUnits,
      int completedUnits,
      int failedUnits,
      int queuedUnits,
      int runningUnits,
      int retryWaitUnits,
      int cancelledUnits,
      String failureCode,
      UUID candidateId,
      String candidateStatus,
      java.time.Instant createdAt,
      java.time.Instant updatedAt,
      java.time.Instant startedAt,
      java.time.Instant completedAt,
      java.time.Instant cancelRequestedAt) {}
}
