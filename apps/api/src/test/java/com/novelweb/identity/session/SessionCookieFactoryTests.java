package com.novelweb.identity.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class SessionCookieFactoryTests {

  private static final String SESSION_TOKEN =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

  @Test
  void issueUsesHostOnlyHttpOnlySecureLaxCookieWithRootPath() {
    ResponseCookie cookie = SessionCookieFactory.issue(SESSION_TOKEN, Duration.ofDays(30));
    String header = cookie.toString();

    assertTrue(header.startsWith("__Host-novel_session=" + SESSION_TOKEN));
    assertTrue(header.contains("Path=/"));
    assertTrue(header.contains("HttpOnly"));
    assertTrue(header.contains("Secure"));
    assertTrue(header.contains("SameSite=Lax"));
    assertTrue(header.contains("Max-Age=2592000"));
    assertFalse(header.toLowerCase().contains("domain="));
  }

  @Test
  void clearExpiresTheSameSecureHostCookieAndRejectsInvalidIssueInputs() {
    String header = SessionCookieFactory.clear().toString();

    assertTrue(header.startsWith("__Host-novel_session="));
    assertTrue(header.contains("Max-Age=0"));
    assertTrue(header.contains("Secure"));
    assertTrue(header.contains("HttpOnly"));
    assertTrue(header.contains("SameSite=Lax"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SessionCookieFactory.issue(SESSION_TOKEN + "=", Duration.ofDays(1)));
    assertThrows(
        IllegalArgumentException.class, () -> SessionCookieFactory.issue(null, Duration.ofDays(1)));
    assertThrows(
        IllegalArgumentException.class, () -> SessionCookieFactory.issue(SESSION_TOKEN, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> SessionCookieFactory.issue(SESSION_TOKEN, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> SessionCookieFactory.issue(SESSION_TOKEN, Duration.ofSeconds(-1)));
  }
}
