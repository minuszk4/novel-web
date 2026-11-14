package com.novelweb.payment.momo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class MomoTestCheckoutResponseValidatorTests {

  private static final String ACCESS_KEY = "novel-test-access";
  private static final String SECRET_KEY = "novel-test-secret-only";
  private static final String PARTNER_CODE = "MOMOTEST123";
  private static final String REQUEST_ID = "request-2026-0001";
  private static final String ORDER_ID = "order-2026-0001";
  private static final long AMOUNT_VND = 1_000L;
  private static final long RESPONSE_TIME = 1_763_164_801_234L;
  private static final String TEST_URL =
      "https://test-payment.momo.vn/v2/gateway/pay?t=synthetic-ticket&s=synthetic-signature";

  @Test
  void acceptsOnlySignedSuccessMatchingTheStoredOrderAndMoMoTestUrl() {
    assertThat(
            MomoTestCheckoutResponseValidator.accepts(
                signedResponse(PARTNER_CODE, REQUEST_ID, ORDER_ID, AMOUNT_VND, 0, TEST_URL),
                expectedOrder(),
                ACCESS_KEY,
                SECRET_KEY))
        .isTrue();

    assertThat(
            MomoTestCheckoutResponseValidator.accepts(
                signedResponse(PARTNER_CODE, REQUEST_ID, ORDER_ID, AMOUNT_VND, 9000, TEST_URL),
                expectedOrder(),
                ACCESS_KEY,
                SECRET_KEY))
        .isFalse();
    assertThat(
            MomoTestCheckoutResponseValidator.accepts(
                signedResponse(
                    PARTNER_CODE,
                    REQUEST_ID,
                    ORDER_ID,
                    AMOUNT_VND,
                    0,
                    "https://evil.example/v2/gateway/pay?t=synthetic"),
                expectedOrder(),
                ACCESS_KEY,
                SECRET_KEY))
        .isFalse();
  }

  @Test
  void rejectsValidlySignedResponsesWithMismatchedOrderSnapshotFields() {
    assertThat(
            accepts(signedResponse("OTHER-PARTNER", REQUEST_ID, ORDER_ID, AMOUNT_VND, 0, TEST_URL)))
        .isFalse();
    assertThat(
            accepts(
                signedResponse(PARTNER_CODE, "other-request", ORDER_ID, AMOUNT_VND, 0, TEST_URL)))
        .isFalse();
    assertThat(
            accepts(
                signedResponse(PARTNER_CODE, REQUEST_ID, "other-order", AMOUNT_VND, 0, TEST_URL)))
        .isFalse();
    assertThat(accepts(signedResponse(PARTNER_CODE, REQUEST_ID, ORDER_ID, 1_001L, 0, TEST_URL)))
        .isFalse();
  }

  @Test
  void limitsCheckoutRedirectToTheExactHttpsTestHostAndPath() {
    assertThat(MomoTestCheckoutUrlPolicy.allows(TEST_URL)).isTrue();
    assertThat(
            MomoTestCheckoutUrlPolicy.allows("HTTPS://TEST-PAYMENT.MOMO.VN:443/v2/gateway/pay?t=x"))
        .isTrue();

    assertThat(MomoTestCheckoutUrlPolicy.allows("http://test-payment.momo.vn/v2/gateway/pay?t=x"))
        .isFalse();
    assertThat(
            MomoTestCheckoutUrlPolicy.allows(
                "https://test-payment.momo.vn.attacker.example/v2/gateway/pay?t=x"))
        .isFalse();
    assertThat(
            MomoTestCheckoutUrlPolicy.allows(
                "https://test-payment.momo.vn@attacker.example/v2/gateway/pay?t=x"))
        .isFalse();
    assertThat(
            MomoTestCheckoutUrlPolicy.allows("https://test-payment.momo.vn:444/v2/gateway/pay?t=x"))
        .isFalse();
    assertThat(MomoTestCheckoutUrlPolicy.allows("https://test-payment.momo.vn/other?t=x"))
        .isFalse();
    assertThat(MomoTestCheckoutUrlPolicy.allows("https://test-payment.momo.vn/v2/gateway/pay"))
        .isFalse();
    assertThat(
            MomoTestCheckoutUrlPolicy.allows(
                "https://test-payment.momo.vn/v2/gateway/pay?t=x#fragment"))
        .isFalse();
    assertThat(MomoTestCheckoutUrlPolicy.allows(null)).isFalse();
  }

  @Test
  void rejectsCheckoutValidationWhenCredentialsAreUnconfigured() {
    MomoSignatureCodec.CreateWalletResponse response =
        signedResponse(PARTNER_CODE, REQUEST_ID, ORDER_ID, AMOUNT_VND, 0, TEST_URL);

    assertThat(MomoTestCheckoutResponseValidator.accepts(response, expectedOrder(), "", SECRET_KEY))
        .isFalse();
    assertThat(
            MomoTestCheckoutResponseValidator.accepts(response, expectedOrder(), ACCESS_KEY, " "))
        .isFalse();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new MomoTestCheckoutResponseValidator.ExpectedOrder(
                    PARTNER_CODE, REQUEST_ID, ORDER_ID, 999L));
  }

  private static boolean accepts(MomoSignatureCodec.CreateWalletResponse response) {
    return MomoTestCheckoutResponseValidator.accepts(
        response, expectedOrder(), ACCESS_KEY, SECRET_KEY);
  }

  private static MomoTestCheckoutResponseValidator.ExpectedOrder expectedOrder() {
    return new MomoTestCheckoutResponseValidator.ExpectedOrder(
        PARTNER_CODE, REQUEST_ID, ORDER_ID, AMOUNT_VND);
  }

  private static MomoSignatureCodec.CreateWalletResponse signedResponse(
      String partnerCode,
      String requestId,
      String orderId,
      long amountVnd,
      int resultCode,
      String payUrl) {
    MomoSignatureCodec.CreateWalletResponse unsigned =
        new MomoSignatureCodec.CreateWalletResponse(
            partnerCode,
            requestId,
            orderId,
            amountVnd,
            "Successful.",
            RESPONSE_TIME,
            resultCode,
            payUrl,
            "");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] signature =
          mac.doFinal(
              MomoSignatureCodec.canonicalCreateWalletResponse(unsigned, ACCESS_KEY)
                  .getBytes(StandardCharsets.UTF_8));
      return new MomoSignatureCodec.CreateWalletResponse(
          partnerCode,
          requestId,
          orderId,
          amountVnd,
          unsigned.message(),
          RESPONSE_TIME,
          resultCode,
          payUrl,
          HexFormat.of().formatHex(signature));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
