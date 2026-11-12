package com.novelweb.library.imports;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class ImportStagingCleanupService {

  private final DataSource dataSource;
  private final ImportStagingFileStore fileStore;
  private final int batchSize;

  public ImportStagingCleanupService(
      DataSource dataSource, ImportStagingFileStore fileStore, ImportStagingProperties properties) {
    this.dataSource = dataSource;
    this.fileStore = fileStore;
    this.batchSize = properties.cleanupBatchSize();
  }

  @Scheduled(fixedDelayString = "${novel.imports.staging.cleanup-delay:1m}")
  public void scheduledCleanup() {
    cleanupExpiredStaging();
  }

  public int cleanupExpiredStaging() {
    List<UUID> expiredJobs = expireAndSelectBatch();
    for (UUID jobId : expiredJobs) {
      try {
        fileStore.delete(jobId);
      } catch (IOException exception) {
        throw new IllegalStateException(
            "Unable to remove expired import staging content", exception);
      }
      markStagingFileDeleted(jobId);
    }
    return expiredJobs.size();
  }

  private List<UUID> expireAndSelectBatch() {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        List<Candidate> candidates = lockCandidates(connection);
        for (Candidate candidate : candidates) {
          if (!candidate.status().equals("committed"))
            deleteParserResult(connection, candidate.id());
          if (candidate.status().equals("upload_pending")
              || candidate.status().equals("uploaded")) {
            expireJob(connection, candidate.id());
          }
        }
        connection.commit();
        return candidates.stream().map(Candidate::id).toList();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to select expired import staging jobs", exception);
    }
  }

  private List<Candidate> lockCandidates(Connection connection) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, status
                FROM import_jobs
                WHERE (
                    (status IN ('upload_pending', 'uploaded') AND expires_at <= clock_timestamp())
                    OR (status IN ('failed', 'cancelled', 'expired', 'committed')
                        AND staging_file_deleted_at IS NULL)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM import_parse_jobs AS active_parse
                      WHERE active_parse.import_id = import_jobs.id
                        AND active_parse.status = 'parsing'
                        AND active_parse.lease_expires_at > clock_timestamp()
                  )
                ORDER BY expires_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """)) {
      select.setInt(1, batchSize);
      try (ResultSet rows = select.executeQuery()) {
        List<Candidate> candidates = new ArrayList<>();
        while (rows.next()) {
          candidates.add(new Candidate(rows.getObject("id", UUID.class), rows.getString("status")));
        }
        return candidates;
      }
    }
  }

  private static void expireJob(Connection connection, UUID jobId) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE import_jobs
                SET status = 'expired', upload_token_sha256 = NULL, upload_token_expires_at = NULL
                WHERE id = ? AND status IN ('upload_pending', 'uploaded') AND expires_at <= clock_timestamp()
                """)) {
      update.setObject(1, jobId);
      if (update.executeUpdate() != 1) {
        throw new SQLException("An expired import job changed while it was locked");
      }
    }
  }

  private static void deleteParserResult(Connection connection, UUID jobId) throws SQLException {
    try (PreparedStatement delete =
        connection.prepareStatement("DELETE FROM import_parse_jobs WHERE import_id = ?")) {
      delete.setObject(1, jobId);
      delete.executeUpdate();
    }
  }

  private void markStagingFileDeleted(UUID jobId) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                """
                     UPDATE import_jobs
                     SET staging_file_deleted_at = clock_timestamp()
                     WHERE id = ? AND status IN ('failed', 'cancelled', 'expired', 'committed')
                       AND staging_file_deleted_at IS NULL
                     """)) {
      update.setObject(1, jobId);
      update.executeUpdate();
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to record import staging cleanup", exception);
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private record Candidate(UUID id, String status) {}
}
