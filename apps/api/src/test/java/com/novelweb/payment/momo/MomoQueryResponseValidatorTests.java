package com.novelweb.payment.momo;

import static com.novelweb.payment.momo.MomoQueryResponseValidator.ExpectedOrder;
import static com.novelweb.payment.momo.MomoQueryResponseValidator.Outcome;
import static com.novelweb.payment.momo.MomoQueryResponseValidator.QueryResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MomoQueryResponseValidatorTests {

  private static final String PARTNER_CODE = "MOMOTEST123";
  private static final String QUERY_REQUEST_ID = "query-2026-0002";
  private static final String ORDER_ID = "order-2026-0001";
  private static final long AMOUNT_VND = 10_000L;
  private static final long TRANSACTION_ID = 987_654_321L;

  @Test
  void classifiesMatchingSuccessfulResponseOnlyWhenTransactionIdIsPresent() {
    assertThat(classify(response(0, TRANSACTION_ID))).isEqualTo(Outcome.SUCCEEDED);
    assertThat(classify(response(0, null))).isEqualTo(Outcome.UNKNOWN);
    assertThat(classify(response(0, 0L))).isEqualTo(Outcome.UNKNOWN);
  }

  @Test
  void preservesDocumentedPendingStates() {
    assertThat(classify(response(1000, null))).isEqualTo(Outcome.PENDING);
    assertThat(classify(response(7000, null))).isEqualTo(Outcome.PENDING);
    assertThat(classify(response(7002, null))).isEqualTo(Outcome.PENDING);
    assertThat(classify(response(9000, TRANSACTION_ID))).isEqualTo(Outcome.PENDING);
  }

  @Test
  void classifiesDocumentedTerminalPaymentFailures() {
    for (int resultCode : new int[] {1001, 1002, 1003, 1004, 1005, 1006, 1007, 1017}) {
      assertThat(classify(response(resultCode, null)))
          .as("resultCode %s", resultCode)
          .isEqualTo(Outcome.FAILED);
    }
  }

  @Test
  void leavesUnrecognizedAndRequestLevelCodesUnresolved() {
    assertThat(classify(response(42, null))).isEqualTo(Outcome.UNKNOWN);
    assertThat(classify(response(99, null))).isEqualTo(Outcome.UNKNOWN);
    assertThat(classify(response(123_456, null))).isEqualTo(Outcome.UNKNOWN);
  }

  @Test
  void rejectsMissingOrMismatchedOrderIdentityWithoutChangingPaymentOutcome() {
    assertThat(MomoQueryResponseValidator.classify(null, expectedOrder()))
        .isEqualTo(Outcome.INVALID_RESPONSE);
    assertThat(MomoQueryResponseValidator.classify(response(0, TRANSACTION_ID), null))
        .isEqualTo(Outcome.INVALID_RESPONSE);
    assertThat(classify(new QueryResponse(null, QUERY_REQUEST_ID, ORDER_ID, AMOUNT_VND, 1L, 0)))
        .isEqualTo(Outcome.INVALID_RESPONSE);
    assertThat(classify(new QueryResponse("OTHER", QUERY_REQUEST_ID, ORDER_ID, AMOUNT_VND, 1L, 0)))
        .isEqualTo(Outcome.INVALID_RESPONSE);
    assertThat(
            classify(new QueryResponse(PARTNER_CODE, "other-query", ORDER_ID, AMOUNT_VND, 1L, 0)))
        .isEqualTo(Outcome.INVALID_RESPONSE);
    assertThat(
            classify(
                new QueryResponse(
                    PARTNER_CODE, QUERY_REQUEST_ID, "other-order", AMOUNT_VND, 1L, 0)))
        .isEqualTo(Outcome.INVALID_RESPONSE);
    assertThat(
            classify(new QueryResponse(PARTNER_CODE, QUERY_REQUEST_ID, ORDER_ID, 10_001L, 1L, 0)))
        .isEqualTo(Outcome.INVALID_RESPONSE);
  }

  @Test
  void keepsTimeoutIndeterminateAndValidatesExpectedAmountBounds() {
    assertThat(MomoQueryResponseValidator.onTimeout()).isEqualTo(Outcome.UNKNOWN);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ExpectedOrder(PARTNER_CODE, QUERY_REQUEST_ID, ORDER_ID, 999L));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new ExpectedOrder(PARTNER_CODE, QUERY_REQUEST_ID, ORDER_ID, 50_000_001L));
  }

  private static Outcome classify(QueryResponse response) {
    return MomoQueryResponseValidator.classify(response, expectedOrder());
  }

  private static QueryResponse response(Integer resultCode, Long transactionId) {
    return new QueryResponse(
        PARTNER_CODE, QUERY_REQUEST_ID, ORDER_ID, AMOUNT_VND, transactionId, resultCode);
  }

  private static ExpectedOrder expectedOrder() {
    return new ExpectedOrder(PARTNER_CODE, QUERY_REQUEST_ID, ORDER_ID, AMOUNT_VND);
  }
}
