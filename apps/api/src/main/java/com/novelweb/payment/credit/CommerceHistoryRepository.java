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

/** Reads actor-owned payment-order and chapter-entitlement history. */
@Repository
@Profile("postgres")
public class CommerceHistoryRepository {

  private final DataSource dataSource;

  public CommerceHistoryRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public Page<PaymentOrder> findPaymentOrders(
      UUID actorId, String state, CommerceHistoryCursorCodec.Position cursor, int limit) {
    Objects.requireNonNull(actorId, "actor id is required");
    if (limit < 1 || limit > 51) throw new IllegalArgumentException("invalid history limit");
    String stateClause = state == null ? "" : " AND o.status = ? ";
    String cursorClause = cursor == null ? "" : " AND (o.created_at, o.id) < (?, ?) ";
    String sql =
        """
            WITH actor AS (
                SELECT id FROM users WHERE id = ? AND status = 'active'
            ), page AS (
                SELECT o.id, o.pack_id, o.pack_version, o.provider, o.environment, o.currency,
                       o.amount_vnd, o.paid_credits, o.bonus_credits,
                       o.paid_credit_expires_at, o.bonus_credit_expires_at, o.status,
                       o.created_at, o.updated_at,
                       COALESCE(grant_totals.paid_credits, 0)::BIGINT AS paid_credits_granted,
                       COALESCE(grant_totals.bonus_credits, 0)::BIGINT AS bonus_credits_granted
                FROM credit_top_up_orders o
                JOIN actor a ON a.id = o.user_id
                LEFT JOIN LATERAL (
                    SELECT SUM(entry.amount_delta) FILTER (WHERE entry.bucket = 'PAID') AS paid_credits,
                           SUM(entry.amount_delta) FILTER (WHERE entry.bucket = 'BONUS') AS bonus_credits
                    FROM credit_ledger_entries entry
                    WHERE entry.user_id = o.user_id
                      AND entry.source_type = 'PAYMENT_ORDER'
                      AND entry.source_id = o.id
                      AND entry.entry_type IN ('TOP_UP', 'BONUS_GRANT')
                ) grant_totals ON TRUE
                WHERE TRUE
            """
            + stateClause
            + cursorClause
            + """
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT ?
            )
            SELECT actor.id IS NOT NULL AS account_active, page.*
            FROM (SELECT 1) AS seed
            LEFT JOIN actor ON TRUE
            LEFT JOIN page ON TRUE
            ORDER BY page.created_at DESC NULLS LAST, page.id DESC NULLS LAST
            """;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(sql)) {
      int parameter = 1;
      select.setObject(parameter++, actorId);
      if (state != null) select.setString(parameter++, state);
      if (cursor != null) {
        select.setObject(parameter++, cursor.createdAt().atOffset(ZoneOffset.UTC));
        select.setObject(parameter++, cursor.itemId());
      }
      select.setInt(parameter, limit);
      try (ResultSet rows = select.executeQuery()) {
        boolean active = false;
        List<PaymentOrder> items = new ArrayList<>();
        while (rows.next()) {
          active = rows.getBoolean("account_active");
          if (rows.getObject("id", UUID.class) != null) items.add(paymentOrder(rows));
        }
        return new Page<>(active, items);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read payment-order history", exception);
    }
  }

  public Page<ChapterPurchase> findChapterPurchases(
      UUID actorId, CommerceHistoryCursorCodec.Position cursor, int limit) {
    Objects.requireNonNull(actorId, "actor id is required");
    if (limit < 1 || limit > 51) throw new IllegalArgumentException("invalid history limit");
    String cursorClause = cursor == null ? "" : " AND (e.granted_at, e.id) < (?, ?) ";
    String sql =
        """
            WITH actor AS (
                SELECT id FROM users WHERE id = ? AND status = 'active'
            ), page AS (
                SELECT e.id, e.chapter_id, w.id AS work_id, w.title AS work_title,
                       c.title AS chapter_title, e.price_version, e.credits_spent, e.granted_at,
                       CASE
                           WHEN w.deleted_at IS NULL AND c.deleted_at IS NULL
                                AND c.status = 'ready' AND c.source_revision_id IS NOT NULL
                                AND (can_access_library(w.library_id, actor.id, FALSE)
                                     OR (w.visibility = 'public'
                                         AND publication_scope_access_is_current(
                                             w.id, 'PUBLIC_METADATA')
                                         AND publication_scope_access_is_current(
                                             w.id, 'CHAPTER_TEXT')))
                             THEN 'READABLE'
                           ELSE 'UNAVAILABLE'
                       END AS current_read_access
                FROM content_entitlements e
                JOIN actor ON actor.id = e.user_id
                JOIN chapters c ON c.id = e.chapter_id
                JOIN works w ON w.id = c.work_id
                WHERE TRUE
            """
            + cursorClause
            + """
                ORDER BY e.granted_at DESC, e.id DESC
                LIMIT ?
            )
            SELECT actor.id IS NOT NULL AS account_active, page.*
            FROM (SELECT 1) AS seed
            LEFT JOIN actor ON TRUE
            LEFT JOIN page ON TRUE
            ORDER BY page.granted_at DESC NULLS LAST, page.id DESC NULLS LAST
            """;
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(sql)) {
      int parameter = 1;
      select.setObject(parameter++, actorId);
      if (cursor != null) {
        select.setObject(parameter++, cursor.createdAt().atOffset(ZoneOffset.UTC));
        select.setObject(parameter++, cursor.itemId());
      }
      select.setInt(parameter, limit);
      try (ResultSet rows = select.executeQuery()) {
        boolean active = false;
        List<ChapterPurchase> items = new ArrayList<>();
        while (rows.next()) {
          active = rows.getBoolean("account_active");
          if (rows.getObject("id", UUID.class) != null) items.add(chapterPurchase(rows));
        }
        return new Page<>(active, items);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read chapter-purchase history", exception);
    }
  }

  private static PaymentOrder paymentOrder(ResultSet row) throws SQLException {
    return new PaymentOrder(
        row.getObject("id", UUID.class),
        row.getString("pack_id"),
        row.getInt("pack_version"),
        row.getString("provider"),
        row.getString("environment"),
        row.getString("currency"),
        BigInteger.valueOf(row.getLong("amount_vnd")),
        BigInteger.valueOf(row.getLong("paid_credits")),
        BigInteger.valueOf(row.getLong("bonus_credits")),
        nullableInstant(row, "paid_credit_expires_at"),
        nullableInstant(row, "bonus_credit_expires_at"),
        BigInteger.valueOf(row.getLong("paid_credits_granted")),
        BigInteger.valueOf(row.getLong("bonus_credits_granted")),
        row.getString("status"),
        instant(row, "created_at"),
        instant(row, "updated_at"));
  }

  private static ChapterPurchase chapterPurchase(ResultSet row) throws SQLException {
    return new ChapterPurchase(
        row.getObject("id", UUID.class),
        row.getObject("chapter_id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getString("work_title"),
        row.getString("chapter_title"),
        "GRANTED",
        row.getInt("price_version"),
        BigInteger.valueOf(row.getLong("credits_spent")),
        instant(row, "granted_at"),
        row.getString("current_read_access"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value.toInstant();
  }

  private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public record PaymentOrder(
      UUID id,
      String packId,
      int packVersion,
      String provider,
      String environment,
      String currency,
      BigInteger amountVnd,
      BigInteger paidCreditsPurchased,
      BigInteger bonusCreditsPurchased,
      Instant paidExpiresAt,
      Instant bonusExpiresAt,
      BigInteger paidCreditsGranted,
      BigInteger bonusCreditsGranted,
      String status,
      Instant createdAt,
      Instant updatedAt) {}

  public record ChapterPurchase(
      UUID entitlementId,
      UUID chapterId,
      UUID workId,
      String workTitle,
      String chapterTitle,
      String entitlementStatus,
      int priceVersion,
      BigInteger creditsSpent,
      Instant grantedAt,
      String currentReadAccess) {}

  public record Page<T>(boolean accountActive, List<T> items) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
