package com.novelweb.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class VnPayTestQueryDrClientTests {

  private static final String HASH_SECRET = "synthetic-vnpay-secret-only";
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final UUID ORDER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void signsQueryAndAcceptsOnlyAMatchingSignedSuccessfulPayment() throws Exception {
    AtomicReference<byte[]> requestBody = new AtomicReference<>();
    Map<String, String> response = validResponse("00", "00");
    response.put(
        "vnp_SecureHash", VnPayTestSignatureCodec.signQueryDrResponse(response, HASH_SECRET));
    VnPayTestQueryDrClient client = client(requestBody, response);

    VnPayTestQueryDrClient.QueryResult result = client.query(request());

    assertThat(result)
        .isEqualTo(
            new VnPayTestQueryDrClient.QueryResult(
                VnPayTestQueryDrClient.Outcome.SUCCEEDED,
                1_234_567L,
                VnPayTestQueryDrClient.FailureCode.NONE));
    var body = JSON.readTree(requestBody.get());
    assertThat(body.get("vnp_TransactionDate").asString()).isEqualTo("20261125120000");
    assertThat(body.get("vnp_Command").asString()).isEqualTo("querydr");
    assertThat(body.get("vnp_SecureHash").asString()).hasSize(128);
  }

  @Test
  void doesNotClassifyAnUnverifiedOrMismatchedResponse() throws Exception {
    AtomicReference<byte[]> requestBody = new AtomicReference<>();
    Map<String, String> invalidSignature = validResponse("00", "00");
    invalidSignature.put("vnp_SecureHash", "0".repeat(128));
    var unverified = client(requestBody, invalidSignature).query(request());
    assertThat(unverified.failureCode())
        .isEqualTo(VnPayTestQueryDrClient.FailureCode.UNVERIFIED_RESPONSE);

    Map<String, String> wrongOrder = validResponse("00", "00");
    wrongOrder.put("vnp_TxnRef", "22222222222242228222222222222222");
    wrongOrder.put(
        "vnp_SecureHash", VnPayTestSignatureCodec.signQueryDrResponse(wrongOrder, HASH_SECRET));
    var mismatched = client(requestBody, wrongOrder).query(request());
    assertThat(mismatched.failureCode())
        .isEqualTo(VnPayTestQueryDrClient.FailureCode.ORDER_MISMATCH);
  }

  @Test
  void keepsPendingProviderStatePendingAndRejectsCallsToNonSandboxEndpoints() throws Exception {
    AtomicReference<byte[]> requestBody = new AtomicReference<>();
    Map<String, String> pending = validResponse("00", "01");
    pending.put("vnp_TransactionNo", "0");
    pending.put(
        "vnp_SecureHash", VnPayTestSignatureCodec.signQueryDrResponse(pending, HASH_SECRET));
    var result = client(requestBody, pending).query(request());
    assertThat(result.outcome()).isEqualTo(VnPayTestQueryDrClient.Outcome.PENDING);

    var transport = VnPayTestQueryDrTransport.jdk();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                transport.post(
                    URI.create("https://example.com/query"), new byte[0], Duration.ofSeconds(35)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsImpossibleTransactionDatesAndMalformedServerAddressesBeforeTransport() {
    VnPayTestQueryDrClient client =
        new VnPayTestQueryDrClient(
            settings(),
            JSON,
            (endpoint, body, timeout) -> {
              throw new AssertionError("invalid query must not call VNPAY");
            });

    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                client.query(
                    new VnPayTestQueryDrClient.QueryRequest(
                        "ABCD1234",
                        "request-1",
                        ORDER_ID,
                        1_000L,
                        "20260230120000",
                        "20261125120500",
                        "127.0.0.1")));
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                client.query(
                    new VnPayTestQueryDrClient.QueryRequest(
                        "ABCD1234",
                        "request-1",
                        ORDER_ID,
                        1_000L,
                        "20261125120000",
                        "20261125120500",
                        "999.999.999.999")));
  }

  private static VnPayTestQueryDrClient client(
      AtomicReference<byte[]> requestBody, Map<String, String> response) throws Exception {
    byte[] responseBytes = JSON.writeValueAsBytes(response);
    VnPayTestQueryDrTransport transport =
        (endpoint, body, timeout) -> {
          assertThat(endpoint).isEqualTo(VnPayTestQueryDrTransport.QUERY_ENDPOINT);
          assertThat(timeout).isEqualTo(Duration.ofSeconds(35));
          requestBody.set(body.clone());
          return new VnPayTestQueryDrTransport.Response(200, responseBytes);
        };
    return new VnPayTestQueryDrClient(settings(), JSON, transport);
  }

  private static Map<String, String> validResponse(String responseCode, String transactionStatus) {
    Map<String, String> response = new LinkedHashMap<>();
    response.put("vnp_ResponseId", "response-1");
    response.put("vnp_Command", "querydr");
    response.put("vnp_ResponseCode", responseCode);
    response.put("vnp_Message", "Confirm Success");
    response.put("vnp_TmnCode", "ABCD1234");
    response.put("vnp_TxnRef", VnPayTestGatewayClient.compactOrderReference(ORDER_ID));
    response.put("vnp_Amount", "100000");
    response.put("vnp_BankCode", "NCB");
    response.put("vnp_PayDate", "20261125120100");
    response.put("vnp_TransactionNo", "1234567");
    response.put("vnp_TransactionType", "01");
    response.put("vnp_TransactionStatus", transactionStatus);
    response.put("vnp_OrderInfo", VnPayTestQueryDrClient.orderInfo(ORDER_ID));
    response.put("vnp_PromotionCode", "");
    response.put("vnp_PromotionAmount", "");
    return response;
  }

  private static VnPayTestQueryDrClient.QueryRequest request() {
    return new VnPayTestQueryDrClient.QueryRequest(
        "ABCD1234",
        "22222222222242228222222222222222",
        ORDER_ID,
        1_000L,
        "20261125120000",
        "20261125120500",
        "127.0.0.1");
  }

  private static VnPayTestGatewaySettings settings() {
    return new VnPayTestGatewaySettings(
        "ABCD1234",
        HASH_SECRET,
        VnPayTestGatewaySettings.SANDBOX_PAYMENT_URI,
        URI.create("https://novel.example" + VnPayTestGatewaySettings.IPN_PATH),
        URI.create("https://novel.example/app/payment/result"));
  }
}
