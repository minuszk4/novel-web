package com.novelweb.library;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class PublicCatalogService {

  private static final int MAX_LIMIT = 50;
  private static final int MAX_AFTER_ORDINAL = 2_000_000_000;
  private static final int MAX_QUERY_CODE_POINTS = 120;
  private static final Pattern LANGUAGE_TAG = Pattern.compile("(?i)[a-z]{2,3}(?:-[a-z0-9]{2,8})*");

  private final PublicCatalogRepository repository;
  private final LibraryCursorCodec cursorCodec;

  public PublicCatalogService(PublicCatalogRepository repository, LibraryCursorCodec cursorCodec) {
    this.repository = Objects.requireNonNull(repository, "catalog repository is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursor codec is required");
  }

  public Page list(String requestedQuery, String requestedLanguage, String cursor, int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new InvalidCatalogQueryException("limit must be an integer between 1 and 50");
    }
    String query = normalizeQuery(requestedQuery);
    String language = normalizeLanguage(requestedLanguage);
    cursorCodec.requireConfigured();
    LibraryCursorCodec.CursorContext context =
        LibraryCursorCodec.publicCatalogContext(query, language);
    LibraryCursorCodec.Position position =
        cursor == null || cursor.isBlank() ? null : cursorCodec.decode(cursor, context);
    PublicCatalogRepository.Slice slice = repository.findWorks(query, language, limit, position);
    String nextCursor = null;
    if (slice.hasMore() && !slice.items().isEmpty()) {
      PublicCatalogRepository.WorkSummary last = slice.items().get(slice.items().size() - 1);
      nextCursor =
          cursorCodec.encode(
              context, new LibraryCursorCodec.Position(0, last.createdAt(), last.id()));
    }
    return new Page(
        slice.items().stream()
            .map(
                item ->
                    new CatalogWork(
                        item.id(),
                        item.title(),
                        item.author(),
                        item.sourceLanguage(),
                        item.readyChapterCount()))
            .toList(),
        nextCursor);
  }

  public WorkDetail detail(UUID workId, UUID actorUserId) {
    Objects.requireNonNull(workId, "work id is required");
    return repository
        .findWork(workId)
        .map(
            work ->
                new WorkDetail(
                    work.id(),
                    work.title(),
                    work.author(),
                    work.sourceLanguage(),
                    work.readyChapterCount(),
                    actorUserId != null && repository.isOwner(workId, actorUserId)))
        .orElseThrow(PublicWorkNotFoundException::new);
  }

  public WorkDetail detail(UUID workId) {
    return detail(workId, null);
  }

  public ChapterPage chapters(UUID workId, int afterOrdinal, int limit) {
    Objects.requireNonNull(workId, "work id is required");
    if (afterOrdinal < 0 || afterOrdinal > MAX_AFTER_ORDINAL) {
      throw new InvalidCatalogQueryException("afterOrdinal must be between 0 and 2000000000");
    }
    validateLimit(limit);
    WorkDetail work = detail(workId);
    PublicCatalogRepository.ChapterSlice slice =
        repository.findReadyChapters(workId, afterOrdinal, limit);
    Integer nextAfterOrdinal = slice.hasMore() ? slice.items().getLast().ordinal() : null;
    return new ChapterPage(work.id(), slice.items(), nextAfterOrdinal);
  }

  private static void validateLimit(int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new InvalidCatalogQueryException("limit must be an integer between 1 and 50");
    }
  }

  private static String normalizeQuery(String requestedQuery) {
    if (requestedQuery == null || requestedQuery.isBlank()) return "";
    String normalized =
        Normalizer.normalize(requestedQuery.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    if (normalized.codePointCount(0, normalized.length()) > MAX_QUERY_CODE_POINTS
        || normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new InvalidCatalogQueryException("q must contain at most 120 printable characters");
    }
    return normalized;
  }

  private static String normalizeLanguage(String requestedLanguage) {
    if (requestedLanguage == null || requestedLanguage.isBlank()) return "";
    String normalized = requestedLanguage.trim().toLowerCase(Locale.ROOT);
    if (normalized.length() > 35 || !LANGUAGE_TAG.matcher(normalized).matches()) {
      throw new InvalidCatalogQueryException("language must be a valid language tag");
    }
    return normalized;
  }

  public record CatalogWork(
      UUID id, String title, String author, String sourceLanguage, int readyChapterCount) {}

  public record Page(List<CatalogWork> items, String nextCursor) {
    public Page {
      items = List.copyOf(items);
    }
  }

  public record WorkDetail(
      UUID id,
      String title,
      String author,
      String sourceLanguage,
      int readyChapterCount,
      boolean isOwner) {}

  public record ChapterPage(
      UUID workId, List<PublicCatalogRepository.PublicChapter> items, Integer nextAfterOrdinal) {
    public ChapterPage {
      items = List.copyOf(items);
    }
  }

  public static class PublicWorkNotFoundException extends RuntimeException {}

  public static class InvalidCatalogQueryException extends RuntimeException {
    public InvalidCatalogQueryException(String message) {
      super(message);
    }
  }
}
