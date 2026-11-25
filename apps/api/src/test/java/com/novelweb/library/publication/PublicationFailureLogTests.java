package com.novelweb.library.publication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class PublicationFailureLogTests {

  private static final String PRIVATE_SENTINEL = "private-row-detail-77a9c0";

  @Test
  void guardRejectionLogsValidatedStateWithoutDatabaseMessage(CapturedOutput output) {
    SQLException exception =
        new SQLException("constraint detail contains " + PRIVATE_SENTINEL, "23514");

    PublicationFailureLog.guardRejected(PublicationFailureLog.Operation.RELEASE, exception);

    assertSafeLog(output, "guard rejected", "RELEASE", "23514");
  }

  @Test
  void transactionFailureLogsValidatedStateWithoutThrowable(CapturedOutput output) {
    SQLException exception = new SQLException("query detail contains " + PRIVATE_SENTINEL, "08006");

    PublicationFailureLog.transactionFailed(
        PublicationFailureLog.Operation.CHAPTER_RELEASE, exception);

    assertSafeLog(output, "transaction failed", "CHAPTER_RELEASE", "08006");
  }

  @Test
  void malformedSqlStateIsReplacedWithUnknown(CapturedOutput output) {
    SQLException exception = new SQLException("unsafe " + PRIVATE_SENTINEL, "23514\nDETAIL");

    PublicationFailureLog.transactionFailed(
        PublicationFailureLog.Operation.REQUEST_SUBMISSION, exception);

    assertSafeLog(output, "transaction failed", "REQUEST_SUBMISSION", "unknown");
    assertFalse(output.getAll().contains("23514"));
  }

  private static void assertSafeLog(
      CapturedOutput output, String event, String operation, String sqlState) {
    String logs = output.getAll();
    assertTrue(logs.contains(event));
    assertTrue(logs.contains(operation));
    assertTrue(logs.contains("sqlState=" + sqlState));
    assertFalse(logs.contains(PRIVATE_SENTINEL));
    assertFalse(logs.contains("constraint detail"));
    assertFalse(logs.contains("query detail"));
    assertFalse(logs.contains("SQLException"));
    assertFalse(logs.contains("at com.novelweb"));
  }
}
