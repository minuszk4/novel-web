package com.novelweb.payment.momo;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Implements the field-specific HMAC-SHA256 canonical forms used by MoMo's one-time wallet flow.
 * This codec performs no network calls and does not grant payment or credit state.
 */
public final class MomoSignatureCodec {

  private static final String ALGORITHM = "HmacSHA256";
  private static final String WALLET_REQUEST_TYPE = "captureWallet";
  private static final long MINIMUM_AMOUNT_VND = 1_000L;
  private static final long MAXIMUM_AMOUNT_VND = 50_000_000L;
  private static final Pattern HEX_SIGNATURE = Pattern.compile("[0-9a-fA-F]{64}");

  private MomoSignatureCodec() {}

  /** Signs the exact {@code captureWallet} create-request fields in MoMo's documented order. */
  public static String signCreateWallet(
      CreateWalletRequest request, String accessKey, String secretKey) {
    Objects.requireNonNull(request, "create-wallet request is required");
    return HexFormat.of().formatHex(hmac(canonicalCreateWallet(request, accessKey), secretKey));
  }

  /** Signs a MoMo order-status query using its distinct request field set. */
  public static String signQueryOrder(
      QueryOrderRequest request, String accessKey, String secretKey) {
    Objects.requireNonNull(request, "query-order request is required");
    return HexFormat.of().formatHex(hmac(canonicalQueryOrder(request, accessKey), secretKey));
  }

  /**
   * Verifies the exact documented IPN fields. Malformed or incomplete notifications fail closed;
   * order ownership, environment and amount matching remain the payment service's responsibility.
   */
  public static boolean verifyNotification(
      PaymentNotification notification,
      String accessKey,
      String secretKey,
      String suppliedSignature) {
    if (notification == null
        || !notification.hasAllSignedFields()
        || suppliedSignature == null
        || !HEX_SIGNATURE.matcher(suppliedSignature).matches()) {
      return false;
    }

    byte[] expected = hmac(canonicalNotification(notification, accessKey), secretKey);
    byte[] supplied = HexFormat.of().parseHex(suppliedSignature);
    return MessageDigest.isEqual(expected, supplied);
  }

  /** Verifies MoMo's signed one-time checkout-create response before its fields are consumed. */
  public static boolean verifyCreateWalletResponse(
      CreateWalletResponse response, String accessKey, String secretKey) {
    if (response == null
        || !response.hasAllSignedFields()
        || response.signature() == null
        || !HEX_SIGNATURE.matcher(response.signature()).matches()) {
      return false;
    }

    byte[] expected = hmac(canonicalCreateWalletResponse(response, accessKey), secretKey);
    byte[] supplied = HexFormat.of().parseHex(response.signature());
    return MessageDigest.isEqual(expected, supplied);
  }

  static String canonicalCreateWallet(CreateWalletRequest request, String accessKey) {
    requireAccessKey(accessKey);
    return "accessKey="
        + accessKey
        + "&amount="
        + request.amountVnd()
        + "&extraData="
        + request.extraData()
        + "&ipnUrl="
        + request.ipnUrl()
        + "&orderId="
        + request.orderId()
        + "&orderInfo="
        + request.orderInfo()
        + "&partnerCode="
        + request.partnerCode()
        + "&redirectUrl="
        + request.redirectUrl()
        + "&requestId="
        + request.requestId()
        + "&requestType="
        + WALLET_REQUEST_TYPE;
  }

  static String canonicalQueryOrder(QueryOrderRequest request, String accessKey) {
    requireAccessKey(accessKey);
    return "accessKey="
        + accessKey
        + "&orderId="
        + request.orderId()
        + "&partnerCode="
        + request.partnerCode()
        + "&requestId="
        + request.requestId();
  }

  static String canonicalNotification(PaymentNotification notification, String accessKey) {
    requireAccessKey(accessKey);
    return "accessKey="
        + accessKey
        + "&amount="
        + notification.amountVnd()
        + "&extraData="
        + notification.extraData()
        + "&message="
        + notification.message()
        + "&orderId="
        + notification.orderId()
        + "&orderInfo="
        + notification.orderInfo()
        + "&orderType="
        + notification.orderType()
        + "&partnerCode="
        + notification.partnerCode()
        + "&payType="
        + notification.payType()
        + "&requestId="
        + notification.requestId()
        + "&responseTime="
        + notification.responseTime()
        + "&resultCode="
        + notification.resultCode()
        + "&transId="
        + notification.transactionId();
  }

  static String canonicalCreateWalletResponse(CreateWalletResponse response, String accessKey) {
    requireAccessKey(accessKey);
    return "accessKey="
        + accessKey
        + "&amount="
        + response.amountVnd()
        + "&message="
        + response.message()
        + "&orderId="
        + response.orderId()
        + "&partnerCode="
        + response.partnerCode()
        + "&payUrl="
        + response.payUrl()
        + "&requestId="
        + response.requestId()
        + "&responseTime="
        + response.responseTime()
        + "&resultCode="
        + response.resultCode();
  }

  private static byte[] hmac(String canonicalData, String secretKey) {
    if (secretKey == null || secretKey.isBlank()) {
      throw new IllegalArgumentException("MoMo secret key is not configured");
    }
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
      return mac.doFinal(canonicalData.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to calculate the MoMo signature", exception);
    }
  }

  private static void requireAccessKey(String accessKey) {
    if (accessKey == null || accessKey.isBlank()) {
      throw new IllegalArgumentException("MoMo access key is not configured");
    }
  }

  public record CreateWalletRequest(
      long amountVnd,
      String extraData,
      String ipnUrl,
      String orderId,
      String orderInfo,
      String partnerCode,
      String redirectUrl,
      String requestId) {

    public CreateWalletRequest {
      if (amountVnd < MINIMUM_AMOUNT_VND || amountVnd > MAXIMUM_AMOUNT_VND) {
        throw new IllegalArgumentException(
            "MoMo wallet amount must be between 1,000 and 50,000,000 VND");
      }
      Objects.requireNonNull(extraData, "extraData is required; use an empty string when absent");
      Objects.requireNonNull(ipnUrl, "ipnUrl is required");
      Objects.requireNonNull(orderId, "orderId is required");
      Objects.requireNonNull(orderInfo, "orderInfo is required");
      Objects.requireNonNull(partnerCode, "partnerCode is required");
      Objects.requireNonNull(
          redirectUrl, "redirectUrl is required; use an empty string when absent");
      Objects.requireNonNull(requestId, "requestId is required");
    }
  }

  public record QueryOrderRequest(String partnerCode, String requestId, String orderId) {

    public QueryOrderRequest {
      Objects.requireNonNull(partnerCode, "partnerCode is required");
      Objects.requireNonNull(requestId, "requestId is required");
      Objects.requireNonNull(orderId, "orderId is required");
    }
  }

  public record PaymentNotification(
      Long amountVnd,
      String extraData,
      String message,
      String orderId,
      String orderInfo,
      String orderType,
      String partnerCode,
      String payType,
      String requestId,
      Long responseTime,
      Integer resultCode,
      Long transactionId) {

    private boolean hasAllSignedFields() {
      return amountVnd != null
          && extraData != null
          && message != null
          && orderId != null
          && orderInfo != null
          && orderType != null
          && partnerCode != null
          && payType != null
          && requestId != null
          && responseTime != null
          && resultCode != null
          && transactionId != null;
    }
  }

  public record CreateWalletResponse(
      String partnerCode,
      String requestId,
      String orderId,
      Long amountVnd,
      String message,
      Long responseTime,
      Integer resultCode,
      String payUrl,
      String signature) {

    private boolean hasAllSignedFields() {
      return partnerCode != null
          && requestId != null
          && orderId != null
          && amountVnd != null
          && message != null
          && responseTime != null
          && resultCode != null
          && payUrl != null;
    }
  }
}
