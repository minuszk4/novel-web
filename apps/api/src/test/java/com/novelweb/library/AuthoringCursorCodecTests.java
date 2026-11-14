package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthoringCursorCodecTests {

  private static final String HMAC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void roundTripsOrdinalBoundToActorAndManuscript() {
    AuthoringCursorCodec codec = new AuthoringCursorCodec(HMAC_KEY);
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();

    String cursor = codec.encode(actorId, workId, 128);

    assertEquals(128, codec.decode(cursor, actorId, workId));
  }

  @Test
  void rejectsCursorForAnotherActorOrManuscript() {
    AuthoringCursorCodec codec = new AuthoringCursorCodec(HMAC_KEY);
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    String cursor = codec.encode(actorId, workId, 1);

    assertThrows(
        AuthoringCursorCodec.InvalidCursorException.class,
        () -> codec.decode(cursor, UUID.randomUUID(), workId));
    assertThrows(
        AuthoringCursorCodec.InvalidCursorException.class,
        () -> codec.decode(cursor, actorId, UUID.randomUUID()));
  }

  @Test
  void rejectsTamperedMalformedAndNoncanonicalCursors() {
    AuthoringCursorCodec codec = new AuthoringCursorCodec(HMAC_KEY);
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    String cursor = codec.encode(actorId, workId, 1);
    char replacement = cursor.charAt(cursor.length() - 1) == 'A' ? 'B' : 'A';
    String tampered = cursor.substring(0, cursor.length() - 1) + replacement;

    assertThrows(
        AuthoringCursorCodec.InvalidCursorException.class,
        () -> codec.decode(tampered, actorId, workId));
    assertThrows(
        AuthoringCursorCodec.InvalidCursorException.class,
        () -> codec.decode("bad.cursor", actorId, workId));
    assertThrows(
        AuthoringCursorCodec.InvalidCursorException.class,
        () -> codec.decode(cursor + "=", actorId, workId));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(actorId, workId, 0));
  }

  @Test
  void requiresCanonicalThirtyTwoByteSigningKey() {
    assertThrows(IllegalArgumentException.class, () -> new AuthoringCursorCodec("not-a-key"));
    assertThrows(IllegalArgumentException.class, () -> new AuthoringCursorCodec("AQ"));
  }

  @Test
  void missingSigningConfigurationFailsClosed() {
    AuthoringCursorCodec codec = new AuthoringCursorCodec("");
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();

    assertThrows(AuthoringCursorCodec.SigningUnavailableException.class, codec::requireConfigured);
    assertThrows(
        AuthoringCursorCodec.SigningUnavailableException.class,
        () -> codec.encode(actorId, workId, 1));
  }
}
