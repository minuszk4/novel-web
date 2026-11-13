package com.novelweb.progression.cultivation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CultivationActivityCursorCodecTests {

  private static final String HMAC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void roundTripsActorBoundActivityDate() {
    CultivationActivityCursorCodec codec = new CultivationActivityCursorCodec(HMAC_KEY);
    UUID actorId = UUID.randomUUID();
    LocalDate beforeDate = LocalDate.of(2026, 11, 1);

    String cursor = codec.encode(actorId, beforeDate);

    assertEquals(beforeDate, codec.decode(cursor, actorId));
    assertThrows(
        CultivationActivityCursorCodec.InvalidCursorException.class,
        () -> codec.decode(cursor, UUID.randomUUID()));
  }

  @Test
  void rejectsMalformedOrTamperedTokens() {
    CultivationActivityCursorCodec codec = new CultivationActivityCursorCodec(HMAC_KEY);
    UUID actorId = UUID.randomUUID();
    String cursor = codec.encode(actorId, LocalDate.of(2026, 11, 1));
    String[] parts = cursor.split("\\.");
    String changedPayload = (parts[0].startsWith("A") ? "B" : "A") + parts[0].substring(1);

    assertThrows(
        CultivationActivityCursorCodec.InvalidCursorException.class,
        () -> codec.decode("not-a-cursor", actorId));
    assertThrows(
        CultivationActivityCursorCodec.InvalidCursorException.class,
        () -> codec.decode(changedPayload + "." + parts[1], actorId));
  }

  @Test
  void failsClosedWhenTheSigningKeyIsMissing() {
    CultivationActivityCursorCodec codec = new CultivationActivityCursorCodec("");

    assertThrows(
        CultivationActivityCursorCodec.SigningUnavailableException.class,
        () -> codec.encode(UUID.randomUUID(), LocalDate.of(2026, 11, 1)));
  }
}
