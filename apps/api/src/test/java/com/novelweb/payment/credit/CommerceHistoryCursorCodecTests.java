package com.novelweb.payment.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.novelweb.payment.credit.CommerceHistoryCursorCodec.Position;
import com.novelweb.payment.credit.CommerceHistoryCursorCodec.Scope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommerceHistoryCursorCodecTests {

  private static final String HMAC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void cursorIsActorScopeAndFilterBound() {
    CommerceHistoryCursorCodec codec = new CommerceHistoryCursorCodec(HMAC_KEY);
    UUID actor = UUID.randomUUID();
    Position position =
        new Position(Instant.parse("2026-11-15T12:34:56.123456Z"), UUID.randomUUID());
    String token = codec.encode(actor, Scope.PAYMENT_ORDERS, "PENDING", position);

    assertEquals(position, codec.decode(actor, Scope.PAYMENT_ORDERS, "PENDING", token));
    assertThrows(
        CommerceHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(UUID.randomUUID(), Scope.PAYMENT_ORDERS, "PENDING", token));
    assertThrows(
        CommerceHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(actor, Scope.PAYMENT_ORDERS, "FAILED", token));
    assertThrows(
        CommerceHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(actor, Scope.CHAPTER_PURCHASES, "", token));
  }

  @Test
  void rejectsTamperedMalformedOversizedAndNoncanonicalTokens() {
    CommerceHistoryCursorCodec codec = new CommerceHistoryCursorCodec(HMAC_KEY);
    UUID actor = UUID.randomUUID();
    String token =
        codec.encode(
            actor,
            Scope.CHAPTER_PURCHASES,
            "",
            new Position(Instant.parse("2026-11-15T12:00:00Z"), UUID.randomUUID()));
    String changed = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

    assertThrows(
        CommerceHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(actor, Scope.CHAPTER_PURCHASES, "", changed));
    assertThrows(
        CommerceHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(actor, Scope.CHAPTER_PURCHASES, "", "bad.cursor"));
    assertThrows(
        CommerceHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(actor, Scope.CHAPTER_PURCHASES, "", "A".repeat(257)));
    assertThrows(
        CommerceHistoryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(actor, Scope.CHAPTER_PURCHASES, "", "AB==.AA"));
  }

  @Test
  void invalidKeyAndUnconfiguredSigningFailClosed() {
    assertThrows(
        IllegalArgumentException.class, () -> new CommerceHistoryCursorCodec("not-base64"));
    CommerceHistoryCursorCodec unconfigured = new CommerceHistoryCursorCodec("");
    assertThrows(
        CommerceHistoryCursorCodec.SigningUnavailableException.class,
        unconfigured::requireConfigured);
  }
}
