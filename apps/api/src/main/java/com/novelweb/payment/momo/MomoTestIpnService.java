package com.novelweb.payment.momo;

import com.novelweb.payment.credit.CreditLedgerService;
import com.novelweb.payment.credit.CreditLedgerService.OperationResult;
import com.novelweb.payment.credit.CreditLedgerService.Outcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Verifies signed MoMo TEST notifications and settles matched credit top-ups atomically. */
public final class MomoTestIpnService {

  private final DataSource dataSource;
  private final CreditLedgerService creditLedger;
  private final MomoTestGatewaySettings settings;

  public MomoTestIpnService(
      DataSource dataSource, CreditLedgerService creditLedger, MomoTestGatewaySettings settings) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.creditLedger = Objects.requireNonNull(creditLedger, "credit ledger is required");
    this.settings = Objects.requireNonNull(settings, "validated MoMo TEST settings are required");
  }

  /**
   * Processes one signed provider notification. The raw body and signature are never persisted.
   * Successful order transition and paid-credit grant share the same PostgreSQL transaction.
   */
  public Result process(MomoSignatureCodec.PaymentNotification notification, String signature) {
    if (!MomoSignatureCodec.verifyNotification(
        notification, settings.accessKey(), settings.secretKey(), signature)) {
      return Result.INVALID_SIGNATURE;
    }
    if (!isValidTestNotification(notification)) return Result.INVALID_NOTIFICATION;

    String eventHash = MomoSignatureCodec.notificationFingerprint(notification);
    UUID orderId = canonicalUuid(notification.orderId());
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<Result> existingEvent = findEventOutcome(connection, eventHash);
        if (existingEvent.isPresent() && existingEvent.orElseThrow() != Result.ACCOUNT_INACTIVE) {
          connection.commit();
          return Result.DUPLICATE;
        }
        boolean previouslyStored = existingEvent.isPresent();
        if (orderId == null) {
          storeEvent(
              connection, eventHash, null, notification, Result.ORDER_NOT_FOUND, previouslyStored);
          connection.commit();
          return Result.ORDER_NOT_FOUND;
        }

        Optional<UUID> owner = findOrderOwner(connection, orderId);
        if (owner.isEmpty()) {
          storeEvent(
              connection, eventHash, null, notification, Result.ORDER_NOT_FOUND, previouslyStored);
          connection.commit();
          return Result.ORDER_NOT_FOUND;
        }
        UUID userId = owner.orElseThrow();
        boolean active = accountActiveAndLocked(connection, userId);
        Optional<OrderSnapshot> found = findOrderForActor(connection, userId, orderId);
        if (found.isEmpty()) {
          storeEvent(connection, eventHash, null, notification, Result.ORDER_NOT_FOUND, false);
          connection.commit();
          return Result.ORDER_NOT_FOUND;
        }
        OrderSnapshot order = found.orElseThrow();

        Optional<Result> priorEvent = findEventOutcome(connection, eventHash);
        if (priorEvent.isPresent() && priorEvent.orElseThrow() != Result.ACCOUNT_INACTIVE) {
          connection.commit();
          return Result.DUPLICATE;
        }

        if (!matchesOrder(order, notification)) {
          storeEvent(
              connection,
              eventHash,
              orderId,
              notification,
              Result.ORDER_MISMATCH,
              priorEvent.isPresent());
          connection.commit();
          return Result.ORDER_MISMATCH;
        }
        if (!active) {
          storeEvent(
              connection,
              eventHash,
              orderId,
              notification,
              Result.ACCOUNT_INACTIVE,
              priorEvent.isPresent());
          connection.commit();
          return Result.ACCOUNT_INACTIVE;
        }

        if (order.status() == OrderStatus.SUCCEEDED) {
          Result repeated =
              Objects.equals(order.providerTransactionId(), notification.transactionId())
                  ? Result.DUPLICATE
                  : Result.TERMINAL_CONFLICT;
          storeEvent(
              connection, eventHash, orderId, notification, repeated, priorEvent.isPresent());
          connection.commit();
          return repeated;
        }
        if (order.status() == OrderStatus.FAILED
            || order.status() == OrderStatus.CANCELLED
            || order.status() == OrderStatus.EXPIRED
            || order.status() == OrderStatus.CREATED) {
          storeEvent(
              connection,
              eventHash,
              orderId,
              notification,
              Result.TERMINAL_CONFLICT,
              priorEvent.isPresent());
          connection.commit();
          return Result.TERMINAL_CONFLICT;
        }

        if (notification.transactionId() > 0L) {
          MomoProviderTransactionLock.acquire(connection, notification.transactionId());
        }
        Optional<UUID> transactionOwner =
            findTransactionOrder(connection, notification.transactionId());
        if (transactionOwner.isPresent() && !transactionOwner.orElseThrow().equals(orderId)) {
          storeEvent(
              connection,
              eventHash,
              orderId,
              notification,
              Result.TRANSACTION_CONFLICT,
              priorEvent.isPresent());
          connection.commit();
          return Result.TRANSACTION_CONFLICT;
        }

        Result result = classify(order, notification);
        if (result == Result.SUCCEEDED) {
          OperationResult grant =
              creditLedger.grantPaidTopUp(connection, userId, orderId, order.paidCredits());
          if (grant.outcome() != Outcome.APPLIED && grant.outcome() != Outcome.ALREADY_APPLIED) {
            throw new SQLException("Verified top-up credit grant did not apply");
          }
        }
        updateOrder(connection, order, result, notification.transactionId());
        storeEvent(connection, eventHash, orderId, notification, result, priorEvent.isPresent());
        connection.commit();
        return result;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to process the MoMo TEST notification", exception);
    }
  }

  private boolean isValidTestNotification(MomoSignatureCodec.PaymentNotification notification) {
    return notification != null
        && settings.partnerCode().equals(notification.partnerCode())
        && "momo_wallet".equals(notification.orderType())
        && "Novel Web credit top-up".equals(notification.orderInfo())
        && notification.extraData().isEmpty()
        && !notification.message().isBlank()
        && notification.message().length() <= 2_048
        && !notification.payType().isBlank()
        && notification.payType().length() <= 128
        && notification.orderId().length() <= 200
        && notification.requestId().length() <= 50
        && notification.amountVnd() >= 1_000L
        && notification.amountVnd() <= 50_000_000L
        && notification.responseTime() > 0L
        && notification.transactionId() >= 0L;
  }

  private static Optional<UUID> findOrderOwner(Connection connection, UUID orderId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT user_id FROM credit_top_up_orders WHERE id = ?")) {
      select.setObject(1, orderId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(row.getObject("user_id", UUID.class)) : Optional.empty();
      }
    }
  }

  private static boolean accountActiveAndLocked(Connection connection, UUID userId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT status FROM users WHERE id = ? FOR UPDATE")) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() && "active".equals(row.getString("status"));
      }
    }
  }

  private static Optional<OrderSnapshot> findOrderForActor(
      Connection connection, UUID userId, UUID orderId) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, request_id, provider, environment, currency, amount_vnd, paid_credits,
                       status, provider_transaction_id, auto_capture
                FROM credit_top_up_orders
                WHERE user_id = ? AND id = ?
                FOR UPDATE
                """)) {
      select.setObject(1, userId);
      select.setObject(2, orderId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new OrderSnapshot(
                row.getObject("id", UUID.class),
                row.getObject("request_id", UUID.class),
                row.getString("provider"),
                row.getString("environment"),
                row.getString("currency"),
                row.getLong("amount_vnd"),
                row.getLong("paid_credits"),
                OrderStatus.valueOf(row.getString("status")),
                (Long) row.getObject("provider_transaction_id"),
                row.getBoolean("auto_capture")));
      }
    }
  }

  private static boolean matchesOrder(
      OrderSnapshot order, MomoSignatureCodec.PaymentNotification notification) {
    return "MOMO".equals(order.provider())
        && "TEST".equals(order.environment())
        && "VND".equals(order.currency())
        && order.amountVnd() == notification.amountVnd()
        && order.id().toString().equals(notification.orderId())
        && order.requestId().toString().equals(notification.requestId());
  }

  private static Optional<Result> findEventOutcome(Connection connection, String eventHash)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT processing_outcome FROM momo_test_ipn_events WHERE event_hash = ? FOR UPDATE")) {
      select.setString(1, eventHash);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(Result.valueOf(row.getString("processing_outcome")))
            : Optional.empty();
      }
    }
  }

  private static Optional<UUID> findTransactionOrder(Connection connection, long transactionId)
      throws SQLException {
    if (transactionId <= 0L) return Optional.empty();
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM credit_top_up_orders "
                + "WHERE provider = 'MOMO' AND environment = 'TEST' AND provider_transaction_id = ?")) {
      select.setLong(1, transactionId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(row.getObject("id", UUID.class)) : Optional.empty();
      }
    }
  }

  private static Result classify(
      OrderSnapshot order, MomoSignatureCodec.PaymentNotification notification) {
    return switch (notification.resultCode()) {
      case 0 -> notification.transactionId() > 0L ? Result.SUCCEEDED : Result.UNKNOWN;
      case 9000 ->
          order.autoCapture()
              ? (notification.transactionId() > 0L ? Result.SUCCEEDED : Result.UNKNOWN)
              : Result.PENDING;
      case 1000, 7000, 7002 -> Result.PENDING;
      case 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1017 -> Result.FAILED;
      default -> Result.UNKNOWN;
    };
  }

  private static void updateOrder(
      Connection connection, OrderSnapshot order, Result result, long transactionId)
      throws SQLException {
    OrderStatus nextStatus =
        switch (result) {
          case SUCCEEDED -> OrderStatus.SUCCEEDED;
          case PENDING -> OrderStatus.PENDING;
          case FAILED -> OrderStatus.FAILED;
          case UNKNOWN -> OrderStatus.UNKNOWN;
          default -> throw new IllegalArgumentException("result is not a payment transition");
        };
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE credit_top_up_orders
                SET status = ?,
                    checkout_url = CASE WHEN ? = 'PENDING' THEN checkout_url ELSE NULL END,
                    provider_transaction_id = CASE WHEN ? > 0 THEN ? ELSE provider_transaction_id END,
                    next_reconciliation_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED')
                        THEN NULL ELSE next_reconciliation_at END,
                    reconciliation_lease_until = NULL,
                    reconciliation_review_required_at = CASE WHEN ? IN ('SUCCEEDED', 'FAILED')
                        THEN NULL ELSE reconciliation_review_required_at END,
                    reconciliation_review_code = CASE WHEN ? IN ('SUCCEEDED', 'FAILED')
                        THEN NULL ELSE reconciliation_review_code END,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status = ?
                """)) {
      update.setString(1, nextStatus.name());
      update.setString(2, nextStatus.name());
      update.setLong(3, nextStatus == OrderStatus.SUCCEEDED ? transactionId : 0L);
      update.setLong(4, nextStatus == OrderStatus.SUCCEEDED ? transactionId : 0L);
      update.setString(5, nextStatus.name());
      update.setString(6, nextStatus.name());
      update.setString(7, nextStatus.name());
      update.setObject(8, order.id());
      update.setString(9, order.status().name());
      if (update.executeUpdate() != 1)
        throw new SQLException("MoMo order state changed concurrently");
    }
  }

  private static void storeEvent(
      Connection connection,
      String eventHash,
      UUID orderId,
      MomoSignatureCodec.PaymentNotification notification,
      Result outcome,
      boolean previouslyStored)
      throws SQLException {
    if (previouslyStored) {
      try (PreparedStatement update =
          connection.prepareStatement(
              "UPDATE momo_test_ipn_events SET processing_outcome = ? WHERE event_hash = ?")) {
        update.setString(1, outcome.name());
        update.setString(2, eventHash);
        if (update.executeUpdate() != 1) throw new SQLException("MoMo IPN event disappeared");
      }
      return;
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO momo_test_ipn_events (
                    event_hash, provider_order_id, provider_request_id, provider_transaction_id,
                    amount_vnd, result_code, credit_top_up_order_id, processing_outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_hash) DO NOTHING
                """)) {
      insert.setString(1, eventHash);
      insert.setString(2, notification.orderId());
      insert.setString(3, notification.requestId());
      insert.setLong(4, notification.transactionId());
      insert.setLong(5, notification.amountVnd());
      insert.setInt(6, notification.resultCode());
      insert.setObject(7, orderId);
      insert.setString(8, outcome.name());
      if (insert.executeUpdate() == 0) {
        throw new SQLException("MoMo IPN event was concurrently processed");
      }
    }
  }

  private static UUID canonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      return parsed.toString().equals(value) ? parsed : null;
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  private record OrderSnapshot(
      UUID id,
      UUID requestId,
      String provider,
      String environment,
      String currency,
      long amountVnd,
      long paidCredits,
      OrderStatus status,
      Long providerTransactionId,
      boolean autoCapture) {}

  private enum OrderStatus {
    CREATED,
    INITIATING,
    PENDING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
  }

  public enum Result {
    INVALID_SIGNATURE,
    INVALID_NOTIFICATION,
    DUPLICATE,
    ORDER_NOT_FOUND,
    ORDER_MISMATCH,
    TERMINAL_CONFLICT,
    ACCOUNT_INACTIVE,
    TRANSACTION_CONFLICT,
    SUCCEEDED,
    PENDING,
    FAILED,
    UNKNOWN
  }
}
