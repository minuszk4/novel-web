package com.novelweb.payment.momo;

import com.novelweb.payment.credit.CreditLedgerService;
import com.novelweb.payment.credit.CreditLedgerService.OperationResult;
import com.novelweb.payment.momo.MomoQueryResponseValidator.Outcome;
import com.novelweb.payment.momo.MomoSignatureCodec.QueryOrderRequest;
import com.novelweb.payment.momo.MomoTestGatewayClient.FailureCode;
import com.novelweb.payment.momo.MomoTestGatewayClient.QueryResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded, idempotent settlement recovery for unresolved MoMo TEST top-up orders. */
public final class MomoTestOrderReconciliationService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(MomoTestOrderReconciliationService.class);
  private static final int MAX_ATTEMPTS = 8;
  private static final int MAX_BATCH_SIZE = 20;
  private static final long INITIATING_STALE_SECONDS = 120;
  private static final long[] BACKOFF_SECONDS = {60, 300, 900, 3_600, 14_400, 43_200, 86_400};

  private final DataSource dataSource;
  private final CreditLedgerService creditLedger;
  private final String partnerCode;
  private final OrderQuery orderQuery;
  private final int batchSize;

  public MomoTestOrderReconciliationService(
      DataSource dataSource,
      CreditLedgerService creditLedger,
      String partnerCode,
      OrderQuery orderQuery,
      int batchSize) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.creditLedger = Objects.requireNonNull(creditLedger, "credit ledger is required");
    if (partnerCode == null || partnerCode.isBlank()) {
      throw new IllegalArgumentException("MoMo TEST partner code is required");
    }
    this.partnerCode = partnerCode;
    this.orderQuery = Objects.requireNonNull(orderQuery, "MoMo TEST query adapter is required");
    if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException("reconciliation batch size must be between 1 and 20");
    }
    this.batchSize = batchSize;
  }

  /**
   * Claims a bounded batch, queries MoMo outside database transactions, then applies safe results.
   */
  public int reconcileDueBatch() {
    List<ClaimedOrder> claimed = claimDueOrders();
    for (ClaimedOrder order : claimed) {
      QueryResult result;
      try {
        result =
            orderQuery.query(
                new QueryOrderRequest(
                    partnerCode, order.queryRequestId().toString(), order.id().toString()),
                order.amountVnd());
      } catch (RuntimeException exception) {
        result = new QueryResult(Outcome.UNKNOWN, null, FailureCode.TRANSPORT);
      }
      if (result == null || result.outcome() == null) {
        result = new QueryResult(Outcome.UNKNOWN, null, FailureCode.TRANSPORT);
      }
      try {
        applyQueryResult(order, result);
      } catch (RuntimeException exception) {
        // The claim already moved its retry time forward; never loop on a DB/provider failure.
        LOGGER.warn(
            "MoMo TEST reconciliation result could not be persisted; order remains unresolved");
      }
    }
    return claimed.size();
  }

  @Scheduled(fixedDelayString = "${novel.payment.momo.test.reconciliation.poll-delay:30s}")
  public void runScheduledBatch() {
    try {
      reconcileDueBatch();
    } catch (RuntimeException exception) {
      LOGGER.warn("MoMo TEST reconciliation batch failed; unresolved orders remain unchanged");
    }
  }

  static long backoffSeconds(int attemptNumber) {
    if (attemptNumber < 1 || attemptNumber > MAX_ATTEMPTS) {
      throw new IllegalArgumentException("reconciliation attempt is outside the supported range");
    }
    return BACKOFF_SECONDS[Math.min(attemptNumber - 1, BACKOFF_SECONDS.length - 1)];
  }

  private List<ClaimedOrder> claimDueOrders() {
    List<ClaimedOrder> claimed = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        List<UnclaimedOrder> due = selectDueOrders(connection);
        for (UnclaimedOrder order : due) {
          int attempt = order.attemptCount() + 1;
          updateClaim(connection, order.id(), attempt);
          claimed.add(
              new ClaimedOrder(
                  order.id(), order.userId(), order.amountVnd(), attempt, UUID.randomUUID()));
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to claim due MoMo TEST orders", exception);
    }
    return List.copyOf(claimed);
  }

  private List<UnclaimedOrder> selectDueOrders(Connection connection) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, amount_vnd, reconciliation_attempt_count
                FROM credit_top_up_orders
                WHERE status IN ('PENDING', 'UNKNOWN', 'INITIATING')
                  AND (status <> 'INITIATING'
                       OR updated_at <= clock_timestamp() - (? * INTERVAL '1 second'))
                  AND reconciliation_review_required_at IS NULL
                  AND reconciliation_attempt_count < ?
                  AND (reconciliation_lease_until IS NULL
                       OR reconciliation_lease_until <= clock_timestamp())
                  AND (next_reconciliation_at IS NULL
                       OR next_reconciliation_at <= clock_timestamp())
                ORDER BY COALESCE(next_reconciliation_at, updated_at), created_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """)) {
      select.setLong(1, INITIATING_STALE_SECONDS);
      select.setInt(2, MAX_ATTEMPTS);
      select.setInt(3, batchSize);
      try (ResultSet rows = select.executeQuery()) {
        List<UnclaimedOrder> due = new ArrayList<>();
        while (rows.next()) {
          due.add(
              new UnclaimedOrder(
                  rows.getObject("id", UUID.class),
                  rows.getObject("user_id", UUID.class),
                  rows.getLong("amount_vnd"),
                  rows.getInt("reconciliation_attempt_count")));
        }
        return due;
      }
    }
  }

  private void updateClaim(Connection connection, UUID orderId, int attemptNumber)
      throws SQLException {
    long delaySeconds = backoffSeconds(attemptNumber);
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE credit_top_up_orders
                SET reconciliation_attempt_count = ?,
                    last_reconciliation_at = clock_timestamp(),
                    next_reconciliation_at = CASE WHEN ? >= ? THEN NULL
                        ELSE clock_timestamp() + (? * INTERVAL '1 second') END,
                    reconciliation_lease_until = clock_timestamp()
                        + ((? * 45 + 30) * INTERVAL '1 second'),
                    reconciliation_review_required_at = CASE WHEN ? >= ?
                        THEN COALESCE(reconciliation_review_required_at, clock_timestamp())
                        ELSE reconciliation_review_required_at END,
                    reconciliation_review_code = CASE WHEN ? >= ?
                        THEN 'ATTEMPTS_EXHAUSTED' ELSE reconciliation_review_code END,
                    updated_at = clock_timestamp()
                WHERE id = ?
                """)) {
      update.setInt(1, attemptNumber);
      update.setInt(2, attemptNumber);
      update.setInt(3, MAX_ATTEMPTS);
      update.setLong(4, delaySeconds);
      update.setInt(5, batchSize);
      update.setInt(6, attemptNumber);
      update.setInt(7, MAX_ATTEMPTS);
      update.setInt(8, attemptNumber);
      update.setInt(9, MAX_ATTEMPTS);
      update.setObject(10, orderId);
      if (update.executeUpdate() != 1) throw new SQLException("MoMo TEST order claim was lost");
    }
  }

  private void applyQueryResult(ClaimedOrder claim, QueryResult queryResult) {
    String invalidResponseCode = invalidResponseReviewCode(queryResult);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        OptionalAccount account = lockAccount(connection, claim.userId());
        CurrentOrder current = loadCurrentOrder(connection, claim.id());
        if (current == null) {
          connection.commit();
          return;
        }
        if (current.reconciliationAttemptCount() != claim.attemptNumber()) {
          recordEvent(connection, claim, queryResult, "STALE_IGNORED", null);
          connection.commit();
          return;
        }
        if (invalidResponseCode != null) {
          quarantine(connection, claim, current, queryResult, invalidResponseCode);
          connection.commit();
          return;
        }
        if (isTerminal(current.status())) {
          settleTerminalObservation(connection, claim, current, queryResult);
          connection.commit();
          return;
        }
        if (!account.active()) {
          quarantine(connection, claim, current, queryResult, "ACCOUNT_INACTIVE");
          connection.commit();
          return;
        }

        Outcome outcome = queryResult.outcome();
        if (outcome == Outcome.SUCCEEDED) {
          if (queryResult.transactionId() == null || queryResult.transactionId() <= 0L) {
            updateAsUnknown(
                connection, claim, current, queryResult, claim.attemptNumber() == MAX_ATTEMPTS);
          } else {
            MomoProviderTransactionLock.acquire(connection, queryResult.transactionId());
            if (transactionBelongsToAnotherOrder(
                connection, claim.id(), queryResult.transactionId())) {
              quarantine(connection, claim, current, queryResult, "TRANSACTION_ID_REUSED");
            } else {
              settleSuccess(connection, claim, current, queryResult);
            }
          }
        } else if (outcome == Outcome.PENDING) {
          updateAsPending(
              connection, claim, current, queryResult, claim.attemptNumber() == MAX_ATTEMPTS);
        } else if (outcome == Outcome.FAILED) {
          updateAsFailed(connection, claim, current, queryResult);
        } else {
          updateAsUnknown(
              connection, claim, current, queryResult, claim.attemptNumber() == MAX_ATTEMPTS);
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to apply a MoMo TEST query result", exception);
    }
  }

  private void settleTerminalObservation(
      Connection connection, ClaimedOrder claim, CurrentOrder current, QueryResult queryResult)
      throws SQLException {
    Outcome outcome = queryResult.outcome();
    if (current.status().equals("SUCCEEDED")
        && outcome == Outcome.SUCCEEDED
        && Objects.equals(current.transactionId(), queryResult.transactionId())) {
      clearReconciliationState(connection, current.id());
      recordEvent(connection, claim, queryResult, "SUCCEEDED", null);
      return;
    }
    if (current.status().equals("FAILED") && outcome == Outcome.FAILED) {
      clearReconciliationState(connection, current.id());
      recordEvent(connection, claim, queryResult, "FAILED", null);
      return;
    }
    if (outcome == Outcome.UNKNOWN) {
      recordEvent(connection, claim, queryResult, "STALE_IGNORED", null);
      return;
    }
    quarantine(connection, claim, current, queryResult, "TERMINAL_STATUS_CONFLICT");
  }

  private void settleSuccess(
      Connection connection, ClaimedOrder claim, CurrentOrder current, QueryResult queryResult)
      throws SQLException {
    OperationResult grant =
        creditLedger.grantPaidTopUp(connection, claim.userId(), claim.id(), current.paidCredits());
    if (grant.outcome() != CreditLedgerService.Outcome.APPLIED
        && grant.outcome() != CreditLedgerService.Outcome.ALREADY_APPLIED) {
      if (grant.outcome() == CreditLedgerService.Outcome.ACCOUNT_INACTIVE
          || grant.outcome() == CreditLedgerService.Outcome.ACCOUNT_NOT_FOUND) {
        quarantine(connection, claim, current, queryResult, "ACCOUNT_INACTIVE");
        return;
      }
      throw new SQLException("Verified MoMo TEST settlement credit grant did not apply");
    }
    updateOrder(connection, current, "SUCCEEDED", queryResult.transactionId(), null, true);
    recordEvent(connection, claim, queryResult, "SUCCEEDED", null);
  }

  private void updateAsPending(
      Connection connection,
      ClaimedOrder claim,
      CurrentOrder current,
      QueryResult queryResult,
      boolean exhausted)
      throws SQLException {
    String reviewCode = exhausted ? "ATTEMPTS_EXHAUSTED" : null;
    updateOrder(connection, current, "PENDING", null, reviewCode, reviewCode == null);
    recordEvent(connection, claim, queryResult, "PENDING", reviewCode);
  }

  private void updateAsUnknown(
      Connection connection,
      ClaimedOrder claim,
      CurrentOrder current,
      QueryResult queryResult,
      boolean exhausted)
      throws SQLException {
    String reviewCode = exhausted ? "ATTEMPTS_EXHAUSTED" : null;
    updateOrder(connection, current, "UNKNOWN", null, reviewCode, reviewCode == null);
    recordEvent(connection, claim, queryResult, "UNKNOWN", reviewCode);
  }

  private void updateAsFailed(
      Connection connection, ClaimedOrder claim, CurrentOrder current, QueryResult queryResult)
      throws SQLException {
    updateOrder(connection, current, "FAILED", null, null, true);
    recordEvent(connection, claim, queryResult, "FAILED", null);
  }

  private void quarantine(
      Connection connection,
      ClaimedOrder claim,
      CurrentOrder current,
      QueryResult queryResult,
      String reviewCode)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE credit_top_up_orders
                SET reconciliation_review_required_at = COALESCE(
                        reconciliation_review_required_at, clock_timestamp()),
                    reconciliation_review_code = ?,
                    next_reconciliation_at = NULL,
                    reconciliation_lease_until = NULL,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = ?
                """)) {
      update.setString(1, reviewCode);
      update.setObject(2, current.id());
      update.setString(3, current.status());
      if (update.executeUpdate() != 1)
        throw new SQLException("MoMo TEST order changed during quarantine");
    }
    recordEvent(connection, claim, queryResult, "QUARANTINED", reviewCode);
  }

  private static void updateOrder(
      Connection connection,
      CurrentOrder current,
      String status,
      Long transactionId,
      String reviewCode,
      boolean clearReview)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE credit_top_up_orders
                SET status = ?,
                    checkout_url = CASE WHEN ? = 'PENDING' THEN checkout_url ELSE NULL END,
                    provider_transaction_id = COALESCE(?, provider_transaction_id),
                    next_reconciliation_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED')
                        THEN NULL ELSE next_reconciliation_at END,
                    reconciliation_lease_until = NULL,
                    reconciliation_review_required_at = CASE WHEN ? THEN NULL
                        WHEN ? IS NOT NULL THEN COALESCE(reconciliation_review_required_at, clock_timestamp())
                        ELSE reconciliation_review_required_at END,
                    reconciliation_review_code = CASE WHEN ? THEN NULL
                        WHEN ? IS NOT NULL THEN ? ELSE reconciliation_review_code END,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = ?
                """)) {
      update.setString(1, status);
      update.setString(2, status);
      if (transactionId == null) update.setNull(3, java.sql.Types.BIGINT);
      else update.setLong(3, transactionId);
      update.setString(4, status);
      update.setBoolean(5, clearReview);
      update.setString(6, reviewCode);
      update.setBoolean(7, clearReview);
      update.setString(8, reviewCode);
      update.setString(9, reviewCode);
      update.setObject(10, current.id());
      update.setString(11, current.status());
      if (update.executeUpdate() != 1)
        throw new SQLException("MoMo TEST order transition was lost");
    }
  }

  private static void clearReconciliationState(Connection connection, UUID orderId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE credit_top_up_orders
                SET next_reconciliation_at = NULL,
                    reconciliation_lease_until = NULL,
                    reconciliation_review_required_at = NULL,
                    reconciliation_review_code = NULL
                WHERE id = ?
                """)) {
      update.setObject(1, orderId);
      if (update.executeUpdate() != 1) throw new SQLException("MoMo TEST order disappeared");
    }
  }

  private static void recordEvent(
      Connection connection,
      ClaimedOrder claim,
      QueryResult queryResult,
      String outcome,
      String reviewCode)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO momo_test_reconciliation_events (
                    query_request_id, credit_top_up_order_id, attempt_number, outcome,
                    provider_transaction_id, review_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, claim.queryRequestId());
      insert.setObject(2, claim.id());
      insert.setInt(3, claim.attemptNumber());
      insert.setString(4, outcome);
      if (queryResult.transactionId() == null || queryResult.transactionId() <= 0L) {
        insert.setNull(5, java.sql.Types.BIGINT);
      } else {
        insert.setLong(5, queryResult.transactionId());
      }
      insert.setString(6, reviewCode);
      if (insert.executeUpdate() != 1)
        throw new SQLException("MoMo reconciliation event was not saved");
    }
  }

  private static boolean transactionBelongsToAnotherOrder(
      Connection connection, UUID orderId, long transactionId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id FROM credit_top_up_orders
                WHERE provider = 'MOMO' AND environment = 'TEST'
                  AND provider_transaction_id = ? AND id <> ?
                """)) {
      select.setLong(1, transactionId);
      select.setObject(2, orderId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
      }
    }
  }

  private static CurrentOrder loadCurrentOrder(Connection connection, UUID orderId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, status, paid_credits, provider_transaction_id,
                       reconciliation_attempt_count
                FROM credit_top_up_orders WHERE id = ? FOR UPDATE
                """)) {
      select.setObject(1, orderId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return null;
        return new CurrentOrder(
            row.getObject("id", UUID.class),
            row.getObject("user_id", UUID.class),
            row.getString("status"),
            row.getLong("paid_credits"),
            (Long) row.getObject("provider_transaction_id"),
            row.getInt("reconciliation_attempt_count"));
      }
    }
  }

  private static OptionalAccount lockAccount(Connection connection, UUID userId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT status FROM users WHERE id = ? FOR UPDATE")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        return new OptionalAccount(row.next() && "active".equals(row.getString("status")));
      }
    }
  }

  private static String invalidResponseReviewCode(QueryResult result) {
    if (result.outcome() == Outcome.INVALID_RESPONSE
        || result.failureCode() == FailureCode.INVALID_RESPONSE) {
      return "QUERY_INVALID_RESPONSE";
    }
    if (result.failureCode() == FailureCode.ORDER_MISMATCH) return "QUERY_ORDER_MISMATCH";
    return null;
  }

  private static boolean isTerminal(String status) {
    return status.equals("SUCCEEDED")
        || status.equals("FAILED")
        || status.equals("CANCELLED")
        || status.equals("EXPIRED");
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  public interface OrderQuery {
    QueryResult query(QueryOrderRequest request, long amountVnd);
  }

  private record UnclaimedOrder(UUID id, UUID userId, long amountVnd, int attemptCount) {}

  private record ClaimedOrder(
      UUID id, UUID userId, long amountVnd, int attemptNumber, UUID queryRequestId) {}

  private record CurrentOrder(
      UUID id,
      UUID userId,
      String status,
      long paidCredits,
      Long transactionId,
      int reconciliationAttemptCount) {}

  private record OptionalAccount(boolean active) {}
}
