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

/** VNPAY 2.1.0 canonical query-string signing and callback verification. */
public final class VnPayTestSignatureCodec {

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
