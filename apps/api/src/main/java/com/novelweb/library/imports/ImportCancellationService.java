package com.novelweb.library.imports;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Cancels an owned staging import and leaves physical cleanup to the bounded janitor. */
@Service
@Profile("postgres")
public class ImportCancellationService {

  private final DataSource dataSource;

  public ImportCancellationService(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public CancelResult cancel(UUID userId, UUID importId) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        JobState job = lockJob(connection, userId, importId);
        if (job == null) return finish(connection, CancelResult.NOT_FOUND);
        if ("cancelled".equals(job.status())) return finish(connection, CancelResult.CANCELLED);
        if (!job.live() || !isCancelable(job.status())) {
          return finish(connection, CancelResult.NOT_CANCELLABLE);
        }

        ParseState parse = lockParseJob(connection, importId);
        int updated = markCancelled(connection, userId, importId);
        if (updated != 1) {
          connection.rollback();
          return CancelResult.NOT_CANCELLABLE;
        }
        if (parse != null && !parse.hasActiveParseLease()) deleteParseJob(connection, importId);
        return finish(connection, CancelResult.CANCELLED);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to cancel the owned private import", exception);
    }
  }

  private static JobState lockJob(Connection connection, UUID userId, UUID importId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT status, expires_at > clock_timestamp() AS live
                FROM import_jobs
                WHERE id = ? AND user_id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, importId);
      select.setObject(2, userId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? new JobState(row.getString("status"), row.getBoolean("live")) : null;
      }
    }
  }

  private static ParseState lockParseJob(Connection connection, UUID importId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT status, lease_expires_at > clock_timestamp() AS active_lease
                FROM import_parse_jobs
                WHERE import_id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, importId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? new ParseState(row.getString("status"), row.getBoolean("active_lease"))
            : null;
      }
    }
  }

  private static int markCancelled(Connection connection, UUID userId, UUID importId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE import_jobs
                SET status = 'cancelled', upload_token_sha256 = NULL, upload_token_expires_at = NULL
                WHERE id = ? AND user_id = ? AND status IN ('upload_pending', 'uploaded')
                  AND expires_at > clock_timestamp()
                """)) {
      update.setObject(1, importId);
      update.setObject(2, userId);
      return update.executeUpdate();
    }
  }

  private static void deleteParseJob(Connection connection, UUID importId) throws SQLException {
    try (PreparedStatement delete =
        connection.prepareStatement("DELETE FROM import_parse_jobs WHERE import_id = ?")) {
      delete.setObject(1, importId);
      delete.executeUpdate();
    }
  }

  private static boolean isCancelable(String status) {
    return "upload_pending".equals(status) || "uploaded".equals(status);
  }

  private static CancelResult finish(Connection connection, CancelResult result)
      throws SQLException {
    connection.commit();
    return result;
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private record JobState(String status, boolean live) {}

  private record ParseState(String status, boolean leaseActive) {
    private boolean hasActiveParseLease() {
      return "parsing".equals(status) && leaseActive;
    }
  }

  public enum CancelResult {
    CANCELLED,
    NOT_FOUND,
    NOT_CANCELLABLE
  }
}
