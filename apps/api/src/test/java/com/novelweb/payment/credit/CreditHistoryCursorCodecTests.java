package com.novelweb.payment.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditHistoryCursorCodecTests {

  private static final String HMAC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void signedPositionRoundTripsForItsActor() {
    CreditHistoryCursorCodec codec = new CreditHistoryCursorCodec(HMAC_KEY);
    UUID actor = UUID.randomUUID();
    CreditHistoryCursorCodec.Position position =
        new CreditHistoryCursorCodec.Position(
            Instant.parse("2026-11-15T12:34:56.123456Z"), UUID.randomUUID());

    String token = codec.encode(actor, position);

    assertEquals(position, codec.decode(actor, token));
  }

  @Test
  void rejectsAnotherActorTamperingMalformedAndOversizedTokens() {
    CreditHistoryCursorCodec codec = new CreditHistoryCursorCodec(HMAC_KEY);
    UUID actor = UUID.randomUUID();
    String token =
        codec.encode(
            actor,
            new CreditHistoryCursorCodec.Position(
                Instant.parse("2026-11-15T12:00:00Z"), UUID.randomUUID()));

    assertThrows(
        CreditHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(UUID.randomUUID(), token));
    assertThrows(
        CreditHistoryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                actor, token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A")));
    assertThrows(
        CreditHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(actor, "bad.cursor"));
    assertThrows(
        CreditHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(actor, "A".repeat(257)));
  }

  @Test
  void rejectsInvalidKeyAndRequiresConfigurationBeforeSigning() {
    assertThrows(IllegalArgumentException.class, () -> new CreditHistoryCursorCodec("not-base64"));
    CreditHistoryCursorCodec unconfigured = new CreditHistoryCursorCodec("");
    assertThrows(
        CreditHistoryCursorCodec.SigningUnavailableException.class,
        unconfigured::requireConfigured);
  }
}
