package com.novelweb.payment.momo;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Synthetic signed IPN fixtures shared by controller and PostgreSQL integration tests. */
public final class MomoTestIpnTestSupport {

  public static final String PARTNER_CODE = "MOMOTEST123";
  public static final String ACCESS_KEY = "synthetic-ipn-access";
  public static final String SECRET_KEY = "synthetic-ipn-secret-only";

  private MomoTestIpnTestSupport() {}

  public static SignedNotification sign(
      String orderId, String requestId, long amountVnd, int resultCode, long transactionId) {
    MomoSignatureCodec.PaymentNotification notification =
        new MomoSignatureCodec.PaymentNotification(
            amountVnd,
            "",
            resultCode == 0 ? "Successful." : "Synthetic result",
            orderId,
            "Novel Web credit top-up",
            "momo_wallet",
            PARTNER_CODE,
            "qr",
            requestId,
            1_794_686_400_000L,
            resultCode,
            transactionId);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] signature =
          mac.doFinal(
              MomoSignatureCodec.canonicalNotification(notification, ACCESS_KEY)
                  .getBytes(StandardCharsets.UTF_8));
      return new SignedNotification(notification, java.util.HexFormat.of().formatHex(signature));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to sign a synthetic MoMo IPN fixture", exception);
    }
  }

  public static Map<String, Object> payload(SignedNotification signed) {
    MomoSignatureCodec.PaymentNotification notification = signed.notification();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("amount", notification.amountVnd());
    payload.put("extraData", notification.extraData());
    payload.put("message", notification.message());
    payload.put("orderId", notification.orderId());
    payload.put("orderInfo", notification.orderInfo());
    payload.put("orderType", notification.orderType());
    payload.put("partnerCode", notification.partnerCode());
    payload.put("payType", notification.payType());
    payload.put("requestId", notification.requestId());
    payload.put("responseTime", notification.responseTime());
    payload.put("resultCode", notification.resultCode());
    payload.put("transId", notification.transactionId());
    payload.put("signature", signed.signature());
    return Map.copyOf(payload);
  }

  public record SignedNotification(
      MomoSignatureCodec.PaymentNotification notification, String signature) {}
}
