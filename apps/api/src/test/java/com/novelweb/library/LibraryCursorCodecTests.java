package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LibraryCursorCodecTests {

  private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void signedCursorRoundTripsAndKeepsMicrosecondSortPosition() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    LibraryCursorCodec.CursorContext context =
        LibraryCursorCodec.workContext(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "  dragon  ",
            "tiên-hiệp",
            "ACTIVE");
    LibraryCursorCodec.Position expected =
        new LibraryCursorCodec.Position(
            0,
            Instant.parse("2026-11-10T08:09:10.123456Z"),
            UUID.fromString("33333333-3333-3333-3333-333333333333"));

    String token = codec.encode(context, expected);

    assertTrue(token.length() < 256);
    assertEquals(expected, codec.decode(token, context));
  }

  @Test
  void cursorIsBoundToOwnerLibraryAndAllSearchFilters() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    UUID owner = UUID.randomUUID();
    UUID library = UUID.randomUUID();
    LibraryCursorCodec.CursorContext original =
        LibraryCursorCodec.workContext(owner, library, "dragon", "tiên-hiệp", "active");
    String token =
        codec.encode(
            original,
            new LibraryCursorCodec.Position(
                0, Instant.parse("2026-11-10T08:09:10Z"), UUID.randomUUID()));

    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.workContext(
                    UUID.randomUUID(), library, "dragon", "tiên-hiệp", "active")));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.workContext(
                    owner, UUID.randomUUID(), "dragon", "tiên-hiệp", "active")));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.workContext(owner, library, "dragon", "tiên-hiệp", "archived")));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.workContext(owner, library, "dragon", "đô thị", "active")));
  }

  @Test
  void cursorIsBoundToTheCurrentWorkspaceAccessFingerprint() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    UUID actor = UUID.randomUUID();
    UUID library = UUID.randomUUID();
    LibraryCursorCodec.CursorContext original =
        LibraryCursorCodec.workContext(actor, library, "dragon", "", "", "workspace-a:7:reader");
    LibraryCursorCodec.Position position =
        new LibraryCursorCodec.Position(
            0, Instant.parse("2026-11-10T08:09:10Z"), UUID.randomUUID());
    String token = codec.encode(original, position);

    assertEquals(position, codec.decode(token, original));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.workContext(
                    actor, library, "dragon", "", "", "workspace-a:8:reader")));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.workContext(
                    actor, library, "dragon", "", "", "workspace-a:7:editor")));

    LibraryCursorCodec.CursorContext libraryList =
        LibraryCursorCodec.libraryContext(actor, "workspace-set:version-4");
    String libraryToken = codec.encode(libraryList, position);
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                libraryToken, LibraryCursorCodec.libraryContext(actor, "workspace-set:version-5")));
  }

  @Test
  void publicCatalogCursorIsBoundToItsSearchAndLanguageFilters() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    LibraryCursorCodec.CursorContext context =
        LibraryCursorCodec.publicCatalogContext("dragon", "vi");
    LibraryCursorCodec.Position position =
        new LibraryCursorCodec.Position(
            0, Instant.parse("2026-11-10T08:09:10.123456Z"), UUID.randomUUID());
    String token = codec.encode(context, position);

    assertEquals(position, codec.decode(token, context));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(token, LibraryCursorCodec.publicCatalogContext("dragon", "en")));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(token, LibraryCursorCodec.publicCatalogContext("other", "vi")));
  }

  @Test
  void publicationRequestHistoryCursorIsBoundToOwnerAndManuscript() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    UUID ownerId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    LibraryCursorCodec.CursorContext context =
        LibraryCursorCodec.publicationRequestHistoryContext(ownerId, workId);
    LibraryCursorCodec.Position position =
        new LibraryCursorCodec.Position(
            0, Instant.parse("2026-11-17T04:10:11.123456Z"), UUID.randomUUID());
    String token = codec.encode(context, position);

    assertEquals(position, codec.decode(token, context));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.publicationRequestHistoryContext(UUID.randomUUID(), workId)));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.publicationRequestHistoryContext(ownerId, UUID.randomUUID())));
  }

  @Test
  void translationPublicationQueueCursorIsBoundToOwnerAndManuscript() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    UUID ownerId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    LibraryCursorCodec.CursorContext context =
        LibraryCursorCodec.translationPublicationQueueContext(ownerId, workId);
    LibraryCursorCodec.Position position =
        new LibraryCursorCodec.Position(
            0, Instant.parse("2026-11-17T04:10:11.123456Z"), UUID.randomUUID());
    String token = codec.encode(context, position);

    assertEquals(position, codec.decode(token, context));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.translationPublicationQueueContext(UUID.randomUUID(), workId)));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.translationPublicationQueueContext(ownerId, UUID.randomUUID())));
  }

  @Test
  void translationReleaseHistoryCursorIsBoundToOwnerAndManuscript() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    UUID ownerId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    LibraryCursorCodec.CursorContext context =
        LibraryCursorCodec.translationReleaseHistoryContext(ownerId, workId);
    LibraryCursorCodec.Position position =
        new LibraryCursorCodec.Position(
            0, Instant.parse("2026-11-17T04:10:11.123456Z"), UUID.randomUUID());
    String token = codec.encode(context, position);

    assertEquals(position, codec.decode(token, context));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.translationReleaseHistoryContext(UUID.randomUUID(), workId)));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token,
                LibraryCursorCodec.translationReleaseHistoryContext(ownerId, UUID.randomUUID())));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () ->
            codec.decode(
                token, LibraryCursorCodec.translationPublicationQueueContext(ownerId, workId)));
  }

  @Test
  void notificationCursorIsBoundToActorAndInboxSort() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    UUID actorId = UUID.randomUUID();
    LibraryCursorCodec.CursorContext context = LibraryCursorCodec.notificationsContext(actorId);
    LibraryCursorCodec.Position position =
        new LibraryCursorCodec.Position(
            0, Instant.parse("2026-11-18T00:00:00.123456Z"), UUID.randomUUID());
    String cursor = codec.encode(context, position);

    assertEquals(position, codec.decode(cursor, context));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(cursor, LibraryCursorCodec.notificationsContext(UUID.randomUUID())));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(cursor, LibraryCursorCodec.workSubscriptionsContext(actorId)));
  }

  @Test
  void rejectsTamperedNonCanonicalAndUnconfiguredCursors() {
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    LibraryCursorCodec.CursorContext context = LibraryCursorCodec.libraryContext(UUID.randomUUID());
    String token =
        codec.encode(
            context,
            new LibraryCursorCodec.Position(
                1, Instant.parse("2026-11-10T08:09:10Z"), UUID.randomUUID()));
    int separator = token.indexOf('.');
    char lastSignatureCharacter = token.charAt(token.length() - 1);
    char replacement = lastSignatureCharacter == 'A' ? 'B' : 'A';
    String tampered = token.substring(0, token.length() - 1) + replacement;

    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class, () -> codec.decode(tampered, context));
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () -> codec.decode(token.substring(0, separator) + ".%%%", context));
    assertThrows(
        LibraryCursorCodec.SigningUnavailableException.class,
        () ->
            new LibraryCursorCodec("")
                .encode(
                    context, new LibraryCursorCodec.Position(0, Instant.now(), UUID.randomUUID())));
    assertThrows(IllegalArgumentException.class, () -> new LibraryCursorCodec("not-a-key"));
  }
}
