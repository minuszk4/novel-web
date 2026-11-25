package com.novelweb.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class VnPayTestGatewayClientTests {

  private static final String HASH_SECRET = "synthetic-vnpay-secret-only";
  private static final UUID ORDER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Test
  void buildsOfficialSandboxUrlWithVndMinorUnitsAndSignedV21Fields() {
    VnPayTestGatewayClient client = new VnPayTestGatewayClient(settings(), fixedClock());

    String checkoutUrl = client.createCreditTopUp(1_000, ORDER_ID, "127.0.0.1");
    URI uri = URI.create(checkoutUrl);
    Map<String, String> fields = parseQuery(uri.getRawQuery());

    assertThat(VnPayTestCheckoutUrlPolicy.allows(checkoutUrl)).isTrue();
    assertThat(uri.getScheme()).isEqualTo("https");
    assertThat(uri.getHost()).isEqualTo("sandbox.vnpayment.vn");
    assertThat(uri.getRawPath()).isEqualTo("/paymentv2/vpcpay.html");
    assertThat(fields)
        .containsEntry("vnp_Amount", "100000")
        .containsEntry("vnp_Command", "pay")
        .containsEntry("vnp_CreateDate", "20261125190000")
        .containsEntry("vnp_ExpireDate", "20261125191500")
        .containsEntry("vnp_CurrCode", "VND")
        .containsEntry("vnp_IpAddr", "127.0.0.1")
        .containsEntry("vnp_OrderType", "other")
        .containsEntry("vnp_TmnCode", "ABCD1234")
        .containsEntry("vnp_TxnRef", "11111111111141118111111111111111")
        .containsEntry("vnp_Version", "2.1.0");
    assertThat(
            VnPayTestSignatureCodec.verify(
                VnPayTestSignatureCodec.signedFields(fields),
                HASH_SECRET,
                fields.get("vnp_SecureHash")))
        .isTrue();
  }

  @Test
  void rejectsOutOfRangeAmountsAndDoesNotTrustInvalidPeerAddressText() {
    VnPayTestGatewayClient client = new VnPayTestGatewayClient(settings(), fixedClock());
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> client.createCreditTopUp(999, ORDER_ID, "127.0.0.1"));
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> client.createCreditTopUp(50_000_001, ORDER_ID, "127.0.0.1"));

    Map<String, String> fields =
        parseQuery(
            URI.create(client.createCreditTopUp(1_000, ORDER_ID, "1.2.3.4,evil")).getRawQuery());
    assertThat(fields.get("vnp_IpAddr")).isEqualTo("127.0.0.1");
  }

  @Test
  void usesThePersistedPaymentRequestInstantForProviderAndQueryTransactionDate() {
    Instant paymentRequestCreatedAt = Instant.parse("2026-11-25T12:01:23Z");
    VnPayTestGatewayClient client = new VnPayTestGatewayClient(settings(), fixedClock());

    Map<String, String> fields =
        parseQuery(
            URI.create(
                    client.createCreditTopUp(1_000, ORDER_ID, "127.0.0.1", paymentRequestCreatedAt))
                .getRawQuery());

    assertThat(fields)
        .containsEntry("vnp_CreateDate", "20261125190123")
        .containsEntry("vnp_ExpireDate", "20261125191623");
    assertThat(VnPayTestGatewayClient.formatVnPayDate(paymentRequestCreatedAt))
        .isEqualTo(fields.get("vnp_CreateDate"));
  }

  private static VnPayTestGatewaySettings settings() {
    return new VnPayTestGatewaySettings(
        "ABCD1234",
        HASH_SECRET,
        VnPayTestGatewaySettings.SANDBOX_PAYMENT_URI,
        URI.create(
            "https://outshine-chair-decipher.ngrok-free.dev" + VnPayTestGatewaySettings.IPN_PATH),
        URI.create("https://outshine-chair-decipher.ngrok-free.dev/app/payment/result"));
  }

  private static Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);
  }

  private static Map<String, String> parseQuery(String query) {
    return Arrays.stream(query.split("&"))
        .map(pair -> pair.split("=", 2))
        .collect(
            Collectors.toMap(
                pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
  }
}
