package com.novelweb.payment.vnpay;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** VNPAY 2.1.0 checkout/IPN query-string signing and pipe-delimited QueryDR verification. */
public final class VnPayTestSignatureCodec {

  private static final java.util.List<String> QUERY_REQUEST_FIELDS =
      java.util.List.of(
          "vnp_RequestId",
          "vnp_Version",
          "vnp_Command",
          "vnp_TmnCode",
          "vnp_TxnRef",
          "vnp_TransactionDate",
          "vnp_CreateDate",
          "vnp_IpAddr",
          "vnp_OrderInfo");
  private static final java.util.List<String> QUERY_RESPONSE_FIELDS =
      java.util.List.of(
          "vnp_ResponseId",
          "vnp_Command",
          "vnp_ResponseCode",
          "vnp_Message",
          "vnp_TmnCode",
          "vnp_TxnRef",
          "vnp_Amount",
          "vnp_BankCode",
          "vnp_PayDate",
          "vnp_TransactionNo",
          "vnp_TransactionType",
          "vnp_TransactionStatus",
          "vnp_OrderInfo",
          "vnp_PromotionCode",
          "vnp_PromotionAmount");

  private VnPayTestSignatureCodec() {}

  public static String sign(Map<String, String> fields, String hashSecret) {
    return hmacSha512(canonical(fields), hashSecret);
  }

  public static boolean verify(Map<String, String> fields, String hashSecret, String signature) {
    if (signature == null || !signature.matches("(?i)[0-9a-f]{128}")) return false;
    byte[] expected = sign(fields, hashSecret).getBytes(StandardCharsets.US_ASCII);
    byte[] provided =
        signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(expected, provided);
  }

  /** Signs the pipe-delimited request defined by VNPAY's QueryDR API. */
  public static String signQueryDrRequest(Map<String, String> fields, String hashSecret) {
    return hmacSha512(pipeCanonical(fields, QUERY_REQUEST_FIELDS), hashSecret);
  }

  /** Verifies the pipe-delimited QueryDR response signature from VNPAY. */
  public static boolean verifyQueryDrResponse(
      Map<String, String> fields, String hashSecret, String signature) {
    if (signature == null || !signature.matches("(?i)[0-9a-f]{128}")) return false;
    byte[] expected =
        hmacSha512(pipeCanonical(fields, QUERY_RESPONSE_FIELDS), hashSecret)
            .getBytes(StandardCharsets.US_ASCII);
    byte[] provided =
        signature.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    return MessageDigest.isEqual(expected, provided);
  }

  static String signQueryDrResponse(Map<String, String> fields, String hashSecret) {
    return hmacSha512(pipeCanonical(fields, QUERY_RESPONSE_FIELDS), hashSecret);
  }

  static String pipeCanonical(Map<String, String> fields, java.util.List<String> names) {
    StringBuilder canonical = new StringBuilder();
    for (String name : names) {
      if (!canonical.isEmpty()) canonical.append('|');
      String value = fields.get(name);
      if (value != null) canonical.append(value);
    }
    return canonical.toString();
  }

  public static SortedMap<String, String> signedFields(Map<String, String> supplied) {
    SortedMap<String, String> result = new TreeMap<>();
    if (supplied == null) return result;
    for (Map.Entry<String, String> field : supplied.entrySet()) {
      String key = field.getKey();
      String value = field.getValue();
      if (key != null
          && key.startsWith("vnp_")
          && !key.equals("vnp_SecureHash")
          && !key.equals("vnp_SecureHashType")
          && value != null) {
        result.put(key, value);
      }
    }
    return result;
  }

  public static String canonical(Map<String, String> fields) {
    SortedMap<String, String> sorted = new TreeMap<>(fields);
    StringBuilder canonical = new StringBuilder();
    for (Map.Entry<String, String> field : sorted.entrySet()) {
      if (!canonical.isEmpty()) canonical.append('&');
      canonical.append(encode(field.getKey())).append('=').append(encode(field.getValue()));
    }
    return canonical.toString();
  }

  public static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String hmacSha512(String value, String hashSecret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA512");
      mac.init(new SecretKeySpec(hashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("VNPAY HMAC-SHA512 is unavailable", exception);
    }
  }
}
