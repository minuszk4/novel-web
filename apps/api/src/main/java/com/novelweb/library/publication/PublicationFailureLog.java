package com.novelweb.library.publication;

import java.sql.SQLException;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes publication database failures without exposing database messages or row details. */
final class PublicationFailureLog {

  private static final Logger LOGGER = LoggerFactory.getLogger(PublicationFailureLog.class);
  private static final Pattern SQL_STATE_PATTERN = Pattern.compile("[0-9A-Z]{5}");

  private PublicationFailureLog() {}

  static void guardRejected(Operation operation, SQLException exception) {
    LOGGER.warn(
        "Publication database guard rejected operation={} sqlState={}",
        Objects.requireNonNull(operation, "operation is required"),
        safeSqlState(exception));
  }

  static void transactionFailed(Operation operation, SQLException exception) {
    LOGGER.error(
        "Publication database transaction failed operation={} sqlState={}",
        Objects.requireNonNull(operation, "operation is required"),
        safeSqlState(exception));
  }

  static void staleApprovalScope(boolean snapshotMatches, boolean currentApproval) {
    LOGGER.warn(
        "Publication release scope is stale; snapshotMatches={}, currentApproval={}",
        snapshotMatches,
        currentApproval);
  }

  private static String safeSqlState(SQLException exception) {
    if (exception == null) {
      return "unknown";
    }
    String sqlState = exception.getSQLState();
    return sqlState != null && SQL_STATE_PATTERN.matcher(sqlState).matches() ? sqlState : "unknown";
  }

  enum Operation {
    RELEASE,
    SCHEDULE,
    CHAPTER_RELEASE,
    REQUEST_SUBMISSION
  }
}
