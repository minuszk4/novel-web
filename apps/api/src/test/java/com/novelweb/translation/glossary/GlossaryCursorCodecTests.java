package com.novelweb.translation.glossary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GlossaryCursorCodecTests {

  private static final String KEY =
      Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

  @Test
  void signedCursorRoundTripsForItsActorFilterAndAccessScope() {
    GlossaryCursorCodec codec = new GlossaryCursorCodec(KEY);
    GlossaryCursorCodec.CursorContext context = context(UUID.randomUUID(), "wuxia");
    GlossaryCursorCodec.Position position =
        new GlossaryCursorCodec.Position(Instant.parse("2026-11-14T00:00:00Z"), UUID.randomUUID());

    String token = codec.encode(context, position);

    assertEquals(position, codec.decode(token, context));
  }

  @Test
  void signedCursorRejectsTamperingActorFilterAndWorkspaceScopeChanges() {
    GlossaryCursorCodec codec = new GlossaryCursorCodec(KEY);
    UUID actorId = UUID.randomUUID();
    GlossaryCursorCodec.CursorContext context = context(actorId, "wuxia");
    String token =
        codec.encode(
            context,
            new GlossaryCursorCodec.Position(
                Instant.parse("2026-11-14T00:00:00Z"), UUID.randomUUID()));
    String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

    assertThrows(
        GlossaryCursorCodec.InvalidCursorException.class, () -> codec.decode(tampered, context));
    assertThrows(
        GlossaryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(token, context(UUID.randomUUID(), "wuxia")));
    assertThrows(
        GlossaryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(token, context(actorId, "different")));
    GlossaryCursorCodec.CursorContext changedAccess =
        new GlossaryCursorCodec.CursorContext(actorId, null, null, "wuxia", "new-role-version");
    assertThrows(
        GlossaryCursorCodec.InvalidCursorException.class, () -> codec.decode(token, changedAccess));
  }

  @Test
  void missingSigningKeyFailsClosed() {
    GlossaryCursorCodec codec = new GlossaryCursorCodec("");
    assertThrows(GlossaryCursorCodec.SigningUnavailableException.class, codec::requireConfigured);
  }

  private static GlossaryCursorCodec.CursorContext context(UUID actorId, String query) {
    return new GlossaryCursorCodec.CursorContext(actorId, null, null, query, "workspace-access-v1");
  }
}
