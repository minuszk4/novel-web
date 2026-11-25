package com.novelweb.payment.credit;

import com.novelweb.payment.credit.CreditLedgerService.Allocation;
import com.novelweb.payment.credit.CreditLedgerService.Bucket;
import com.novelweb.payment.credit.CreditLedgerService.OperationResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Atomically exchanges paid TEST credits for access to an eligible public chapter. */
@Service
@Profile("postgres")
@ConditionalOnProperty(prefix = "novel.payment.vnpay.test", name = "enabled", havingValue = "true")
public class ChapterUnlockService {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");

  private final DataSource dataSource;
  private final CreditLedgerService creditLedger;
  private final CreditPackCatalog catalog;

  public ChapterUnlockService(
      DataSource dataSource,
      CreditLedgerService creditLedger,
      @Qualifier("vnPayTestCreditPackCatalog") CreditPackCatalog catalog) {
    this.dataSource = dataSource;
    this.creditLedger = creditLedger;
    this.catalog = catalog;
  }

  public Optional<UnlockQuote> quote(UUID userId, UUID chapterId) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    try (Connection connection = dataSource.getConnection()) {
      Optional<EligibleChapter> chapter = findEligibleChapter(connection, userId, chapterId, false);
      if (chapter.isEmpty()) return Optional.empty();
      EligibleChapter value = chapter.orElseThrow();
      boolean alreadyAccessible =
          value.actorCanRead() || hasEntitlement(connection, userId, chapterId);
      return Optional.of(
          new UnlockQuote(
              value.chapterId(),
              value.workId(),
              value.chapterTitle(),
              value.workTitle(),
              catalog.eligiblePublishedChapterPriceVersion(),
              catalog.eligiblePublishedChapterUnlockCost(),
              alreadyAccessible));
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the chapter unlock quote", exception);
    }
  }

  public UnlockResult unlock(UUID userId, UUID chapterId, int priceVersion, String idempotencyKey) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      return UnlockResult.outcome(Outcome.INVALID_IDEMPOTENCY_KEY);
    }
    if (priceVersion < 1) return UnlockResult.outcome(Outcome.INVALID_PRICE_VERSION);

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        AccountState account = lockAccount(connection, userId);
        if (account != AccountState.ACTIVE) {
          connection.commit();
          return UnlockResult.outcome(account.outcome());
        }

        Optional<Entitlement> replay = findEntitlementByKey(connection, userId, idempotencyKey);
        if (replay.isPresent()) {
          Entitlement prior = replay.orElseThrow();
          connection.commit();
          return prior.chapterId().equals(chapterId)
              ? UnlockResult.entitlement(Outcome.REPLAYED, prior)
              : UnlockResult.outcome(Outcome.IDEMPOTENCY_KEY_REUSED);
        }

        Optional<EligibleChapter> eligible =
            findEligibleChapter(connection, userId, chapterId, true);
        if (eligible.isEmpty()) {
          connection.commit();
          return UnlockResult.outcome(Outcome.CHAPTER_NOT_FOUND);
        }
        EligibleChapter chapter = eligible.orElseThrow();
        if (chapter.actorCanRead()) {
          connection.commit();
          return UnlockResult.outcome(Outcome.ALREADY_ACCESSIBLE);
        }

        Optional<Entitlement> existing = findEntitlementByChapter(connection, userId, chapterId);
        if (existing.isPresent()) {
          connection.commit();
          return UnlockResult.entitlement(Outcome.ALREADY_OWNED, existing.orElseThrow());
        }

        int currentPriceVersion = catalog.eligiblePublishedChapterPriceVersion();
        if (priceVersion != currentPriceVersion) {
          connection.commit();
          return UnlockResult.outcome(Outcome.PRICE_VERSION_STALE);
        }

        long cost = catalog.eligiblePublishedChapterUnlockCost();
        UUID unlockOperationId = UUID.randomUUID();
        OperationResult debit =
            creditLedger.debitUnlock(
                connection,
                userId,
                unlockOperationId,
                cost,
                List.of(new Allocation(Bucket.PAID, cost, null)));
        if (debit.outcome() == CreditLedgerService.Outcome.INSUFFICIENT_CREDITS) {
          connection.rollback();
          return UnlockResult.outcome(Outcome.INSUFFICIENT_PAID_CREDITS);
        }
        if (debit.outcome() != CreditLedgerService.Outcome.APPLIED
            || debit.ledgerEntryIds().size() != 1) {
          throw new SQLException("Credit debit did not produce the expected unlock operation");
        }

        Entitlement entitlement =
            insertEntitlement(
                connection,
                unlockOperationId,
                userId,
                chapterId,
                currentPriceVersion,
                cost,
                idempotencyKey);
        connection.commit();
        return UnlockResult.entitlement(Outcome.CREATED, entitlement);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to unlock the chapter", exception);
    }
  }

  private static Optional<EligibleChapter> findEligibleChapter(
      Connection connection, UUID userId, UUID chapterId, boolean lock) throws SQLException {
    String query =
        """
            SELECT c.id, c.work_id, c.title AS chapter_title, w.title AS work_title,
                   can_access_library(w.library_id, ?, FALSE) AS actor_can_read
            FROM chapters c
            JOIN works w ON w.id = c.work_id
            WHERE c.id = ? AND c.status = 'ready' AND c.source_revision_id IS NOT NULL
              AND c.deleted_at IS NULL AND w.status = 'active' AND w.deleted_at IS NULL
              AND w.visibility = 'public'
              AND publication_scope_access_is_current(w.id, 'PUBLIC_METADATA')
              AND publication_scope_access_is_current(w.id, 'CHAPTER_TEXT')
              AND publication_scope_access_is_current(w.id, 'COMMERCIAL_SALE')
            """
            + (lock ? " FOR UPDATE OF c, w" : "");
    try (PreparedStatement select = connection.prepareStatement(query)) {
      select.setObject(1, userId);
      select.setObject(2, chapterId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(
                new EligibleChapter(
                    row.getObject("id", UUID.class),
                    row.getObject("work_id", UUID.class),
                    row.getString("chapter_title"),
                    row.getString("work_title"),
                    row.getBoolean("actor_can_read")))
            : Optional.empty();
      }
    }
  }

  private static boolean hasEntitlement(Connection connection, UUID userId, UUID chapterId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT 1 FROM content_entitlements WHERE user_id = ? AND chapter_id = ?")) {
      select.setObject(1, userId);
      select.setObject(2, chapterId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static Optional<Entitlement> findEntitlementByKey(
      Connection connection, UUID userId, String idempotencyKey) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, chapter_id, price_version, credits_spent, granted_at
                FROM content_entitlements
                WHERE user_id = ? AND idempotency_key = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setString(2, idempotencyKey);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readEntitlement(row)) : Optional.empty();
      }
    }
  }

  private static Optional<Entitlement> findEntitlementByChapter(
      Connection connection, UUID userId, UUID chapterId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, chapter_id, price_version, credits_spent, granted_at
                FROM content_entitlements
                WHERE user_id = ? AND chapter_id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setObject(2, chapterId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(readEntitlement(row)) : Optional.empty();
      }
    }
  }

  private static AccountState lockAccount(Connection connection, UUID userId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT status FROM users WHERE id = ? FOR UPDATE")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return AccountState.NOT_FOUND;
        return "active".equals(row.getString("status"))
            ? AccountState.ACTIVE
            : AccountState.INACTIVE;
      }
    }
  }

  private static Entitlement insertEntitlement(
      Connection connection,
      UUID entitlementId,
      UUID userId,
      UUID chapterId,
      int priceVersion,
      long creditsSpent,
      String idempotencyKey)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO content_entitlements (
                    id, user_id, chapter_id, price_version, credits_spent, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, chapter_id, price_version, credits_spent, granted_at
                """)) {
      insert.setObject(1, entitlementId);
      insert.setObject(2, userId);
      insert.setObject(3, chapterId);
      insert.setInt(4, priceVersion);
      insert.setLong(5, creditsSpent);
      insert.setString(6, idempotencyKey);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Chapter entitlement was not persisted");
        return readEntitlement(row);
      }
    }
  }

  private static Entitlement readEntitlement(ResultSet row) throws SQLException {
    OffsetDateTime grantedAt = row.getObject("granted_at", OffsetDateTime.class);
    return new Entitlement(
        row.getObject("id", UUID.class),
        row.getObject("chapter_id", UUID.class),
        row.getInt("price_version"),
        row.getLong("credits_spent"),
        grantedAt.toInstant());
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }

  public enum Outcome {
    CREATED,
    REPLAYED,
    ALREADY_OWNED,
    ALREADY_ACCESSIBLE,
    INSUFFICIENT_PAID_CREDITS,
    PRICE_VERSION_STALE,
    IDEMPOTENCY_KEY_REUSED,
    INVALID_IDEMPOTENCY_KEY,
    INVALID_PRICE_VERSION,
    CHAPTER_NOT_FOUND,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_INACTIVE
  }

  public record UnlockQuote(
      UUID chapterId,
      UUID workId,
      String chapterTitle,
      String workTitle,
      int priceVersion,
      long priceCredits,
      boolean accessAlreadyGranted) {}

  public record Entitlement(
      UUID id, UUID chapterId, int priceVersion, long creditsSpent, Instant grantedAt) {}

  public record UnlockResult(Outcome outcome, Entitlement entitlement) {
    public UnlockResult {
      Objects.requireNonNull(outcome, "unlock outcome is required");
    }

    private static UnlockResult outcome(Outcome outcome) {
      return new UnlockResult(outcome, null);
    }

    private static UnlockResult entitlement(Outcome outcome, Entitlement entitlement) {
      return new UnlockResult(outcome, Objects.requireNonNull(entitlement));
    }
  }

  private record EligibleChapter(
      UUID chapterId, UUID workId, String chapterTitle, String workTitle, boolean actorCanRead) {}

  private enum AccountState {
    ACTIVE,
    INACTIVE,
    NOT_FOUND;

    private Outcome outcome() {
      return this == NOT_FOUND ? Outcome.ACCOUNT_NOT_FOUND : Outcome.ACCOUNT_INACTIVE;
    }
  }
}
