package com.novelweb.library.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReaderBookmarkServiceTests {

  private static final String HMAC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void normalizesLabelAndFingerprintsValidatedBookmarkRequest() {
    ReaderBookmarkRepository repository = mock(ReaderBookmarkRepository.class);
    ReaderBookmarkService service =
        new ReaderBookmarkService(repository, new ReaderCursorCodec(HMAC_KEY));
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID bookmarkId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-11-11T10:00:00Z");
    ReaderBookmarkRepository.BookmarkRecord expected =
        new ReaderBookmarkRepository.BookmarkRecord(
            bookmarkId, workId, chapterId, "stable-anchor", "Saved passage", createdAt);
    when(repository.create(
            eq(actorId),
            eq(workId),
            eq(chapterId),
            eq("stable-anchor"),
            eq("Saved passage"),
            eq("retry-key-00000001"),
            any(byte[].class)))
        .thenReturn(expected);

    ReaderBookmarkRepository.BookmarkRecord actual =
        service.create(
            actorId,
            workId,
            "retry-key-00000001",
            new ReaderBookmarkService.CreateBookmarkRequest(
                chapterId, "stable-anchor", "  Saved passage  "));

    assertEquals(expected, actual);
    ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
    verify(repository)
        .create(
            eq(actorId),
            eq(workId),
            eq(chapterId),
            eq("stable-anchor"),
            eq("Saved passage"),
            eq("retry-key-00000001"),
            hash.capture());
    assertEquals(32, hash.getValue().length);
  }

  @Test
  void rejectsMissingOrUnknownRequestFieldsAndInvalidValuesBeforePersistence() {
    ReaderBookmarkRepository repository = mock(ReaderBookmarkRepository.class);
    ReaderBookmarkService service =
        new ReaderBookmarkService(repository, new ReaderCursorCodec(HMAC_KEY));
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();

    assertThrows(
        ReaderBookmarkService.InvalidBookmarkRequestException.class,
        () ->
            service.create(
                actorId, workId, "short", new ReaderBookmarkService.CreateBookmarkRequest()));
    assertThrows(
        ReaderBookmarkService.InvalidBookmarkRequestException.class,
        () ->
            service.create(
                actorId,
                workId,
                "retry-key-00000001",
                new ReaderBookmarkService.CreateBookmarkRequest(chapterId, null, "  ")));
    assertThrows(
        ReaderBookmarkService.InvalidBookmarkRequestException.class,
        () ->
            service.create(
                actorId,
                workId,
                "retry-key-00000001",
                new ReaderBookmarkService.CreateBookmarkRequest(
                    chapterId, "anchor with spaces", "Valid label")));

    ReaderBookmarkService.CreateBookmarkRequest unknown =
        new ReaderBookmarkService.CreateBookmarkRequest(chapterId, null, "Valid label");
    unknown.captureUnknownField("trackingId", "must-not-persist");
    assertThrows(
        ReaderBookmarkService.InvalidBookmarkRequestException.class,
        () -> service.create(actorId, workId, "retry-key-00000001", unknown));
    verifyNoInteractions(repository);
  }

  @Test
  void signsNextBookmarkCursorFromLastItemAndBoundsPageSize() {
    ReaderBookmarkRepository repository = mock(ReaderBookmarkRepository.class);
    ReaderBookmarkService service =
        new ReaderBookmarkService(repository, new ReaderCursorCodec(HMAC_KEY));
    UUID actorId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-11-11T10:00:00Z");
    ReaderBookmarkRepository.BookmarkRecord first =
        new ReaderBookmarkRepository.BookmarkRecord(
            UUID.randomUUID(), workId, UUID.randomUUID(), null, "one", createdAt);
    ReaderBookmarkRepository.BookmarkRecord second =
        new ReaderBookmarkRepository.BookmarkRecord(
            UUID.randomUUID(), workId, UUID.randomUUID(), "anchor", "two", createdAt);
    when(repository.findForWork(actorId, workId, null, 2))
        .thenReturn(new ReaderBookmarkRepository.Page(List.of(first, second), true));

    ReaderBookmarkService.BookmarkList page = service.list(actorId, workId, null, 2);

    assertEquals(List.of(first, second), page.items());
    assertTrue(page.nextCursor() != null && !page.nextCursor().isBlank());
    ReaderCursorCodec codec = new ReaderCursorCodec(HMAC_KEY);
    assertEquals(
        new ReaderCursorCodec.KeysetPosition(second.createdAt(), second.id()),
        codec.decodeKeyset(page.nextCursor(), ReaderCursorCodec.bookmarksContext(actorId, workId)));
    assertThrows(IllegalArgumentException.class, () -> service.list(actorId, workId, null, 101));
  }
}
