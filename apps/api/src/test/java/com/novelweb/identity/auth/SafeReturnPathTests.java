package com.novelweb.identity.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SafeReturnPathTests {

  @Test
  void acceptsAnInternalApplicationPath() {
    assertEquals("/app/reader?work=abc", SafeReturnPath.sanitize("/app/reader?work=abc"));
  }

  @Test
  void replacesExternalAndAmbiguousPathsWithHome() {
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize("https://attacker.example"));
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize("//attacker.example"));
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize("/%2f%2fattacker.example"));
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize("/\\attacker.example"));
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize("/app/../admin"));
  }

  @Test
  void replacesMalformedOverlongAndControlCharacterPathsWithHome() {
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize("/bad path"));
    assertEquals(
        SafeReturnPath.DEFAULT_PATH,
        SafeReturnPath.sanitize("/app/home\r\nLocation: https://attacker.example"));
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize("/" + "a".repeat(512)));
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize(null));
  }
}
