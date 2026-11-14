package com.novelweb.payment.momo;

import java.util.Objects;

/** Validates an offline MoMo query response against the persisted payment-order snapshot. */
public final class MomoQueryResponseValidator {

  private MomoQueryResponseValidator() {}

  /**
   * Classifies only documented transaction outcomes. Unknown result codes and missing success
   * transaction IDs remain unresolved so callers cannot mistake them for a definitive failure or
   * grant credit from incomplete data.
   */
  public static Outcome classify(QueryResponse response, ExpectedOrder expectedOrder) {
    if (response == null || expectedOrder == null || !response.hasRequiredFields()) {
      return Outcome.INVALID_RESPONSE;
    }

    if (!expectedOrder.partnerCode().equals(response.partnerCode())
        || !expectedOrder.requestId().equals(response.requestId())
        || !expectedOrder.orderId().equals(response.orderId())
        || !expectedOrder.amountVnd().equals(response.amountVnd())) {
      return Outcome.INVALID_RESPONSE;
    }

    return switch (response.resultCode()) {
      case 0 ->
          response.transactionId() != null && response.transactionId() > 0
              ? Outcome.SUCCEEDED
              : Outcome.UNKNOWN;
      case 1000, 7000, 7002, 9000 -> Outcome.PENDING;
      case 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1017 -> Outcome.FAILED;
      default -> Outcome.UNKNOWN;
    };
  }

  /** A network timeout is indeterminate; it does not mean MoMo failed the payment. */
  public static Outcome onTimeout() {
    return Outcome.UNKNOWN;
  }

  public enum Outcome {
    INVALID_RESPONSE,
    SUCCEEDED,
    FAILED,
    PENDING,
    UNKNOWN
  }

  /** Fields returned by the documented MoMo transaction-query response. */
  public record QueryResponse(
      String partnerCode,
      String requestId,
      String orderId,
      Long amountVnd,
      Long transactionId,
      Integer resultCode) {

    private boolean hasRequiredFields() {
      return partnerCode != null
          && requestId != null
          && orderId != null
          && amountVnd != null
          && resultCode != null;
    }
  }

  /** Immutable values captured for the order and the specific status-query request. */
  public record ExpectedOrder(
      String partnerCode, String requestId, String orderId, Long amountVnd) {

    public ExpectedOrder {
      requireNonBlank(partnerCode, "partnerCode is required");
      requireNonBlank(requestId, "query requestId is required");
      requireNonBlank(orderId, "orderId is required");
      Objects.requireNonNull(amountVnd, "amountVnd is required");
      if (amountVnd < 1_000L || amountVnd > 50_000_000L) {
        throw new IllegalArgumentException(
            "MoMo wallet amount must be between 1,000 and 50,000,000 VND");
      }
    }

    private static void requireNonBlank(String value, String message) {
      if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    }
  }
}
