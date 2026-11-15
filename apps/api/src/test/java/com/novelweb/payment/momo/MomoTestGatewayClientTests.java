package com.novelweb.payment.momo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.novelweb.platform.ProviderCallMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MomoTestGatewayClientTests {

  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final String PARTNER_CODE = "MOMOTEST123";
  private static final String ACCESS_KEY = "synthetic-access";
  private static final String SECRET_KEY = "synthetic-secret-only";
  private static final String IPN_URL = "https://novel.example/api/v1/payments/momo/ipn";
  private static final String REDIRECT_URL = "https://novel.example/app/payment/result";
  private static final String PAY_URL =
      "https://test-payment.momo.vn/v2/gateway/pay?t=synthetic-ticket&s=synthetic-signature";

  @Test
  void createsCheckoutOnlyAfterSignedResponseAndExactTestOrderMatch() throws Exception {
    var response = signedCreateResponse(PARTNER_CODE, "request-1", "order-1", 1_000L, 0, PAY_URL);
    AtomicReference<URI> endpoint = new AtomicReference<>();
    AtomicReference<JsonNode> postedBody = new AtomicReference<>();
    AtomicReference<Duration> timeout = new AtomicReference<>();
    AtomicInteger sends = new AtomicInteger();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProviderCallMetrics metrics = new ProviderCallMetrics(registry);
    MomoTestHttpTransport transport =
        (uri, body, requestTimeout) -> {
          sends.incrementAndGet();
          endpoint.set(uri);
          postedBody.set(JSON.readTree(body));
          timeout.set(requestTimeout);
          return new MomoTestHttpTransport.Response(
              200,
              JSON.writeValueAsBytes(
                  Map.of(
                      "partnerCode",
                      response.partnerCode(),
                      "requestId",
                      response.requestId(),
                      "orderId",
                      response.orderId(),
                      "amount",
                      response.amountVnd(),
                      "message",
                      response.message(),
                      "responseTime",
                      response.responseTime(),
                      "resultCode",
                      response.resultCode(),
                      "payUrl",
                      response.payUrl(),
                      "signature",
                      response.signature())));
        };

    MomoTestGatewayClient client = new MomoTestGatewayClient(settings(), JSON, transport, metrics);
    MomoTestGatewayClient.CreateResult result = client.createWallet(createRequest());

    assertThat(result.state()).isEqualTo(MomoTestGatewayClient.CreateState.READY);
    assertThat(result.payUrl()).isEqualTo(PAY_URL);
    assertThat(result.toString()).doesNotContain(PAY_URL);
    assertThat(result.failureCode()).isEqualTo(MomoTestGatewayClient.FailureCode.NONE);
    assertThat(endpoint.get())
        .isEqualTo(URI.create("https://test-payment.momo.vn/v2/gateway/api/create"));
    assertThat(timeout.get()).isEqualTo(Duration.ofSeconds(35));
    assertThat(sends.get()).isEqualTo(1);
    assertThat(postedBody.get().path("requestType").stringValue()).isEqualTo("captureWallet");
    assertThat(postedBody.get().path("partnerCode").stringValue()).isEqualTo(PARTNER_CODE);
    assertThat(postedBody.get().path("signature").stringValue())
        .isEqualTo(MomoSignatureCodec.signCreateWallet(createRequest(), ACCESS_KEY, SECRET_KEY));
    assertThat(JSON.writeValueAsString(postedBody.get()))
        .doesNotContain(ACCESS_KEY, SECRET_KEY, "accessKey");
    assertEquals(
        1,
        registry
            .get("novel.provider.request.duration")
            .tags("provider", "momo_test", "operation", "create_order", "outcome", "success")
            .timer()
            .count());
  }

  @Test
  void buildsTopUpCreateRequestOnlyFromSavedAmountAndOpaqueOrderIds() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    AtomicReference<JsonNode> postedBody = new AtomicReference<>();
    MomoTestHttpTransport transport =
        (endpoint, body, timeout) -> {
          JsonNode request = JSON.readTree(body);
          postedBody.set(request);
          MomoSignatureCodec.CreateWalletResponse response;
          try {
            response =
                signedCreateResponse(
                    request.path("partnerCode").stringValue(),
                    request.path("requestId").stringValue(),
                    request.path("orderId").stringValue(),
                    request.path("amount").longValue(),
                    0,
                    PAY_URL);
          } catch (GeneralSecurityException exception) {
            throw new IOException(exception);
          }
          return new MomoTestHttpTransport.Response(
              200,
              JSON.writeValueAsBytes(
                  Map.of(
                      "partnerCode", response.partnerCode(),
                      "requestId", response.requestId(),
                      "orderId", response.orderId(),
                      "amount", response.amountVnd(),
                      "message", response.message(),
                      "responseTime", response.responseTime(),
                      "resultCode", response.resultCode(),
                      "payUrl", response.payUrl(),
                      "signature", response.signature())));
        };

    MomoTestGatewayClient.CreateResult result =
        client(transport).createCreditTopUp(1_000, orderId, requestId);

    assertThat(result.state()).isEqualTo(MomoTestGatewayClient.CreateState.READY);
    assertThat(result.payUrl()).isEqualTo(PAY_URL);
    assertThat(postedBody.get().path("amount").longValue()).isEqualTo(1_000);
    assertThat(postedBody.get().path("orderId").stringValue()).isEqualTo(orderId.toString());
    assertThat(postedBody.get().path("requestId").stringValue()).isEqualTo(requestId.toString());
    assertThat(postedBody.get().path("orderInfo").stringValue())
        .isEqualTo("Novel Web credit top-up");
    assertThat(postedBody.get().path("autoCapture").booleanValue()).isTrue();
    assertThat(postedBody.get().path("extraData").stringValue()).isEmpty();
    assertThat(postedBody.get().path("ipnUrl").stringValue()).isEqualTo(IPN_URL);
    assertThat(postedBody.get().path("redirectUrl").stringValue()).isEqualTo(REDIRECT_URL);
  }

  @Test
  void createTimeoutOrBadResponseStaysUnknownAndIsNeverAutomaticallyRetried() {
    AtomicInteger sends = new AtomicInteger();
    MomoTestHttpTransport timeoutTransport =
        (endpoint, body, timeout) -> {
          sends.incrementAndGet();
          throw new IOException("synthetic private transport detail");
        };

    MomoTestGatewayClient.CreateResult result =
        client(timeoutTransport).createWallet(createRequest());

    assertThat(result.state()).isEqualTo(MomoTestGatewayClient.CreateState.UNKNOWN);
    assertThat(result.payUrl()).isNull();
    assertThat(result.failureCode()).isEqualTo(MomoTestGatewayClient.FailureCode.TRANSPORT);
    assertThat(sends.get()).isEqualTo(1);
  }

  @Test
  void recordsMomoTestTransportTimeoutAsCoarseFailureWithoutSensitiveTags() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProviderCallMetrics metrics = new ProviderCallMetrics(registry);
    MomoTestHttpTransport timeoutTransport =
        (endpoint, body, timeout) -> {
          throw new java.net.http.HttpTimeoutException("synthetic private timeout detail");
        };
    MomoTestGatewayClient client =
        new MomoTestGatewayClient(settings(), JSON, timeoutTransport, metrics);

    MomoTestGatewayClient.CreateResult result = client.createWallet(createRequest());

    assertThat(result.state()).isEqualTo(MomoTestGatewayClient.CreateState.UNKNOWN);
    assertThat(result.failureCode()).isEqualTo(MomoTestGatewayClient.FailureCode.TRANSPORT);
    assertEquals(
        1,
        registry
            .get("novel.provider.request.duration")
            .tags(
                "provider", "momo_test", "operation", "create_order", "outcome", "transport_error")
            .timer()
            .count());
    assertThat(registry.getMeters().toString())
        .doesNotContain(ACCESS_KEY, SECRET_KEY, "request-1", "order-1", IPN_URL, PAY_URL);
    assertThat(registry.getMeters().stream())
        .allMatch(
            meter ->
                meter.getId().getTags().stream()
                    .allMatch(
                        tag -> Set.of("provider", "operation", "outcome").contains(tag.getKey())));
  }

  @Test
  void refusesMismatchedOrUnsignedCreateResponseWithoutReturningItsUrl() throws Exception {
    var response =
        signedCreateResponse(PARTNER_CODE, "other-request", "order-1", 1_000L, 0, PAY_URL);
    MomoTestHttpTransport transport =
        (endpoint, body, timeout) ->
            new MomoTestHttpTransport.Response(200, JSON.writeValueAsBytes(response));

    MomoTestGatewayClient.CreateResult result = client(transport).createWallet(createRequest());

    assertThat(result.state()).isEqualTo(MomoTestGatewayClient.CreateState.UNKNOWN);
    assertThat(result.payUrl()).isNull();
    assertThat(result.failureCode())
        .isEqualTo(MomoTestGatewayClient.FailureCode.UNVERIFIED_RESPONSE);
  }

  @Test
  void sendsSignedQueryAndClassifiesOnlyTheMatchingSnapshot() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProviderCallMetrics metrics = new ProviderCallMetrics(registry);
    AtomicReference<URI> endpoint = new AtomicReference<>();
    AtomicReference<JsonNode> postedBody = new AtomicReference<>();
    MomoTestHttpTransport transport =
        (uri, body, timeout) -> {
          endpoint.set(uri);
          postedBody.set(JSON.readTree(body));
          return new MomoTestHttpTransport.Response(
              200,
              """
                  {"partnerCode":"MOMOTEST123","requestId":"query-1","orderId":"order-1",
                   "amount":1000,"transId":987654,"resultCode":0}
                  """
                  .getBytes(StandardCharsets.UTF_8));
        };
    var request = new MomoSignatureCodec.QueryOrderRequest(PARTNER_CODE, "query-1", "order-1");

    MomoTestGatewayClient.QueryResult result =
        new MomoTestGatewayClient(settings(), JSON, transport, metrics).queryOrder(request, 1_000L);

    assertThat(result.outcome()).isEqualTo(MomoQueryResponseValidator.Outcome.SUCCEEDED);
    assertThat(result.transactionId()).isEqualTo(987_654L);
    assertThat(endpoint.get())
        .isEqualTo(URI.create("https://test-payment.momo.vn/v2/gateway/api/query"));
    assertThat(postedBody.get().path("signature").stringValue())
        .isEqualTo(MomoSignatureCodec.signQueryOrder(request, ACCESS_KEY, SECRET_KEY));
    assertThat(postedBody.get().has("amount")).isFalse();
    assertThat(postedBody.get().has("accessKey")).isFalse();
    assertEquals(
        1,
        registry
            .get("novel.provider.request.duration")
            .tags("provider", "momo_test", "operation", "query_order", "outcome", "success")
            .timer()
            .count());
  }

  @Test
  void queryTransportHttpAndUnknownResponsesNeverBecomePaymentSuccess() throws Exception {
    var request = new MomoSignatureCodec.QueryOrderRequest(PARTNER_CODE, "query-1", "order-1");
    MomoTestHttpTransport unavailable =
        (endpoint, body, timeout) -> new MomoTestHttpTransport.Response(503, new byte[0]);

    MomoTestGatewayClient.QueryResult httpResult = client(unavailable).queryOrder(request, 1_000L);
    assertThat(httpResult.outcome()).isEqualTo(MomoQueryResponseValidator.Outcome.UNKNOWN);
    assertThat(httpResult.transactionId()).isNull();
    assertThat(httpResult.failureCode()).isEqualTo(MomoTestGatewayClient.FailureCode.HTTP_STATUS);

    MomoTestHttpTransport unknownCode =
        (endpoint, body, timeout) ->
            new MomoTestHttpTransport.Response(
                200,
                """
                    {"partnerCode":"MOMOTEST123","requestId":"query-1","orderId":"order-1",
                     "amount":1000,"transId":987654,"resultCode":424242}
                    """
                    .getBytes(StandardCharsets.UTF_8));
    MomoTestGatewayClient.QueryResult unknownResult =
        client(unknownCode).queryOrder(request, 1_000L);
    assertThat(unknownResult.outcome()).isEqualTo(MomoQueryResponseValidator.Outcome.UNKNOWN);
    assertThat(unknownResult.failureCode()).isEqualTo(MomoTestGatewayClient.FailureCode.NONE);
  }

  @Test
  void rejectsInvalidOrderOrCallbackBeforeTransportIsInvoked() {
    AtomicInteger sends = new AtomicInteger();
    MomoTestHttpTransport transport =
        (endpoint, body, timeout) -> {
          sends.incrementAndGet();
          return new MomoTestHttpTransport.Response(200, new byte[0]);
        };
    MomoSignatureCodec.CreateWalletRequest wrongCallback =
        new MomoSignatureCodec.CreateWalletRequest(
            1_000L,
            "",
            "https://evil.example/ipn",
            "order-1",
            "Credit top-up",
            PARTNER_CODE,
            REDIRECT_URL,
            "request-1");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> client(transport).createWallet(wrongCallback));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                client(transport)
                    .queryOrder(
                        new MomoSignatureCodec.QueryOrderRequest(
                            "OTHER-PARTNER", "query-1", "order-1"),
                        1_000L));
    assertThat(sends.get()).isZero();
  }

  @Test
  void acceptsOnlyTheExactCreateEndpointAndRedactsServerCredentials() {
    assertThat(MomoTestCreateUrlPolicy.allows("https://test-payment.momo.vn/v2/gateway/api/create"))
        .isTrue();
    assertThat(
            MomoTestCreateUrlPolicy.allows(
                "https://test-payment.momo.vn.attacker.example/v2/gateway/api/create"))
        .isFalse();
    assertThat(
            MomoTestCreateUrlPolicy.allows(
                "https://test-payment.momo.vn:444/v2/gateway/api/create"))
        .isFalse();
    assertThat(MomoTestCreateUrlPolicy.allows("https://test-payment.momo.vn/v2/gateway/api/query"))
        .isFalse();
    assertThat(settings().toString()).doesNotContain(ACCESS_KEY, SECRET_KEY);
  }

  @Test
  void realTransportRejectsOtherHostsAndSubThirtySecondTimeoutBeforeNetwork() {
    MomoTestHttpTransport transport = MomoTestHttpTransport.jdk();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                transport.post(
                    URI.create("https://attacker.example/v2/gateway/api/create"),
                    new byte[0],
                    Duration.ofSeconds(30)));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                transport.post(
                    URI.create("https://test-payment.momo.vn/v2/gateway/api/query"),
                    new byte[0],
                    Duration.ofSeconds(29)));
  }

  private static MomoTestGatewayClient client(MomoTestHttpTransport transport) {
    return new MomoTestGatewayClient(settings(), JSON, transport);
  }

  private static MomoTestGatewaySettings settings() {
    return MomoTestGatewaySettings.fromEnvironment(
        Map.of(
            "MOMO_ENV",
            "test",
            "MOMO_TEST_PARTNER_CODE",
            PARTNER_CODE,
            "MOMO_TEST_ACCESS_KEY",
            ACCESS_KEY,
            "MOMO_TEST_SECRET_KEY",
            SECRET_KEY,
            "MOMO_TEST_BASE_URL",
            "https://test-payment.momo.vn",
            "MOMO_TEST_IPN_URL",
            IPN_URL,
            "MOMO_TEST_REDIRECT_URL",
            REDIRECT_URL));
  }

  private static MomoSignatureCodec.CreateWalletRequest createRequest() {
    return new MomoSignatureCodec.CreateWalletRequest(
        1_000L, "", IPN_URL, "order-1", "Credit top-up", PARTNER_CODE, REDIRECT_URL, "request-1");
  }

  private static MomoSignatureCodec.CreateWalletResponse signedCreateResponse(
      String partner, String request, String order, long amount, int resultCode, String payUrl)
      throws GeneralSecurityException {
    var unsigned =
        new MomoSignatureCodec.CreateWalletResponse(
            partner,
            request,
            order,
            amount,
            "Successful.",
            1_763_164_801_234L,
            resultCode,
            payUrl,
            "");
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature =
        HexFormat.of()
            .formatHex(
                mac.doFinal(
                    MomoSignatureCodec.canonicalCreateWalletResponse(unsigned, ACCESS_KEY)
                        .getBytes(StandardCharsets.UTF_8)));
    return new MomoSignatureCodec.CreateWalletResponse(
        partner,
        request,
        order,
        amount,
        unsigned.message(),
        unsigned.responseTime(),
        resultCode,
        payUrl,
        signature);
  }
}
