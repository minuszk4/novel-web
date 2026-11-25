package com.novelweb.payment.vnpay;

import com.novelweb.payment.credit.CreditLedgerService;
import com.novelweb.payment.credit.CreditLedgerService.OperationResult;
import com.novelweb.payment.vnpay.VnPayTestQueryDrClient.FailureCode;
import com.novelweb.payment.vnpay.VnPayTestQueryDrClient.QueryResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Bounded, idempotent settlement recovery for unresolved VNPAY TEST top-up orders. */
public final class VnPayTestOrderReconciliationService {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(VnPayTestOrderReconciliationService.class);
  private static final int MAX_ATTEMPTS = 8;
  private static final int MAX_BATCH_SIZE = 20;
  private static final long INITIATING_STALE_SECONDS = 120;
  private static final long[] BACKOFF_SECONDS = {60, 300, 900, 3_600, 14_400, 43_200, 86_400};

  private final DataSource dataSource;
  private final CreditLedgerService creditLedger;
  private final String tmnCode;
  private final String serverIpAddress;
  private final OrderQuery orderQuery;
  private final int batchSize;
  private final Clock clock;

  public VnPayTestOrderReconciliationService(
      DataSource dataSource,
      CreditLedgerService creditLedger,
      String tmnCode,
      String serverIpAddress,
      OrderQuery orderQuery,
      int batchSize) {
    this(
        dataSource,
        creditLedger,
        tmnCode,
        serverIpAddress,
        orderQuery,
        batchSize,
        Clock.systemUTC());
  }

  VnPayTestOrderReconciliationService(
      DataSource dataSource,
      CreditLedgerService creditLedger,
      String tmnCode,
      String serverIpAddress,
      OrderQuery orderQuery,
      int batchSize,
      Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.creditLedger = Objects.requireNonNull(creditLedger, "credit ledger is required");
    this.tmnCode = Objects.requireNonNull(tmnCode, "VNPAY TEST TmnCode is required");
    this.serverIpAddress =
        Objects.requireNonNull(serverIpAddress, "VNPAY TEST server IP address is required");
    this.orderQuery = Objects.requireNonNull(orderQuery, "VNPAY QueryDR client is required");
    if (!tmnCode.matches("[A-Za-z0-9]{8}")) {
      throw new IllegalArgumentException("VNPAY TEST TmnCode is invalid");
    }
    if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException("VNPAY TEST reconciliation batch size must be 1 to 20");
    }
    this.batchSize = batchSize;
    this.clock = Objects.requireNonNull(clock, "clock is required");
  }

  /** Claims a bounded batch, queries VNPAY outside database transactions, then applies results. */
  public int reconcileDueBatch() {
    List<ClaimedOrder> claimed = claimDueOrders();
    for (ClaimedOrder order : claimed) {
      QueryResult result;
      try {
        result =
            orderQuery.query(
                new VnPayTestQueryDrClient.QueryRequest(
                    tmnCode,
                    compactRequestId(order.queryRequestId()),
                    order.id(),
                    order.amountVnd(),
                    VnPayTestGatewayClient.formatVnPayDate(order.paymentCreatedAt()),
                    VnPayTestGatewayClient.formatVnPayDate(clock.instant()),
                    serverIpAddress));
      } catch (IllegalArgumentException exception) {
        result =
            new QueryResult(
                VnPayTestQueryDrClient.Outcome.UNKNOWN, null, FailureCode.INVALID_REQUEST);
      } catch (RuntimeException exception) {
        result =
            new QueryResult(VnPayTestQueryDrClient.Outcome.UNKNOWN, null, FailureCode.TRANSPORT);
      }
      if (result == null || result.outcome() == null) {
        result =
            new QueryResult(VnPayTestQueryDrClient.Outcome.UNKNOWN, null, FailureCode.TRANSPORT);
      }
      try {
        applyQueryResult(order, result);
      } catch (RuntimeException exception) {
        // The claim moved its retry time forward; do not loop on a provider or database failure.
        LOGGER.warn(
            "VNPAY TEST reconciliation result could not be persisted; order remains unresolved");
      }
    }
    return claimed.size();
  }

  @Scheduled(fixedDelayString = "${novel.payment.vnpay.test.reconciliation.poll-delay:30s}")
  public void runScheduledBatch() {
    try {
      reconcileDueBatch();
    } catch (RuntimeException exception) {
      LOGGER.warn("VNPAY TEST reconciliation batch failed; unresolved orders remain unchanged");
    }
  }

  static long backoffSeconds(int attemptNumber) {
    if (attemptNumber < 1 || attemptNumber > MAX_ATTEMPTS) {
      throw new IllegalArgumentException(
          "VNPAY reconciliation attempt is outside the supported range");
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
                  order.id(),
                  order.userId(),
                  order.amountVnd(),
                  order.paymentCreatedAt(),
                  attempt,
                  UUID.randomUUID()));
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to claim due VNPAY TEST orders", exception);
    }
    return List.copyOf(claimed);
  }

  private List<UnclaimedOrder> selectDueOrders(Connection connection) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, amount_vnd, created_at, reconciliation_attempt_count
                FROM credit_top_up_orders
                WHERE provider = 'VNPAY' AND environment = 'TEST'
                  AND status IN ('PENDING', 'UNKNOWN', 'INITIATING')
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
                  rows.getObject("created_at", OffsetDateTime.class).toInstant(),
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
                WHERE id = ? AND provider = 'VNPAY' AND environment = 'TEST'
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
      if (update.executeUpdate() != 1) {
        throw new SQLException("VNPAY TEST order claim was lost");
      }
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

        switch (queryResult.outcome()) {
          case SUCCEEDED -> {
            Long transactionId = queryResult.transactionId();
            if (transactionId == null || transactionId <= 0) {
              updateAsUnknown(connection, claim, current, queryResult, exhausted(claim));
            } else {
              VnPayProviderTransactionLock.acquire(connection, transactionId);
              if (transactionBelongsToAnotherOrder(connection, claim.id(), transactionId)) {
                quarantine(connection, claim, current, queryResult, "TRANSACTION_ID_REUSED");
              } else {
                settleSuccess(connection, claim, current, queryResult);
              }
            }
          }
          case PENDING ->
              updateAsPending(connection, claim, current, queryResult, exhausted(claim));
          case FAILED -> updateAsFailed(connection, claim, current, queryResult);
          case UNKNOWN ->
              updateAsUnknown(connection, claim, current, queryResult, exhausted(claim));
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to apply a VNPAY TEST query result", exception);
    }
  }

  private void settleTerminalObservation(
      Connection connection, ClaimedOrder claim, CurrentOrder current, QueryResult result)
      throws SQLException {
    if (current.status().equals("SUCCEEDED")
        && result.outcome() == VnPayTestQueryDrClient.Outcome.SUCCEEDED
        && Objects.equals(current.transactionId(), result.transactionId())) {
      clearReconciliationState(connection, current.id());
      recordEvent(connection, claim, result, "SUCCEEDED", null);
      return;
    }
    if (current.status().equals("FAILED")
        && result.outcome() == VnPayTestQueryDrClient.Outcome.FAILED) {
      clearReconciliationState(connection, current.id());
      recordEvent(connection, claim, result, "FAILED", null);
      return;
    }
    if (result.outcome() == VnPayTestQueryDrClient.Outcome.UNKNOWN) {
      recordEvent(connection, claim, result, "STALE_IGNORED", null);
      return;
    }
    quarantine(connection, claim, current, result, "TERMINAL_STATUS_CONFLICT");
  }

  private void settleSuccess(
      Connection connection, ClaimedOrder claim, CurrentOrder current, QueryResult result)
      throws SQLException {
    OperationResult grant =
        creditLedger.grantPaidTopUp(connection, claim.userId(), claim.id(), current.paidCredits());
    if (grant.outcome() != CreditLedgerService.Outcome.APPLIED
        && grant.outcome() != CreditLedgerService.Outcome.ALREADY_APPLIED) {
      if (grant.outcome() == CreditLedgerService.Outcome.ACCOUNT_INACTIVE
          || grant.outcome() == CreditLedgerService.Outcome.ACCOUNT_NOT_FOUND) {
        quarantine(connection, claim, current, result, "ACCOUNT_INACTIVE");
        return;
      }
      throw new SQLException("Verified VNPAY TEST settlement credit grant did not apply");
    }
    updateOrder(connection, current, "SUCCEEDED", result.transactionId(), null, true);
    recordEvent(connection, claim, result, "SUCCEEDED", null);
  }

  private void updateAsPending(
      Connection connection,
      ClaimedOrder claim,
      CurrentOrder current,
      QueryResult result,
      boolean exhausted)
      throws SQLException {
    String reviewCode = exhausted ? "ATTEMPTS_EXHAUSTED" : null;
    updateOrder(connection, current, "PENDING", null, reviewCode, reviewCode == null);
    recordEvent(connection, claim, result, "PENDING", reviewCode);
  }

  private void updateAsUnknown(
      Connection connection,
      ClaimedOrder claim,
      CurrentOrder current,
      QueryResult result,
      boolean exhausted)
      throws SQLException {
    String reviewCode = exhausted ? "ATTEMPTS_EXHAUSTED" : null;
    updateOrder(connection, current, "UNKNOWN", null, reviewCode, reviewCode == null);
    recordEvent(connection, claim, result, "UNKNOWN", reviewCode);
  }

  private void updateAsFailed(
      Connection connection, ClaimedOrder claim, CurrentOrder current, QueryResult result)
      throws SQLException {
    updateOrder(connection, current, "FAILED", null, null, true);
    recordEvent(connection, claim, result, "FAILED", null);
  }

  private void quarantine(
      Connection connection,
      ClaimedOrder claim,
      CurrentOrder current,
      QueryResult result,
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
                WHERE id = ? AND status = ? AND provider = 'VNPAY' AND environment = 'TEST'
                """)) {
      update.setString(1, reviewCode);
      update.setObject(2, current.id());
      update.setString(3, current.status());
      if (update.executeUpdate() != 1) {
        throw new SQLException("VNPAY TEST order changed during quarantine");
      }
    }
    recordEvent(connection, claim, result, "QUARANTINED", reviewCode);
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
                WHERE id = ? AND status = ? AND provider = 'VNPAY' AND environment = 'TEST'
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
      if (update.executeUpdate() != 1) {
        throw new SQLException("VNPAY TEST order transition was lost");
      }
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
                WHERE id = ? AND provider = 'VNPAY' AND environment = 'TEST'
                """)) {
      update.setObject(1, orderId);
      if (update.executeUpdate() != 1) throw new SQLException("VNPAY TEST order disappeared");
    }
  }

  private static void recordEvent(
      Connection connection,
      ClaimedOrder claim,
      QueryResult result,
      String outcome,
      String reviewCode)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO vnpay_test_reconciliation_events (
                    query_request_id, credit_top_up_order_id, attempt_number, outcome,
                    provider_transaction_id, review_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, claim.queryRequestId());
      insert.setObject(2, claim.id());
      insert.setInt(3, claim.attemptNumber());
      insert.setString(4, outcome);
      if (result.transactionId() == null || result.transactionId() <= 0) {
        insert.setNull(5, java.sql.Types.BIGINT);
      } else {
        insert.setLong(5, result.transactionId());
      }
      insert.setString(6, reviewCode);
      if (insert.executeUpdate() != 1) {
        throw new SQLException("VNPAY TEST reconciliation event was not saved");
      }
    }
  }

  private static boolean transactionBelongsToAnotherOrder(
      Connection connection, UUID orderId, long transactionId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id FROM credit_top_up_orders
                WHERE provider = 'VNPAY' AND environment = 'TEST'
                  AND provider_transaction_id = ? AND id <> ?
                """)) {
      select.setLong(1, transactionId);
      select.setObject(2, orderId);
      try (ResultSet row = select.executeQuery()) {
        return row.next();
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

  private static CurrentOrder loadCurrentOrder(Connection connection, UUID orderId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, user_id, status, paid_credits, provider_transaction_id,
                       reconciliation_attempt_count
                FROM credit_top_up_orders
                WHERE id = ? AND provider = 'VNPAY' AND environment = 'TEST'
                FOR UPDATE
                """)) {
      select.setObject(1, orderId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? new CurrentOrder(
                row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getString("status"),
                row.getLong("paid_credits"),
                (Long) row.getObject("provider_transaction_id"),
                row.getInt("reconciliation_attempt_count"))
            : null;
      }
    }
  }

  private static boolean exhausted(ClaimedOrder claim) {
    return claim.attemptNumber() >= MAX_ATTEMPTS;
  }

  private static String invalidResponseReviewCode(QueryResult result) {
    if (result.failureCode() == FailureCode.INVALID_RESPONSE
        || result.failureCode() == FailureCode.INVALID_REQUEST) {
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

  private static String compactRequestId(UUID requestId) {
    return requestId.toString().replace("-", "");
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  @FunctionalInterface
  public interface OrderQuery {
    QueryResult query(VnPayTestQueryDrClient.QueryRequest request);
  }

  private record UnclaimedOrder(
      UUID id, UUID userId, long amountVnd, Instant paymentCreatedAt, int attemptCount) {}

  private record ClaimedOrder(
      UUID id,
      UUID userId,
      long amountVnd,
      Instant paymentCreatedAt,
      int attemptNumber,
      UUID queryRequestId) {}

  private record CurrentOrder(
      UUID id,
      UUID userId,
      String status,
      long paidCredits,
      Long transactionId,
      int reconciliationAttemptCount) {}

  private record OptionalAccount(boolean active) {}
}
