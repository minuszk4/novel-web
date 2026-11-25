package com.novelweb.payment.vnpay;

import com.novelweb.payment.credit.CreditLedgerService;
import com.novelweb.payment.credit.CreditLedgerService.OperationResult;
import com.novelweb.payment.credit.CreditLedgerService.Outcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Verifies VNPAY TEST IPNs and atomically records payment state and paid-credit grants. */
public final class VnPayTestIpnService {

  private final DataSource dataSource;
  private final CreditLedgerService creditLedger;
  private final VnPayTestGatewaySettings settings;

  public VnPayTestIpnService(
      DataSource dataSource, CreditLedgerService creditLedger, VnPayTestGatewaySettings settings) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.creditLedger = Objects.requireNonNull(creditLedger, "credit ledger is required");
    this.settings = Objects.requireNonNull(settings, "VNPAY TEST settings are required");
  }

  public Acknowledgement process(Map<String, String> parameters) {
    if (parameters == null) return acknowledgement("95", "Invalid request");
    String signature = parameters.get("vnp_SecureHash");
    Map<String, String> signedFields = VnPayTestSignatureCodec.signedFields(parameters);
    if (!VnPayTestSignatureCodec.verify(signedFields, settings.hashSecret(), signature)) {
      return acknowledgement("97", "Invalid signature");
    }
    Notification notification = Notification.from(signedFields, settings.tmnCode());
    if (notification == null) return acknowledgement("95", "Invalid request");

    String eventHash = fingerprint(signedFields, signature);
    UUID orderId = parseOrderReference(notification.txnRef());
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        Optional<EventOutcome> priorEvent = findEvent(connection, eventHash);
        if (priorEvent.isPresent() && priorEvent.orElseThrow() != EventOutcome.ACCOUNT_INACTIVE) {
          connection.commit();
          return ackFor(EventOutcome.DUPLICATE);
        }
        boolean previouslyStored = priorEvent.isPresent();
        if (orderId == null) {
          saveEvent(
              connection,
              eventHash,
              notification,
              null,
              EventOutcome.ORDER_NOT_FOUND,
              previouslyStored);
          connection.commit();
          return ackFor(EventOutcome.ORDER_NOT_FOUND);
        }

        Optional<UUID> owner = findOrderOwner(connection, orderId);
        if (owner.isEmpty()) {
          saveEvent(
              connection,
              eventHash,
              notification,
              null,
              EventOutcome.ORDER_NOT_FOUND,
              previouslyStored);
          connection.commit();
          return ackFor(EventOutcome.ORDER_NOT_FOUND);
        }
        UUID userId = owner.orElseThrow();
        boolean active = accountActiveAndLocked(connection, userId);
        Optional<OrderSnapshot> found = findOrder(connection, userId, orderId);
        if (found.isEmpty()) {
          saveEvent(
              connection, eventHash, notification, orderId, EventOutcome.ORDER_NOT_FOUND, false);
          connection.commit();
          return ackFor(EventOutcome.ORDER_NOT_FOUND);
        }
        OrderSnapshot order = found.orElseThrow();
        Optional<EventOutcome> repeated = findEvent(connection, eventHash);
        if (repeated.isPresent() && repeated.orElseThrow() != EventOutcome.ACCOUNT_INACTIVE) {
          connection.commit();
          return ackFor(EventOutcome.DUPLICATE);
        }

        if (!matchesOrder(order, notification)) {
          saveEvent(
              connection,
              eventHash,
              notification,
              orderId,
              EventOutcome.ORDER_MISMATCH,
              repeated.isPresent());
          connection.commit();
          return ackFor(EventOutcome.ORDER_MISMATCH);
        }
        if (!active) {
          saveEvent(
              connection,
              eventHash,
              notification,
              orderId,
              EventOutcome.ACCOUNT_INACTIVE,
              repeated.isPresent());
          connection.commit();
          return ackFor(EventOutcome.ACCOUNT_INACTIVE);
        }
        if (order.status() == OrderStatus.SUCCEEDED) {
          EventOutcome outcome =
              Objects.equals(order.providerTransactionId(), notification.transactionNo())
                  ? EventOutcome.DUPLICATE
                  : EventOutcome.TERMINAL_CONFLICT;
          saveEvent(connection, eventHash, notification, orderId, outcome, repeated.isPresent());
          connection.commit();
          return ackFor(outcome);
        }
        if (order.status() == OrderStatus.FAILED
            || order.status() == OrderStatus.CANCELLED
            || order.status() == OrderStatus.EXPIRED
            || order.status() == OrderStatus.CREATED) {
          saveEvent(
              connection,
              eventHash,
              notification,
              orderId,
              EventOutcome.TERMINAL_CONFLICT,
              repeated.isPresent());
          connection.commit();
          return ackFor(EventOutcome.TERMINAL_CONFLICT);
        }

        if (notification.transactionNo() > 0) {
          VnPayProviderTransactionLock.acquire(connection, notification.transactionNo());
          Optional<UUID> transactionOwner =
              findTransactionOrder(connection, notification.transactionNo());
          if (transactionOwner.isPresent() && !transactionOwner.orElseThrow().equals(orderId)) {
            saveEvent(
                connection,
                eventHash,
                notification,
                orderId,
                EventOutcome.TRANSACTION_CONFLICT,
                repeated.isPresent());
            connection.commit();
            return ackFor(EventOutcome.TRANSACTION_CONFLICT);
          }
        }

        EventOutcome outcome = classify(notification);
        if (outcome == EventOutcome.SUCCEEDED) {
          OperationResult grant =
              creditLedger.grantPaidTopUp(connection, userId, orderId, order.paidCredits());
          if (grant.outcome() != Outcome.APPLIED && grant.outcome() != Outcome.ALREADY_APPLIED) {
            throw new SQLException("Verified VNPAY top-up credit grant did not apply");
          }
        }
        updateOrder(connection, order, notification, outcome);
        saveEvent(connection, eventHash, notification, orderId, outcome, repeated.isPresent());
        connection.commit();
        return ackFor(outcome);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to process the VNPAY TEST notification", exception);
    }
  }

  private static boolean matchesOrder(OrderSnapshot order, Notification notification) {
    return "VNPAY".equals(order.provider())
        && "TEST".equals(order.environment())
        && "VND".equals(order.currency())
        && order.amountVnd() == notification.amountVnd()
        && order.id().equals(parseOrderReference(notification.txnRef()))
        && notification.orderInfo().equals("Nap credit " + notification.txnRef());
  }

  private static EventOutcome classify(Notification notification) {
    if ("00".equals(notification.responseCode())
        && "00".equals(notification.transactionStatus())
        && notification.transactionNo() > 0) return EventOutcome.SUCCEEDED;
    if ("07".equals(notification.responseCode())
        || "07".equals(notification.transactionStatus())
        || "04".equals(notification.transactionStatus())) return EventOutcome.UNKNOWN;
    if ("00".equals(notification.responseCode())) {
      return "01".equals(notification.transactionStatus())
          ? EventOutcome.PENDING
          : EventOutcome.UNKNOWN;
    }
    return "02".equals(notification.transactionStatus())
            || "09".equals(notification.transactionStatus())
        ? EventOutcome.FAILED
        : EventOutcome.UNKNOWN;
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

  private static Optional<OrderSnapshot> findOrder(Connection connection, UUID userId, UUID orderId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, provider, environment, currency, amount_vnd, paid_credits, status,
                       provider_transaction_id
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
                row.getString("provider"),
                row.getString("environment"),
                row.getString("currency"),
                row.getLong("amount_vnd"),
                row.getLong("paid_credits"),
                OrderStatus.valueOf(row.getString("status")),
                (Long) row.getObject("provider_transaction_id")));
      }
    }
  }

  private static Optional<EventOutcome> findEvent(Connection connection, String eventHash)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT processing_outcome FROM vnpay_test_ipn_events WHERE event_hash = ? FOR UPDATE")) {
      select.setString(1, eventHash);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? Optional.of(EventOutcome.valueOf(row.getString("processing_outcome")))
            : Optional.empty();
      }
    }
  }

  private static Optional<UUID> findTransactionOrder(Connection connection, long transactionNo)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM credit_top_up_orders WHERE provider = 'VNPAY' AND environment = 'TEST' AND provider_transaction_id = ?")) {
      select.setLong(1, transactionNo);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(row.getObject("id", UUID.class)) : Optional.empty();
      }
    }
  }

  private static void updateOrder(
      Connection connection, OrderSnapshot order, Notification notification, EventOutcome outcome)
      throws SQLException {
    OrderStatus nextStatus =
        switch (outcome) {
          case SUCCEEDED -> OrderStatus.SUCCEEDED;
          case FAILED -> OrderStatus.FAILED;
          case PENDING -> OrderStatus.PENDING;
          default -> OrderStatus.UNKNOWN;
        };
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE credit_top_up_orders
                SET status = ?, checkout_url = NULL,
                    provider_transaction_id = CASE WHEN ? > 0 THEN ? ELSE provider_transaction_id END,
                    updated_at = clock_timestamp()
                WHERE id = ? AND status IN ('INITIATING', 'PENDING', 'UNKNOWN')
                """)) {
      update.setString(1, nextStatus.name());
      update.setLong(2, notification.transactionNo());
      update.setLong(3, notification.transactionNo());
      update.setObject(4, order.id());
      if (update.executeUpdate() != 1)
        throw new SQLException("VNPAY order state changed concurrently");
    }
  }

  private static void saveEvent(
      Connection connection,
      String eventHash,
      Notification notification,
      UUID orderId,
      EventOutcome outcome,
      boolean previouslyStored)
      throws SQLException {
    if (previouslyStored) {
      try (PreparedStatement update =
          connection.prepareStatement(
              "UPDATE vnpay_test_ipn_events SET processing_outcome = ? WHERE event_hash = ?")) {
        update.setString(1, outcome.name());
        update.setString(2, eventHash);
        if (update.executeUpdate() != 1) throw new SQLException("VNPAY IPN event disappeared");
      }
      return;
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO vnpay_test_ipn_events (
                    event_hash, txn_ref, transaction_no, amount_vnd, response_code,
                    transaction_status, credit_top_up_order_id, processing_outcome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_hash) DO NOTHING
                """)) {
      insert.setString(1, eventHash);
      insert.setString(2, notification.txnRef());
      insert.setLong(3, notification.transactionNo());
      insert.setLong(4, notification.amountVnd());
      insert.setString(5, notification.responseCode());
      insert.setString(6, notification.transactionStatus());
      insert.setObject(7, orderId);
      insert.setString(8, outcome.name());
      if (insert.executeUpdate() != 1)
        throw new SQLException("VNPAY IPN was concurrently processed");
    }
  }

  private static UUID parseOrderReference(String txnRef) {
    if (txnRef == null || !txnRef.matches("(?i)[0-9a-f]{32}")) return null;
    String canonical =
        txnRef.substring(0, 8)
            + "-"
            + txnRef.substring(8, 12)
            + "-"
            + txnRef.substring(12, 16)
            + "-"
            + txnRef.substring(16, 20)
            + "-"
            + txnRef.substring(20);
    try {
      return UUID.fromString(canonical);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static String fingerprint(Map<String, String> fields, String signature) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(VnPayTestSignatureCodec.canonical(fields).getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '|');
      return HexFormat.of().formatHex(digest.digest(signature.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Acknowledgement ackFor(EventOutcome outcome) {
    return switch (outcome) {
      case INVALID_SIGNATURE -> acknowledgement("97", "Invalid signature");
      case INVALID_NOTIFICATION -> acknowledgement("95", "Invalid request");
      case ORDER_NOT_FOUND -> acknowledgement("01", "Order not found");
      case ORDER_MISMATCH -> acknowledgement("04", "Invalid amount");
      case DUPLICATE, TERMINAL_CONFLICT, TRANSACTION_CONFLICT ->
          acknowledgement("02", "Order already confirmed");
      case ACCOUNT_INACTIVE -> acknowledgement("01", "Order not found");
      case SUCCEEDED, PENDING, FAILED, UNKNOWN -> acknowledgement("00", "Confirm Success");
    };
  }

  private static Acknowledgement acknowledgement(String code, String message) {
    return new Acknowledgement(code, message);
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
      String provider,
      String environment,
      String currency,
      long amountVnd,
      long paidCredits,
      OrderStatus status,
      Long providerTransactionId) {}

  private record Notification(
      String txnRef,
      String orderInfo,
      long transactionNo,
      long amountVnd,
      String responseCode,
      String transactionStatus) {
    private static Notification from(Map<String, String> fields, String tmnCode) {
      try {
        String txnRef = fields.get("vnp_TxnRef");
        String orderInfo = fields.get("vnp_OrderInfo");
        String response = fields.get("vnp_ResponseCode");
        String transactionStatus = fields.get("vnp_TransactionStatus");
        if (!tmnCode.equals(fields.get("vnp_TmnCode"))
            || !"VND".equals(fields.get("vnp_CurrCode"))
            || txnRef == null
            || txnRef.length() > 100
            || orderInfo == null
            || orderInfo.length() > 255
            || response == null
            || !response.matches("[0-9]{2}")
            || transactionStatus == null
            || !transactionStatus.matches("[0-9]{2}")) return null;
        long providerAmount = Long.parseLong(fields.get("vnp_Amount"));
        long transactionNo = Long.parseLong(fields.get("vnp_TransactionNo"));
        if (providerAmount < 100_000L
            || providerAmount > 5_000_000_000L
            || providerAmount % 100L != 0
            || transactionNo < 0) return null;
        return new Notification(
            txnRef, orderInfo, transactionNo, providerAmount / 100L, response, transactionStatus);
      } catch (RuntimeException exception) {
        return null;
      }
    }
  }

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

  private enum EventOutcome {
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

  public record Acknowledgement(String rspCode, String message) {}
}
