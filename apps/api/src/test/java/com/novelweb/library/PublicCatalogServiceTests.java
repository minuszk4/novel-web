package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicCatalogServiceTests {

  private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  @Test
  void normalizesFiltersAndIssuesFilterBoundKeysetCursor() {
    PublicCatalogRepository repository = mock(PublicCatalogRepository.class);
    LibraryCursorCodec codec = new LibraryCursorCodec(TEST_KEY);
    UUID firstId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-11-16T07:08:09.123456Z");
    when(repository.findWorks("dragon", "vi", 1, null))
        .thenReturn(
            new PublicCatalogRepository.Slice(
                List.of(
                    new PublicCatalogRepository.WorkSummary(
                        firstId, "Dragon", "Author", "vi", 3, createdAt)),
                true));
    PublicCatalogService service = new PublicCatalogService(repository, codec);

    PublicCatalogService.Page page = service.list(" Dragon ", "VI", null, 1);

    assertEquals(1, page.items().size());
    assertNotNull(page.nextCursor());
    assertEquals(
        new LibraryCursorCodec.Position(0, createdAt, firstId),
        codec.decode(page.nextCursor(), LibraryCursorCodec.publicCatalogContext("dragon", "vi")));
    verify(repository).findWorks("dragon", "vi", 1, null);
    verifyNoMoreInteractions(repository);
    assertThrows(
        LibraryCursorCodec.InvalidCursorException.class,
        () -> service.list("other", "vi", page.nextCursor(), 1));
  }

  @Test
  void rejectsOutOfRangeLimitsAndInvalidLanguageTagsBeforeQuerying() {
    PublicCatalogRepository repository = mock(PublicCatalogRepository.class);
    PublicCatalogService service =
        new PublicCatalogService(repository, new LibraryCursorCodec(TEST_KEY));

    assertThrows(
        PublicCatalogService.InvalidCatalogQueryException.class,
        () -> service.list(null, null, null, 51));
    assertThrows(
        PublicCatalogService.InvalidCatalogQueryException.class,
        () -> service.list(null, "vi!", null, 20));
    verifyNoMoreInteractions(repository);
  }

  @Test
  void returnsMetadataOnlyDetailsAndOrdinalContinuationForReadyChapters() {
    PublicCatalogRepository repository = mock(PublicCatalogRepository.class);
    UUID workId = UUID.randomUUID();
    when(repository.findWork(workId))
        .thenReturn(
            Optional.of(
                new PublicCatalogRepository.PublicWork(workId, "Dragon", "Author", "vi", 3)));
    when(repository.findReadyChapters(workId, 0, 2))
        .thenReturn(
            new PublicCatalogRepository.ChapterSlice(
                List.of(
                    new PublicCatalogRepository.PublicChapter(1, "First"),
                    new PublicCatalogRepository.PublicChapter(2, "Second")),
                true));
    PublicCatalogService service =
        new PublicCatalogService(repository, new LibraryCursorCodec(TEST_KEY));

    assertEquals(
        new PublicCatalogService.WorkDetail(workId, "Dragon", "Author", "vi", 3, false),
        service.detail(workId));
    assertEquals(
        new PublicCatalogService.ChapterPage(
            workId,
            List.of(
                new PublicCatalogRepository.PublicChapter(1, "First"),
                new PublicCatalogRepository.PublicChapter(2, "Second")),
            2),
        service.chapters(workId, 0, 2));
    verify(repository, times(2)).findWork(workId);
    verify(repository).findReadyChapters(workId, 0, 2);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void acceptsAnEmptyChapterPageAtTheEndOfAValidPublicWork() {
    PublicCatalogRepository repository = mock(PublicCatalogRepository.class);
    UUID workId = UUID.randomUUID();
    when(repository.findWork(workId))
        .thenReturn(
            Optional.of(
                new PublicCatalogRepository.PublicWork(workId, "Dragon", "Author", "vi", 1)));
    when(repository.findReadyChapters(workId, 1_000, 20))
        .thenReturn(new PublicCatalogRepository.ChapterSlice(List.of(), false));
    PublicCatalogService service =
        new PublicCatalogService(repository, new LibraryCursorCodec(TEST_KEY));

    assertEquals(
        new PublicCatalogService.ChapterPage(workId, List.of(), null),
        service.chapters(workId, 1_000, 20));
  }

  @Test
  void exposesOnlyTheBooleanOwnerHintForTheCurrentPublicOwner() {
    PublicCatalogRepository repository = mock(PublicCatalogRepository.class);
    UUID workId = UUID.randomUUID();
    UUID ownerId = UUID.randomUUID();
    when(repository.findWork(workId))
        .thenReturn(
            Optional.of(
                new PublicCatalogRepository.PublicWork(workId, "Dragon", "Author", "vi", 1)));
    when(repository.isOwner(workId, ownerId)).thenReturn(true);
    PublicCatalogService service =
        new PublicCatalogService(repository, new LibraryCursorCodec(TEST_KEY));

    assertEquals(
        new PublicCatalogService.WorkDetail(workId, "Dragon", "Author", "vi", 1, true),
        service.detail(workId, ownerId));
    verify(repository).findWork(workId);
    verify(repository).isOwner(workId, ownerId);
    verifyNoMoreInteractions(repository);
  }
}
