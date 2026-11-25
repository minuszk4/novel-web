package com.novelweb.library.reader;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class ReaderService {

  private static final int MAX_MANIFEST_LIMIT = 100;
  private static final int MAX_CONTENT_LIMIT = 1000;

  private final ReaderRepository repository;
  private final ReaderCursorCodec cursorCodec;

  public ReaderService(ReaderRepository repository, ReaderCursorCodec cursorCodec) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
  }

  public ReaderManifest manifest(UUID actorUserId, UUID workId, String cursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    validateLimit(limit, MAX_MANIFEST_LIMIT);
    cursorCodec.requireConfigured();
    ReaderRepository.ReaderWork work =
        repository
            .findAccessibleWork(actorUserId, workId)
            .orElseThrow(ReaderNotFoundException::new);
    ReaderCursorCodec.Context context = ReaderCursorCodec.manifestContext(actorUserId, workId);
    int afterOrdinal = cursor == null ? 0 : cursorCodec.decode(cursor, context);
    ReaderRepository.Page<ReaderRepository.ReaderChapter> page =
        repository.findChaptersAfter(actorUserId, workId, afterOrdinal, limit);
    String nextCursor = page.hasMore() ? cursorCodec.encode(context, page.lastOrdinal()) : null;
    return new ReaderManifest(
        new ReaderWorkSummary(
            work.id(),
            work.libraryId(),
            work.title(),
            work.author(),
            work.language(),
            work.visibility(),
            work.chapterCount(),
            work.createdAt()),
        work.language(),
        page.items().stream()
            .map(
                chapter ->
                    new ReaderChapterSummary(
                        chapter.id(), chapter.ordinal(), chapter.title(), chapter.accessState()))
            .toList(),
        nextCursor);
  }

  public ChapterContent content(
      UUID actorUserId, UUID chapterId, UUID requestedRevisionId, String cursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    validateLimit(limit, MAX_CONTENT_LIMIT);
    cursorCodec.requireConfigured();
    ReaderRepository.ReadableChapter chapter =
        repository
            .findReadableChapter(actorUserId, chapterId, requestedRevisionId)
            .orElseThrow(ReaderNotFoundException::new);
    ReaderCursorCodec.Context context =
        ReaderCursorCodec.contentContext(actorUserId, chapterId, chapter.revisionId());
    int afterOrdinal = cursor == null ? 0 : cursorCodec.decode(cursor, context);
    ReaderRepository.Page<ReaderRepository.SourceSegment> page =
        repository.findSegmentsAfter(
            actorUserId, chapterId, chapter.revisionId(), afterOrdinal, limit);
    String nextCursor = page.hasMore() ? cursorCodec.encode(context, page.lastOrdinal()) : null;
    return new ChapterContent(
        chapter.id(),
        chapter.workId(),
        chapter.revisionId(),
        chapter.language(),
        chapter.accessState(),
        chapter.previewPercent(),
        page.items().stream()
            .map(
                segment ->
                    new SourceSegment(segment.anchor(), segment.type(), segment.sourceText()))
            .toList(),
        nextCursor);
  }

  private static void validateLimit(int limit, int maximum) {
    if (limit < 1 || limit > maximum) {
      throw new IllegalArgumentException("limit must be between 1 and " + maximum);
    }
  }

  public record ReaderManifest(
      ReaderWorkSummary work,
      String sourceLanguage,
      List<ReaderChapterSummary> chapters,
      String nextCursor) {
    public ReaderManifest {
      chapters = List.copyOf(chapters);
    }
  }

  public record ReaderWorkSummary(
      UUID id,
      UUID libraryId,
      String title,
      String author,
      String language,
      String visibility,
      int chapterCount,
      java.time.Instant createdAt) {}

  public record ReaderChapterSummary(UUID id, int ordinal, String title, String accessState) {}

  public record ChapterContent(
      UUID chapterId,
      UUID workId,
      UUID sourceRevisionId,
      String sourceLanguage,
      String accessState,
      Integer previewPercent,
      List<SourceSegment> segments,
      String nextSegmentCursor) {
    public ChapterContent {
      segments = List.copyOf(segments);
    }
  }

  public record SourceSegment(String anchor, String type, String sourceText) {}

  public static class ReaderNotFoundException extends RuntimeException {}
}
