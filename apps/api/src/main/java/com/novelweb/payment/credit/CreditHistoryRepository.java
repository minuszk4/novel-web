package com.novelweb.payment.credit;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Reads append-only credit ledger entries belonging to the active actor. */
@Repository
@Profile("postgres")
public class CreditHistoryRepository {

  private final DataSource dataSource;

  public CreditHistoryRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public Page findRecent(UUID actorUserId, CreditHistoryCursorCodec.Position cursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (limit < 1 || limit > 51) throw new IllegalArgumentException("invalid history limit");
    String cursorClause = cursor == null ? "" : " AND (entry.created_at, entry.id) < (?, ?) ";
    String sql =
        """
            WITH actor AS (
                SELECT id FROM users WHERE id = ? AND status = 'active'
            ), page AS (
                SELECT entry.id, entry.entry_type, entry.bucket, entry.amount_delta,
                       entry.expires_at, entry.source_type, entry.created_at
                FROM credit_ledger_entries entry
                JOIN actor ON actor.id = entry.user_id
                WHERE TRUE
            """
            + cursorClause
            + """
                ORDER BY entry.created_at DESC, entry.id DESC
                LIMIT ?
            )
            SELECT actor.id IS NOT NULL AS account_active,
                   page.id, page.entry_type, page.bucket, page.amount_delta,
                   page.expires_at, page.source_type, page.created_at
            FROM (SELECT 1) AS seed
            LEFT JOIN actor ON TRUE
            LEFT JOIN page ON TRUE
            ORDER BY page.created_at DESC NULLS LAST, page.id DESC NULLS LAST
            """;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(sql)) {
      int parameter = 1;
      select.setObject(parameter++, actorUserId);
      if (cursor != null) {
        select.setObject(parameter++, cursor.createdAt().atOffset(ZoneOffset.UTC));
        select.setObject(parameter++, cursor.entryId());
      }
      select.setInt(parameter, limit);
      try (ResultSet rows = select.executeQuery()) {
        boolean accountActive = false;
        List<CreditEntry> entries = new ArrayList<>();
        while (rows.next()) {
          accountActive = rows.getBoolean("account_active");
          UUID entryId = rows.getObject("id", UUID.class);
          if (entryId != null) entries.add(entry(rows));
        }
        return new Page(accountActive, entries);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read credit transaction history", exception);
    }
  }

  private static CreditEntry entry(ResultSet row) throws SQLException {
    OffsetDateTime expiresAt = row.getObject("expires_at", OffsetDateTime.class);
    OffsetDateTime createdAt = row.getObject("created_at", OffsetDateTime.class);
    return new CreditEntry(
        row.getObject("id", UUID.class),
        row.getString("entry_type"),
        row.getString("bucket"),
        BigInteger.valueOf(row.getLong("amount_delta")),
        expiresAt == null ? null : expiresAt.toInstant(),
        row.getString("source_type"),
        createdAt.toInstant());
  }

  public record CreditEntry(
      UUID id,
      String entryType,
      String bucket,
      BigInteger amountDelta,
      Instant expiresAt,
      String sourceType,
      Instant occurredAt) {}

  public record Page(boolean accountActive, List<CreditEntry> items) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
