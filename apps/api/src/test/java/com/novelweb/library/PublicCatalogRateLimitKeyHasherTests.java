package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class PublicCatalogRateLimitKeyHasherTests {

  private static final String KEY =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

  @Test
  void derivesStableDomainSeparatedHashForAnIpAddress() {
    byte[] address = new byte[] {(byte) 192, 0, 2, 7};

    byte[] first = PublicCatalogRateLimitKeyHasher.hash(address, KEY);
    byte[] second = PublicCatalogRateLimitKeyHasher.hash(address, KEY);

    assertArrayEquals(first, second);
    assertEqualsLength32(first);
    assertFalse(java.util.Arrays.equals(address, first));
    assertNotEquals(
        java.util.HexFormat.of().formatHex(first),
        java.util.HexFormat.of()
            .formatHex(HmacFixture.hash(address, Base64.getUrlDecoder().decode(KEY))));
  }

  @Test
  void changesWithAddressAndRejectsMissingOrWrongSizedKeys() {
    byte[] first = PublicCatalogRateLimitKeyHasher.hash(new byte[] {127, 0, 0, 1}, KEY);
    byte[] second = PublicCatalogRateLimitKeyHasher.hash(new byte[] {127, 0, 0, 2}, KEY);

    assertFalse(java.util.Arrays.equals(first, second));
    assertTrue(PublicCatalogRateLimitKeyHasher.isValidKey(KEY));
    assertFalse(PublicCatalogRateLimitKeyHasher.isValidKey(""));
    assertFalse(
        PublicCatalogRateLimitKeyHasher.isValidKey(
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31])));
    assertThrows(
        IllegalArgumentException.class,
        () -> PublicCatalogRateLimitKeyHasher.hash(new byte[] {1}, KEY));
  }

  @Test
  void groupsIpv6PrivacyAddressesByTheirNetworkPrefix() {
    byte[] first = new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1};
    byte[] rotated =
        new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 1, 0, 2, 0x55, 0x66, 0x77, 0, 0, 0, 0, 9};
    byte[] otherNetwork =
        new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 1};

    assertArrayEquals(
        PublicCatalogRateLimitKeyHasher.hash(first, KEY),
        PublicCatalogRateLimitKeyHasher.hash(rotated, KEY));
    assertFalse(
        java.util.Arrays.equals(
            PublicCatalogRateLimitKeyHasher.hash(first, KEY),
            PublicCatalogRateLimitKeyHasher.hash(otherNetwork, KEY)));
  }

  private static void assertEqualsLength32(byte[] value) {
    org.junit.jupiter.api.Assertions.assertEquals(32, value.length);
  }

  private static final class HmacFixture {
    private static byte[] hash(byte[] address, byte[] key) {
      try {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(address);
      } catch (java.security.GeneralSecurityException exception) {
        throw new AssertionError(exception);
      }
    }
  }
}
