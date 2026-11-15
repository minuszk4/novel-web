package com.novelweb.payment.credit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * Transaction-aware credit ledger operations. Callers own the transaction so a future payment order
 * or chapter-unlock transition can share it with the corresponding ledger write.
 */
@Repository
@Profile("postgres")
public class CreditLedgerService {

  /**
   * Grants paid credits for a verified payment order. This internal operation is not exposed over
   * HTTP and must only be called after the order service verifies final payment success.
   */
  public OperationResult grantPaidTopUp(
      Connection connection, UUID userId, UUID paymentOrderId, long credits) throws SQLException {
    requireTransaction(connection);
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(paymentOrderId, "payment order id is required");
    if (credits <= 0) throw new IllegalArgumentException("credits must be positive");

    lockSourceOperation(connection, "PAYMENT_ORDER", paymentOrderId);
    AccountState account = lockAccount(connection, userId);
    if (account != AccountState.ACTIVE) return OperationResult.outcome(account.outcome());

    List<ExistingEntry> existing = findSourceEntries(connection, "PAYMENT_ORDER", paymentOrderId);
    if (!existing.isEmpty()) {
      if (existing.size() == 1
          && existing.getFirst().userId().equals(userId)
          && existing.getFirst().entryType().equals("TOP_UP")
          && existing.getFirst().bucket() == Bucket.PAID
          && existing.getFirst().amountDelta() == credits
          && existing.getFirst().expiresAt() == null) {
        return OperationResult.replayed(List.of(existing.getFirst().entryId()), credits);
      }
      return OperationResult.outcome(Outcome.SOURCE_OPERATION_REUSED);
    }

    UUID entryId =
        insertEntry(
            connection,
            userId,
            "TOP_UP",
            Bucket.PAID,
            credits,
            null,
            "PAYMENT_ORDER",
            paymentOrderId,
            null);
    if (entryId == null) return OperationResult.outcome(Outcome.SOURCE_OPERATION_REUSED);
    return OperationResult.applied(List.of(entryId), credits);
  }

  /**
   * Debits caller-selected balance tranches for one unlock operation. The caller must apply the
   * approved paid/bonus consumption policy when constructing allocations; this method does not
   * invent that business policy.
   */
  public OperationResult debitUnlock(
      Connection connection,
      UUID userId,
      UUID unlockOperationId,
      long cost,
      List<Allocation> allocations)
      throws SQLException {
    requireTransaction(connection);
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(unlockOperationId, "unlock operation id is required");
    if (cost <= 0) throw new IllegalArgumentException("cost must be positive");
    List<Allocation> normalized = normalizeAllocations(allocations);
    long allocated = 0;
    for (Allocation allocation : normalized) {
      allocated = Math.addExact(allocated, allocation.credits());
    }
    if (allocated != cost) {
      throw new IllegalArgumentException("allocation total must equal the unlock cost");
    }

    lockSourceOperation(connection, "CHAPTER_UNLOCK", unlockOperationId);
    AccountState account = lockAccount(connection, userId);
    if (account != AccountState.ACTIVE) return OperationResult.outcome(account.outcome());

    List<ExistingEntry> existing =
        findSourceEntries(connection, "CHAPTER_UNLOCK", unlockOperationId);
    if (!existing.isEmpty()) {
      return matchesDebit(existing, userId, normalized)
          ? OperationResult.replayed(existing.stream().map(ExistingEntry::entryId).toList(), cost)
          : OperationResult.outcome(Outcome.SOURCE_OPERATION_REUSED);
    }

    for (Allocation allocation : normalized) {
      if (!hasCredits(connection, userId, allocation)) {
        return OperationResult.outcome(Outcome.INSUFFICIENT_CREDITS);
      }
    }

    List<UUID> entryIds = new ArrayList<>(normalized.size());
    for (Allocation allocation : normalized) {
      UUID entryId =
          insertEntry(
              connection,
              userId,
              "UNLOCK_DEBIT",
              allocation.bucket(),
              -allocation.credits(),
              allocation.expiresAt(),
              "CHAPTER_UNLOCK",
              unlockOperationId,
              null);
      if (entryId == null) {
        throw new SQLException("Credit source operation changed while the account was locked");
      }
      entryIds.add(entryId);
    }
    return OperationResult.applied(entryIds, -cost);
  }

  /**
   * Appends a partial or full compensating entry for one ledger row. A row can be reversed only
   * once; callers cannot reverse another account's entry or reverse a reversal.
   */
  public OperationResult reverseEntry(
      Connection connection, UUID userId, UUID originalEntryId, long creditsToReverse)
      throws SQLException {
    requireTransaction(connection);
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(originalEntryId, "ledger entry id is required");
    if (creditsToReverse <= 0) {
      throw new IllegalArgumentException("reversal amount must be positive");
    }

    AccountState account = lockAccount(connection, userId);
    if (account != AccountState.ACTIVE) return OperationResult.outcome(account.outcome());

    ExistingEntry original = findEntry(connection, userId, originalEntryId);
    if (original == null) return OperationResult.outcome(Outcome.ENTRY_NOT_FOUND);
    if (original.entryType().equals("REVERSAL")) {
      return OperationResult.outcome(Outcome.REVERSAL_NOT_ALLOWED);
    }
    if (original.amountDelta() == Long.MIN_VALUE
        || creditsToReverse > Math.abs(original.amountDelta())) {
      return OperationResult.outcome(Outcome.REVERSAL_AMOUNT_EXCEEDS_SOURCE);
    }

    ExistingEntry prior = findReversal(connection, originalEntryId);
    long reversalDelta = original.amountDelta() > 0 ? -creditsToReverse : creditsToReverse;
    if (prior != null) {
      return prior.userId().equals(userId)
              && prior.amountDelta() == reversalDelta
              && prior.bucket() == original.bucket()
              && Objects.equals(prior.expiresAt(), original.expiresAt())
          ? OperationResult.replayed(List.of(prior.entryId()), reversalDelta)
          : OperationResult.outcome(Outcome.ENTRY_ALREADY_REVERSED);
    }

    if (reversalDelta < 0
        && !hasProjectedCredits(
            connection, userId, original.bucket(), original.expiresAt(), -reversalDelta)) {
      return OperationResult.outcome(Outcome.BALANCE_CONFLICT);
    }

    UUID reversalId =
        insertEntry(
            connection,
            userId,
            "REVERSAL",
            original.bucket(),
            reversalDelta,
            original.expiresAt(),
            "REVERSAL",
            originalEntryId,
            originalEntryId);
    if (reversalId == null) return OperationResult.outcome(Outcome.ENTRY_ALREADY_REVERSED);
    return OperationResult.applied(List.of(reversalId), reversalDelta);
  }

  private static void requireTransaction(Connection connection) throws SQLException {
    Objects.requireNonNull(connection, "transaction connection is required");
    if (connection.getAutoCommit()) {
      throw new IllegalArgumentException("credit ledger operation requires an open transaction");
    }
  }

  private static void lockSourceOperation(Connection connection, String sourceType, UUID sourceId)
      throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      lock.setString(1, sourceType + ":" + sourceId);
      lock.execute();
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

  private static List<ExistingEntry> findSourceEntries(
      Connection connection, String sourceType, UUID sourceId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, entry_type, bucket, amount_delta, expires_at
                FROM credit_ledger_entries
                WHERE source_type = ? AND source_id = ?
                ORDER BY entry_type, bucket, expires_at NULLS FIRST, id
                FOR UPDATE
                """)) {
      select.setString(1, sourceType);
      select.setObject(2, sourceId);
      try (ResultSet rows = select.executeQuery()) {
        List<ExistingEntry> entries = new ArrayList<>();
        while (rows.next()) entries.add(readEntry(rows));
        return List.copyOf(entries);
      }
    }
  }

  private static ExistingEntry findEntry(Connection connection, UUID userId, UUID entryId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, entry_type, bucket, amount_delta, expires_at
                FROM credit_ledger_entries
                WHERE user_id = ? AND id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setObject(2, entryId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? readEntry(row) : null;
      }
    }
  }

  private static ExistingEntry findReversal(Connection connection, UUID originalEntryId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, entry_type, bucket, amount_delta, expires_at
                FROM credit_ledger_entries
                WHERE reversal_of_entry_id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, originalEntryId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? readEntry(row) : null;
      }
    }
  }

  private static ExistingEntry readEntry(ResultSet row) throws SQLException {
    OffsetDateTime expiresAt = row.getObject("expires_at", OffsetDateTime.class);
    return new ExistingEntry(
        row.getObject("id", UUID.class),
        row.getObject("user_id", UUID.class),
        row.getString("entry_type"),
        Bucket.valueOf(row.getString("bucket")),
        row.getLong("amount_delta"),
        expiresAt == null ? null : expiresAt.toInstant());
  }

  private static boolean matchesDebit(
      List<ExistingEntry> existing, UUID userId, List<Allocation> allocations) {
    if (existing.size() != allocations.size()) return false;
    for (int index = 0; index < allocations.size(); index++) {
      ExistingEntry entry = existing.get(index);
      Allocation allocation = allocations.get(index);
      if (!entry.userId().equals(userId)
          || !entry.entryType().equals("UNLOCK_DEBIT")
          || entry.bucket() != allocation.bucket()
          || entry.amountDelta() != -allocation.credits()
          || !Objects.equals(entry.expiresAt(), allocation.expiresAt())) return false;
    }
    return true;
  }

  private static boolean hasCredits(Connection connection, UUID userId, Allocation allocation)
      throws SQLException {
    String expiryEligibility =
        allocation.bucket() == Bucket.BONUS
            ? "AND (expires_at IS NULL OR expires_at > clock_timestamp())"
            : "";
    return hasProjectedCredits(
        connection,
        userId,
        allocation.bucket(),
        allocation.expiresAt(),
        allocation.credits(),
        expiryEligibility);
  }

  private static boolean hasProjectedCredits(
      Connection connection, UUID userId, Bucket bucket, Instant expiresAt, long requiredCredits)
      throws SQLException {
    return hasProjectedCredits(connection, userId, bucket, expiresAt, requiredCredits, "");
  }

  private static boolean hasProjectedCredits(
      Connection connection,
      UUID userId,
      Bucket bucket,
      Instant expiresAt,
      long requiredCredits,
      String extraPredicate)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT balance
                FROM credit_balance_projection
                WHERE user_id = ? AND bucket = ? AND expires_at IS NOT DISTINCT FROM ?
                """
                + extraPredicate
                + " FOR UPDATE")) {
      select.setObject(1, userId);
      select.setString(2, bucket.name());
      setInstant(select, 3, expiresAt);
      try (ResultSet row = select.executeQuery()) {
        return row.next() && row.getLong("balance") >= requiredCredits;
      }
    }
  }

  private static UUID insertEntry(
      Connection connection,
      UUID userId,
      String entryType,
      Bucket bucket,
      long amountDelta,
      Instant expiresAt,
      String sourceType,
      UUID sourceId,
      UUID reversalOfEntryId)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO credit_ledger_entries (
                    user_id, entry_type, bucket, amount_delta, expires_at,
                    source_type, source_id, reversal_of_entry_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                RETURNING id
                """)) {
      insert.setObject(1, userId);
      insert.setString(2, entryType);
      insert.setString(3, bucket.name());
      insert.setLong(4, amountDelta);
      setInstant(insert, 5, expiresAt);
      insert.setString(6, sourceType);
      insert.setObject(7, sourceId);
      insert.setObject(8, reversalOfEntryId);
      try (ResultSet row = insert.executeQuery()) {
        return row.next() ? row.getObject("id", UUID.class) : null;
      }
    }
  }

  private static void setInstant(PreparedStatement statement, int index, Instant value)
      throws SQLException {
    statement.setTimestamp(
        index, value == null ? null : Timestamp.from(value.truncatedTo(ChronoUnit.MICROS)));
  }

  private static List<Allocation> normalizeAllocations(List<Allocation> allocations) {
    Objects.requireNonNull(allocations, "allocations are required");
    if (allocations.isEmpty())
      throw new IllegalArgumentException("at least one allocation is required");
    List<Allocation> normalized = new ArrayList<>(allocations.size());
    for (Allocation allocation : allocations) {
      Objects.requireNonNull(allocation, "allocation is required");
      if (allocation.credits() <= 0) {
        throw new IllegalArgumentException("allocation credits must be positive");
      }
      Instant expiresAt = normalizeExpiry(allocation.bucket(), allocation.expiresAt());
      normalized.add(new Allocation(allocation.bucket(), allocation.credits(), expiresAt));
    }
    normalized.sort(
        Comparator.comparing((Allocation item) -> item.bucket().name())
            .thenComparing(
                Allocation::expiresAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    for (int index = 1; index < normalized.size(); index++) {
      Allocation previous = normalized.get(index - 1);
      Allocation current = normalized.get(index);
      if (previous.bucket() == current.bucket()
          && Objects.equals(previous.expiresAt(), current.expiresAt())) {
        throw new IllegalArgumentException("duplicate balance tranche allocation");
      }
    }
    return List.copyOf(normalized);
  }

  private static Instant normalizeExpiry(Bucket bucket, Instant expiresAt) {
    if (bucket == Bucket.PAID && expiresAt != null) {
      throw new IllegalArgumentException("paid credits cannot expire");
    }
    return expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.MICROS);
  }

  public enum Bucket {
    PAID,
    BONUS
  }

  public enum Outcome {
    APPLIED,
    ALREADY_APPLIED,
    INSUFFICIENT_CREDITS,
    SOURCE_OPERATION_REUSED,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_INACTIVE,
    ENTRY_NOT_FOUND,
    ENTRY_ALREADY_REVERSED,
    REVERSAL_NOT_ALLOWED,
    REVERSAL_AMOUNT_EXCEEDS_SOURCE,
    BALANCE_CONFLICT
  }

  public record Allocation(Bucket bucket, long credits, Instant expiresAt) {
    public Allocation {
      Objects.requireNonNull(bucket, "allocation bucket is required");
    }
  }

  public record OperationResult(Outcome outcome, List<UUID> ledgerEntryIds, long amountDelta) {
    public OperationResult {
      Objects.requireNonNull(outcome, "operation outcome is required");
      ledgerEntryIds = List.copyOf(ledgerEntryIds);
    }

    private static OperationResult outcome(Outcome outcome) {
      return new OperationResult(outcome, List.of(), 0);
    }

    private static OperationResult applied(List<UUID> ids, long amountDelta) {
      return new OperationResult(Outcome.APPLIED, ids, amountDelta);
    }

    private static OperationResult replayed(List<UUID> ids, long amountDelta) {
      return new OperationResult(Outcome.ALREADY_APPLIED, ids, amountDelta);
    }
  }

  private enum AccountState {
    ACTIVE,
    INACTIVE,
    NOT_FOUND;

    private Outcome outcome() {
      return switch (this) {
        case INACTIVE -> Outcome.ACCOUNT_INACTIVE;
        case NOT_FOUND -> Outcome.ACCOUNT_NOT_FOUND;
        case ACTIVE -> throw new IllegalStateException("active accounts have no error outcome");
      };
    }
  }

  private record ExistingEntry(
      UUID entryId,
      UUID userId,
      String entryType,
      Bucket bucket,
      long amountDelta,
      Instant expiresAt) {}
}
