package com.novelweb.platform;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** PostgreSQL aggregate queries for operational queue metrics. */
@Repository
@Profile("postgres & observability")
public class PostgresQueueMetricsSnapshotReader implements QueueMetricsSnapshotReader {

  private static final String SNAPSHOT_SQL =
      """
          SELECT
            (SELECT count(*) FROM import_jobs
             WHERE status IN ('upload_pending', 'uploaded')
               AND expires_at > clock_timestamp()) AS active_imports,
            (SELECT count(*) FROM import_parse_jobs WHERE status = 'queued') AS queued_import_parses,
            (SELECT count(*) FROM import_parse_jobs WHERE status = 'parsing') AS running_import_parses,
            (SELECT COALESCE(EXTRACT(EPOCH FROM
                 clock_timestamp() - min(created_at))::bigint, 0)
             FROM import_parse_jobs WHERE status = 'queued') AS oldest_queued_import_parse_seconds,
            (SELECT count(*) FROM translation_jobs
             WHERE status IN ('queued', 'preparing', 'retry_wait')) AS pending_translation_jobs,
            (SELECT count(*) FROM translation_jobs
             WHERE status IN ('translating', 'validating')) AS running_translation_jobs,
            (SELECT COALESCE(EXTRACT(EPOCH FROM
                 clock_timestamp() - min(created_at))::bigint, 0)
             FROM translation_jobs WHERE status IN ('queued', 'preparing', 'retry_wait'))
                 AS oldest_pending_translation_seconds,
            (SELECT count(*) FROM credit_top_up_orders
             WHERE status IN ('PENDING', 'UNKNOWN', 'INITIATING')) AS unresolved_momo_orders,
            (SELECT count(*) FROM credit_top_up_orders
             WHERE status IN ('PENDING', 'UNKNOWN', 'INITIATING')
               AND (status <> 'INITIATING'
                    OR updated_at <= clock_timestamp() - INTERVAL '120 seconds')
               AND reconciliation_review_required_at IS NULL
               AND reconciliation_attempt_count < 8
               AND (reconciliation_lease_until IS NULL
                    OR reconciliation_lease_until <= clock_timestamp())
               AND (next_reconciliation_at IS NULL
                    OR next_reconciliation_at <= clock_timestamp())) AS due_momo_orders,
            (SELECT count(*) FROM credit_top_up_orders
             WHERE reconciliation_review_required_at IS NOT NULL) AS review_required_momo_orders,
            (SELECT COALESCE(EXTRACT(EPOCH FROM clock_timestamp() - min(due_at))::bigint, 0)
             FROM (
               SELECT CASE WHEN status = 'INITIATING'
                           THEN updated_at + INTERVAL '120 seconds'
                           ELSE COALESCE(next_reconciliation_at, updated_at)
                      END AS due_at
               FROM credit_top_up_orders
               WHERE status IN ('PENDING', 'UNKNOWN', 'INITIATING')
                 AND (status <> 'INITIATING'
                      OR updated_at <= clock_timestamp() - INTERVAL '120 seconds')
                 AND reconciliation_review_required_at IS NULL
                 AND reconciliation_attempt_count < 8
                 AND (reconciliation_lease_until IS NULL
                      OR reconciliation_lease_until <= clock_timestamp())
                 AND (next_reconciliation_at IS NULL
                      OR next_reconciliation_at <= clock_timestamp())
             ) AS due_orders) AS oldest_due_momo_order_seconds
          """;

  private final DataSource dataSource;

  public PostgresQueueMetricsSnapshotReader(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public QueueMetricsSnapshot readSnapshot() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query = connection.prepareStatement(SNAPSHOT_SQL);
        ResultSet row = query.executeQuery()) {
      if (!row.next()) throw new SQLException("Queue metrics query returned no row");
      return new QueueMetricsSnapshot(
          row.getLong("active_imports"),
          row.getLong("queued_import_parses"),
          row.getLong("running_import_parses"),
          row.getLong("oldest_queued_import_parse_seconds"),
          row.getLong("pending_translation_jobs"),
          row.getLong("running_translation_jobs"),
          row.getLong("oldest_pending_translation_seconds"),
          row.getLong("unresolved_momo_orders"),
          row.getLong("due_momo_orders"),
          row.getLong("review_required_momo_orders"),
          row.getLong("oldest_due_momo_order_seconds"));
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read operational queue metrics", exception);
    }
  }
}
