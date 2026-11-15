package com.novelweb.payment.momo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelweb.payment.credit.CreditLedgerService;
import com.novelweb.payment.momo.MomoQueryResponseValidator.Outcome;
import com.novelweb.payment.momo.MomoTestGatewayClient.FailureCode;
import com.novelweb.payment.momo.MomoTestGatewayClient.QueryResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class MomoTestOrderReconciliationLogTests {

  private static final String PARTNER_CODE = "MOMOTEST123";
  private static final String DATABASE_FAILURE_SENTINEL = "database-secret-a19c";

  @Test
  void scheduledBatchDatabaseFailureLogsOnlyGenericMessage(CapturedOutput output) throws Exception {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection())
        .thenThrow(new SQLException("Database password " + DATABASE_FAILURE_SENTINEL));
    MomoTestOrderReconciliationService service =
        new MomoTestOrderReconciliationService(
            dataSource, new CreditLedgerService(), PARTNER_CODE, (request, amount) -> null, 1);

    service.runScheduledBatch();

    String logOutput = output.getAll();
    assertTrue(logOutput.contains("MoMo TEST reconciliation batch failed"));
    assertFalse(logOutput.contains(DATABASE_FAILURE_SENTINEL));
    assertFalse(logOutput.contains("Database password"));
    assertFalse(logOutput.contains("SQLException"));
    assertFalse(logOutput.contains("at com.novelweb"));
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
    when(rows.getInt("reconciliation_attempt_count")).thenReturn(0);
    when(update.executeUpdate()).thenReturn(1);

    MomoTestOrderReconciliationService service =
        new MomoTestOrderReconciliationService(
            dataSource,
            new CreditLedgerService(),
            PARTNER_CODE,
            (request, amount) -> new QueryResult(Outcome.UNKNOWN, null, FailureCode.TRANSPORT),
            1);

    assertEquals(1, service.reconcileDueBatch());

    String logOutput = output.getAll();
    assertTrue(logOutput.contains("MoMo TEST reconciliation result could not be persisted"));
    assertFalse(logOutput.contains(DATABASE_FAILURE_SENTINEL));
    assertFalse(logOutput.contains("Database password"));
    assertFalse(logOutput.contains("SQLException"));
    assertFalse(logOutput.contains("at com.novelweb"));
  }
}
