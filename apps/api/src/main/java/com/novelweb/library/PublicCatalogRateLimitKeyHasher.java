package com.novelweb.library;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Creates domain-separated HMAC keys for anonymous catalog rate-limit buckets. */
final class PublicCatalogRateLimitKeyHasher {

  private static final String DOMAIN = "novel-web:public-catalog-rate-limit:v1";
  private static final String ALGORITHM = "HmacSHA256";
  private static final int SECRET_BYTES = 32;

  private PublicCatalogRateLimitKeyHasher() {}

  static boolean isValidKey(String encodedKey) {
    try {
      return decodeKey(encodedKey).length == SECRET_BYTES;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  static byte[] hash(byte[] clientAddress, String encodedKey) {
    if (clientAddress == null || (clientAddress.length != 4 && clientAddress.length != 16)) {
      throw new IllegalArgumentException("Client address must be IPv4 or IPv6");
    }
    byte[] normalizedAddress = clientAddress.clone();
    if (normalizedAddress.length == 16) {
      Arrays.fill(normalizedAddress, 8, normalizedAddress.length, (byte) 0);
    }
    byte[] domain = DOMAIN.getBytes(StandardCharsets.US_ASCII);
    byte[] payload =
        ByteBuffer.allocate(domain.length + 2 + normalizedAddress.length)
            .put(domain)
            .put((byte) 0)
            .put((byte) normalizedAddress.length)
            .put(normalizedAddress)
            .array();
    try {
      Mac hmac = Mac.getInstance(ALGORITHM);
      hmac.init(new SecretKeySpec(decodeKey(encodedKey), ALGORITHM));
      return hmac.doFinal(payload);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to create public catalog rate-limit key", exception);
    }
  }

  private static byte[] decodeKey(String encodedKey) {
    if (encodedKey == null || encodedKey.isBlank()) {
      throw new IllegalArgumentException("Public catalog rate-limit key is not configured");
    }
    byte[] key;
    try {
      key = Base64.getUrlDecoder().decode(encodedKey);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Public catalog rate-limit key is invalid", exception);
    }
    if (key.length != SECRET_BYTES) {
      throw new IllegalArgumentException("Public catalog rate-limit key must decode to 32 bytes");
    }
    return key;
  }
}
