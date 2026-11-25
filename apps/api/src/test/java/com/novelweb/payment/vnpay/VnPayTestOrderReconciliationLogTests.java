package com.novelweb.payment.vnpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelweb.payment.credit.CreditLedgerService;
import com.novelweb.payment.vnpay.VnPayTestQueryDrClient.FailureCode;
import com.novelweb.payment.vnpay.VnPayTestQueryDrClient.Outcome;
import com.novelweb.payment.vnpay.VnPayTestQueryDrClient.QueryResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class VnPayTestOrderReconciliationLogTests {

  private static final String DATABASE_FAILURE_SENTINEL = "database-secret-60e3b4";
  private static final String TMN_CODE = "ABCD1234";

  @Test
  void scheduledBatchDatabaseFailureLogsOnlyGenericMessage(CapturedOutput output) throws Exception {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection())
        .thenThrow(new SQLException("Database password " + DATABASE_FAILURE_SENTINEL));
    VnPayTestOrderReconciliationService service = service(dataSource, (request) -> null);

    service.runScheduledBatch();

    assertSafeFailureLog(output, "VNPAY TEST reconciliation batch failed");
  }

  @Test
  void resultPersistenceFailureLogsOnlyGenericMessage(CapturedOutput output) throws Exception {
    DataSource dataSource = mock(DataSource.class);
    Connection claimConnection = mock(Connection.class);
    PreparedStatement select = mock(PreparedStatement.class);
    PreparedStatement update = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);
    UUID orderId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    when(dataSource.getConnection())
        .thenReturn(claimConnection)
        .thenThrow(new SQLException("Database password " + DATABASE_FAILURE_SENTINEL));
    when(claimConnection.prepareStatement(anyString()))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0, String.class);
              return sql.startsWith("SELECT") ? select : update;
            });
    when(select.executeQuery()).thenReturn(rows);
    when(rows.next()).thenReturn(true, false);
    when(rows.getObject("id", UUID.class)).thenReturn(orderId);
    when(rows.getObject("user_id", UUID.class)).thenReturn(userId);
    when(rows.getLong("amount_vnd")).thenReturn(1_000L);
    when(rows.getObject("created_at", OffsetDateTime.class))
        .thenReturn(OffsetDateTime.parse("2026-11-26T00:00:00Z"));
    when(rows.getInt("reconciliation_attempt_count")).thenReturn(0);
    when(update.executeUpdate()).thenReturn(1);

    VnPayTestOrderReconciliationService service =
        service(
            dataSource, request -> new QueryResult(Outcome.UNKNOWN, null, FailureCode.TRANSPORT));

    assertEquals(1, service.reconcileDueBatch());

    assertSafeFailureLog(output, "VNPAY TEST reconciliation result could not be persisted");
  }

  private static VnPayTestOrderReconciliationService service(
      DataSource dataSource, VnPayTestOrderReconciliationService.OrderQuery query) {
    return new VnPayTestOrderReconciliationService(
        dataSource, new CreditLedgerService(), TMN_CODE, "127.0.0.1", query, 1);
  }

  private static void assertSafeFailureLog(CapturedOutput output, String expectedMessage) {
    String logs = output.getAll();
    assertTrue(logs.contains(expectedMessage));
    assertFalse(logs.contains(DATABASE_FAILURE_SENTINEL));
    assertFalse(logs.contains("Database password"));
    assertFalse(logs.contains("SQLException"));
    assertFalse(logs.contains("at com.novelweb"));
  }
}
