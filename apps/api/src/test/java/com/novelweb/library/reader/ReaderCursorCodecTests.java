package com.novelweb.library.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReaderCursorCodecTests {

  private static final String HMAC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void roundTripsManifestAndContentPositions() {
    ReaderCursorCodec codec = new ReaderCursorCodec(HMAC_KEY);
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();

    String manifestCursor = codec.encode(ReaderCursorCodec.manifestContext(actorId, workId), 12);
    String contentCursor =
        codec.encode(ReaderCursorCodec.contentContext(actorId, chapterId, revisionId), 35);

    assertEquals(
        12, codec.decode(manifestCursor, ReaderCursorCodec.manifestContext(actorId, workId)));
    assertEquals(
        35,
        codec.decode(
            contentCursor, ReaderCursorCodec.contentContext(actorId, chapterId, revisionId)));
  }

  @Test
  void rejectsCursorFromAnotherActorOrResource() {
    ReaderCursorCodec codec = new ReaderCursorCodec(HMAC_KEY);
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    String cursor = codec.encode(ReaderCursorCodec.manifestContext(actorId, workId), 1);

    assertThrows(
        ReaderCursorCodec.InvalidCursorException.class,
        () -> codec.decode(cursor, ReaderCursorCodec.manifestContext(UUID.randomUUID(), workId)));
    assertThrows(
        ReaderCursorCodec.InvalidCursorException.class,
        () -> codec.decode(cursor, ReaderCursorCodec.manifestContext(actorId, UUID.randomUUID())));
  }

  @Test
  void rejectsTamperedNoncanonicalAndWrongKindCursors() {
    ReaderCursorCodec codec = new ReaderCursorCodec(HMAC_KEY);
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    String manifestCursor = codec.encode(ReaderCursorCodec.manifestContext(actorId, workId), 1);

    assertThrows(
        ReaderCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                tamperLastCharacter(manifestCursor),
                ReaderCursorCodec.manifestContext(actorId, workId)));
    assertThrows(
        ReaderCursorCodec.InvalidCursorException.class,
        () -> codec.decode("not.a.cursor", ReaderCursorCodec.manifestContext(actorId, workId)));
    assertThrows(
        ReaderCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                manifestCursor, ReaderCursorCodec.contentContext(actorId, chapterId, revisionId)));
  }

  @Test
  void requiresCanonicalThirtyTwoByteSigningKey() {
    assertThrows(IllegalArgumentException.class, () -> new ReaderCursorCodec("not-a-key"));
    assertThrows(IllegalArgumentException.class, () -> new ReaderCursorCodec("AQ"));
  }

  private static String tamperLastCharacter(String cursor) {
    char last = cursor.charAt(cursor.length() - 1);
    char replacement = last == 'A' ? 'B' : 'A';
    return cursor.substring(0, cursor.length() - 1) + replacement;
  }
}
