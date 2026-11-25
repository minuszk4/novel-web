package com.novelweb.payment.vnpay;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Serializes a VNPAY transaction ID decision across IPN and QueryDR settlement. */
final class VnPayProviderTransactionLock {

  private static final int LOCK_NAMESPACE = 731_943;

  private VnPayProviderTransactionLock() {}

  static void acquire(Connection connection, long transactionId) throws SQLException {
    if (transactionId <= 0L) {
      throw new IllegalArgumentException("provider transaction ID must be positive");
    }
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(?, hashint8(?))")) {
      lock.setInt(1, LOCK_NAMESPACE);
      lock.setLong(2, transactionId);
      try (ResultSet ignored = lock.executeQuery()) {
        if (!ignored.next())
          throw new SQLException("VNPAY provider transaction lock was not acquired");
      }
    }
  }
}
