package com.novelweb.identity.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SessionCsrfTokenServiceTests {

  private static final String KEY =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
  private static final String SESSION_ONE =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
  private static final String SESSION_TWO =
      Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              new byte[] {
                1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0
              });

  @Test
  void derivesCanonicalTokenThatIsStableOnlyForTheSameSessionSecret() {
    SessionCsrfTokenService service = new SessionCsrfTokenService(KEY);

    String first = service.tokenFor(SESSION_ONE);
    String repeat = service.tokenFor(SESSION_ONE);
    String otherSession = service.tokenFor(SESSION_TWO);

    assertTrue(service.isConfigured());
    assertTrue(first.matches("[A-Za-z0-9_-]{43}"));
    assertEquals(first, repeat);
    assertNotEquals(first, otherSession);
  }

  @Test
  void matchesOnlyTheTokenDerivedFromTheSameSession() {
    SessionCsrfTokenService service = new SessionCsrfTokenService(KEY);
    String token = service.tokenFor(SESSION_ONE);

    assertTrue(service.matches(SESSION_ONE, token));
    assertFalse(service.matches(SESSION_TWO, token));
    assertFalse(service.matches(SESSION_ONE, "attacker-token"));
    assertFalse(service.matches("malformed-session", token));
    assertNull(service.tokenFor(null));
    assertFalse(service.matches(null, token));
    assertFalse(service.matches(SESSION_ONE, null));
  }

  @Test
  void missingKeyFailsClosedWithoutIssuingOrAcceptingTokens() {
    SessionCsrfTokenService service = new SessionCsrfTokenService("");
    SessionCsrfTokenService nullKeyService = new SessionCsrfTokenService(null);

    assertFalse(service.isConfigured());
    assertNull(service.tokenFor(SESSION_ONE));
    assertFalse(service.matches(SESSION_ONE, "A".repeat(43)));
    assertFalse(nullKeyService.isConfigured());
    assertNull(nullKeyService.tokenFor(SESSION_ONE));
    assertFalse(nullKeyService.matches(SESSION_ONE, "A".repeat(43)));
  }

  @Test
  void malformedOrNonCanonicalKeysAreRejectedAtStartup() {
    assertThrows(IllegalArgumentException.class, () -> new SessionCsrfTokenService("too-short"));
    String paddedKey = KEY + "=";
    assertThrows(IllegalArgumentException.class, () -> new SessionCsrfTokenService(paddedKey));
    String nonCanonicalTrailingBits = KEY.substring(0, KEY.length() - 1) + "B";
    assertThrows(
        IllegalArgumentException.class,
        () -> new SessionCsrfTokenService(nonCanonicalTrailingBits));
  }
}
