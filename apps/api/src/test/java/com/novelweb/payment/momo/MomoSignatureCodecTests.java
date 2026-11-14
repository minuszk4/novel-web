package com.novelweb.payment.momo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MomoSignatureCodecTests {

  private static final String ACCESS_KEY = "novel-test-access";
  private static final String SECRET_KEY = "novel-test-secret-only";
  private static final String EXTRA_DATA = "eyJ0ZXN0Ijoib25seSJ9";

  @Test
  void signsCaptureWalletFieldsInTheDocumentedCanonicalOrder() {
    MomoSignatureCodec.CreateWalletRequest request = createWalletRequest(1_000L);

    assertThat(MomoSignatureCodec.canonicalCreateWallet(request, ACCESS_KEY))
        .isEqualTo(
            "accessKey=novel-test-access&amount=1000&extraData=eyJ0ZXN0Ijoib25seSJ9"
                + "&ipnUrl=https://example.test/api/v1/momo/ipn&orderId=order-2026-0001"
                + "&orderInfo=Credit top-up&partnerCode=MOMOTEST123"
                + "&redirectUrl=https://example.test/app/payment/result"
                + "&requestId=request-2026-0001&requestType=captureWallet");
    assertThat(MomoSignatureCodec.signCreateWallet(request, ACCESS_KEY, SECRET_KEY))
        .isEqualTo("03352a3cf1e081d67175939e031773af02e40a2ef21a5c89ee9c002a8b08241b");
  }

  @Test
  void signsStatusQueryWithItsDistinctCanonicalFieldSet() {
    MomoSignatureCodec.QueryOrderRequest request =
        new MomoSignatureCodec.QueryOrderRequest(
            "MOMOTEST123", "request-2026-0002", "order-2026-0001");

    assertThat(MomoSignatureCodec.canonicalQueryOrder(request, ACCESS_KEY))
        .isEqualTo(
            "accessKey=novel-test-access&orderId=order-2026-0001"
                + "&partnerCode=MOMOTEST123&requestId=request-2026-0002");
    assertThat(MomoSignatureCodec.signQueryOrder(request, ACCESS_KEY, SECRET_KEY))
        .isEqualTo("656caca6e32e30fb223fdb42a4bfcf1828d60a7f5ade818852b7153956f26328");
  }

  @Test
  void rejectsWalletAmountsOutsideTheDocumentedVndRange() {
    assertThatIllegalArgumentException().isThrownBy(() -> createWalletRequest(999L));
    assertThat(
            MomoSignatureCodec.signCreateWallet(
                createWalletRequest(50_000_000L), ACCESS_KEY, SECRET_KEY))
        .hasSize(64);
    assertThatIllegalArgumentException().isThrownBy(() -> createWalletRequest(50_000_001L));
  }

  @Test
  void verifiesNotificationVectorAndRejectsMutationOrMalformedSignature() {
    MomoSignatureCodec.PaymentNotification notification = notification(1_000L);
    String signature = "8ee9b5feb3f59e5de88a6e6ce330791a4f4751a3b3481dc06694c4c5abb66b66";

    assertThat(MomoSignatureCodec.canonicalNotification(notification, ACCESS_KEY))
        .isEqualTo(
            "accessKey=novel-test-access&amount=1000&extraData=eyJ0ZXN0Ijoib25seSJ9"
                + "&message=Successful.&orderId=order-2026-0001&orderInfo=Credit top-up"
                + "&orderType=momo_wallet&partnerCode=MOMOTEST123&payType=qr"
                + "&requestId=request-2026-0001&responseTime=1763164800000"
                + "&resultCode=0&transId=1234567890");
    assertThat(
            MomoSignatureCodec.verifyNotification(notification, ACCESS_KEY, SECRET_KEY, signature))
        .isTrue();
    assertThat(
            MomoSignatureCodec.verifyNotification(
                notification, ACCESS_KEY, SECRET_KEY, signature.toUpperCase()))
        .isTrue();
    assertThat(
            MomoSignatureCodec.verifyNotification(
                notification(1_001L), ACCESS_KEY, SECRET_KEY, signature))
        .isFalse();
    assertThat(
            MomoSignatureCodec.verifyNotification(
                notification, "other-access-key", SECRET_KEY, signature))
        .isFalse();
    assertThat(
            MomoSignatureCodec.verifyNotification(
                notification, ACCESS_KEY, SECRET_KEY, "not-a-signature"))
        .isFalse();
  }

  @Test
  void verifiesCreateWalletResponseUsingItsDistinctCanonicalFieldSet() {
    MomoSignatureCodec.CreateWalletResponse response =
        new MomoSignatureCodec.CreateWalletResponse(
            "MOMOTEST123",
            "request-2026-0001",
            "order-2026-0001",
            1_000L,
            "Successful.",
            1_763_164_801_234L,
            0,
            "https://test-payment.momo.vn/v2/gateway/pay"
                + "?t=synthetic-ticket&s=synthetic-signature",
            "b1b8e157a91208126f97b521123df14261bcc5fae2ea9f3eccd9950856f19695");

    assertThat(MomoSignatureCodec.canonicalCreateWalletResponse(response, ACCESS_KEY))
        .isEqualTo(
            "accessKey=novel-test-access&amount=1000&message=Successful."
                + "&orderId=order-2026-0001"
                + "&partnerCode=MOMOTEST123&payUrl=https://test-payment.momo.vn/v2/gateway/pay"
                + "?t=synthetic-ticket&s=synthetic-signature&requestId=request-2026-0001"
                + "&responseTime=1763164801234&resultCode=0");
    assertThat(MomoSignatureCodec.verifyCreateWalletResponse(response, ACCESS_KEY, SECRET_KEY))
        .isTrue();
    assertThat(
            MomoSignatureCodec.verifyCreateWalletResponse(
                new MomoSignatureCodec.CreateWalletResponse(
                    response.partnerCode(),
                    response.requestId(),
                    response.orderId(),
                    1_001L,
                    response.message(),
                    response.responseTime(),
                    response.resultCode(),
                    response.payUrl(),
                    response.signature()),
                ACCESS_KEY,
                SECRET_KEY))
        .isFalse();
    assertThat(
            MomoSignatureCodec.verifyCreateWalletResponse(
                new MomoSignatureCodec.CreateWalletResponse(
                    response.partnerCode(),
                    response.requestId(),
                    response.orderId(),
                    response.amountVnd(),
                    "Tampered message",
                    response.responseTime(),
                    response.resultCode(),
                    response.payUrl(),
                    response.signature()),
                ACCESS_KEY,
                SECRET_KEY))
        .isFalse();
    assertThat(MomoSignatureCodec.verifyCreateWalletResponse(response, ACCESS_KEY, "wrong-secret"))
        .isFalse();
  }

  @Test
  void rejectsNotificationMissingAnySignedField() {
    MomoSignatureCodec.PaymentNotification incomplete =
        new MomoSignatureCodec.PaymentNotification(
            null,
            EXTRA_DATA,
            "Successful.",
            "order-2026-0001",
            "Credit top-up",
            "momo_wallet",
            "MOMOTEST123",
            "qr",
            "request-2026-0001",
            1_763_164_800_000L,
            0,
            1_234_567_890L);

    assertThat(
            MomoSignatureCodec.verifyNotification(
                incomplete,
                ACCESS_KEY,
                SECRET_KEY,
                "8ee9b5feb3f59e5de88a6e6ce330791a4f4751a3b3481dc06694c4c5abb66b66"))
        .isFalse();
  }

  private static MomoSignatureCodec.CreateWalletRequest createWalletRequest(long amountVnd) {
    return new MomoSignatureCodec.CreateWalletRequest(
        amountVnd,
        EXTRA_DATA,
        "https://example.test/api/v1/momo/ipn",
        "order-2026-0001",
        "Credit top-up",
        "MOMOTEST123",
        "https://example.test/app/payment/result",
        "request-2026-0001");
  }

  private static MomoSignatureCodec.PaymentNotification notification(long amountVnd) {
    return new MomoSignatureCodec.PaymentNotification(
        amountVnd,
        EXTRA_DATA,
        "Successful.",
        "order-2026-0001",
        "Credit top-up",
        "momo_wallet",
        "MOMOTEST123",
        "qr",
        "request-2026-0001",
        1_763_164_800_000L,
        0,
        1_234_567_890L);
  }
}
