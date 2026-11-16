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
    String maximumLengthPath = "/" + "a".repeat(511);
    assertEquals(512, maximumLengthPath.length());
    assertEquals(maximumLengthPath, SafeReturnPath.sanitize(maximumLengthPath));
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize(maximumLengthPath + "a"));
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize(null));
  }

  @Test
  void rejectsEncodedTraversalBackslashAndHeaderControls() {
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize("/app/%2e%2e/admin"));
    assertEquals(SafeReturnPath.DEFAULT_PATH, SafeReturnPath.sanitize("/app/%5cadmin"));
    assertEquals(
        SafeReturnPath.DEFAULT_PATH,
        SafeReturnPath.sanitize("/app/%0d%0aLocation%3a%20https://attacker.example"));
  }
}
