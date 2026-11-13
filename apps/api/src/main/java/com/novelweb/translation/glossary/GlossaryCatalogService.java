package com.novelweb.translation.glossary;

import com.novelweb.library.LibraryRepository;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates glossary catalog commands and binds pagination to the actor's visible scope. */
@Service
@Profile("postgres")
public class GlossaryCatalogService {

  private final GlossaryCatalogRepository repository;
  private final GlossaryCursorCodec cursorCodec;
  private final LibraryRepository libraryRepository;

  public GlossaryCatalogService(
      GlossaryCatalogRepository repository,
      GlossaryCursorCodec cursorCodec,
      LibraryRepository libraryRepository) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
    this.libraryRepository = libraryRepository;
  }

  public GlossaryCatalogRepository.GlossaryRecord create(
      UUID actorUserId, CreateRequest requested) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(requested, "glossary request is required");
    if (requested.scope() == null) throw new IllegalArgumentException("scope is required");
    String name = requiredText(requested.name(), "name", 120);
    String sourceLanguage = languageTag(requested.sourceLanguage(), "sourceLanguage");
    String targetLanguage = languageTag(requested.targetLanguage(), "targetLanguage");
    validateScopeTargets(requested);
    return repository.createGlossary(
        actorUserId,
        new GlossaryCatalogRepository.NewGlossary(
            name,
            requested.scope(),
            sourceLanguage,
            targetLanguage,
            requested.libraryId(),
            requested.workId(),
            requested.volumeId(),
            requested.chapterId()));
  }

  public Page list(
      UUID actorUserId,
      String requestedScope,
      UUID libraryId,
      String requestedQuery,
      String cursor,
      int requestedLimit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (requestedLimit < 1 || requestedLimit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100");
    }
    GlossaryMatcher.Scope scope = parseScope(requestedScope);
    if (scope == GlossaryMatcher.Scope.USER_GLOBAL && libraryId != null) {
      throw new IllegalArgumentException("libraryId cannot be used with USER_GLOBAL scope");
    }
    String query = normalizeQuery(requestedQuery);
    cursorCodec.requireConfigured();
    String accessFingerprint = libraryRepository.libraryListAccessFingerprint(actorUserId);
    GlossaryCursorCodec.CursorContext context =
        new GlossaryCursorCodec.CursorContext(
            actorUserId, scope, libraryId, query, accessFingerprint);
    GlossaryCursorCodec.Position position =
        cursor == null ? null : cursorCodec.decode(cursor, context);
    GlossaryCatalogRepository.Slice slice =
        repository.findVisibleGlossaries(
            actorUserId, scope, libraryId, query, position, requestedLimit);
    String nextCursor = slice.hasMore() ? cursorCodec.encode(context, slice.nextPosition()) : null;
    return new Page(slice.items(), nextCursor);
  }

  private static GlossaryMatcher.Scope parseScope(String requestedScope) {
    if (requestedScope == null || requestedScope.isBlank()) return null;
    try {
      return GlossaryMatcher.Scope.valueOf(requestedScope.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("scope must be a supported glossary scope");
    }
  }

  private static String normalizeQuery(String requestedQuery) {
    if (requestedQuery == null || requestedQuery.isBlank()) return "";
    String query =
        Normalizer.normalize(requestedQuery, Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT);
    if (codePointLength(query) > 160)
      throw new IllegalArgumentException("q must be at most 160 characters");
    if (query.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("q must not contain control characters");
    }
    return query;
  }

  private static void validateScopeTargets(CreateRequest request) {
    boolean valid =
        switch (request.scope()) {
          case USER_GLOBAL ->
              request.libraryId() == null
                  && request.workId() == null
                  && request.volumeId() == null
                  && request.chapterId() == null;
          case LIBRARY ->
              request.libraryId() != null
                  && request.workId() == null
                  && request.volumeId() == null
                  && request.chapterId() == null;
          case WORK ->
              request.libraryId() != null
                  && request.workId() != null
                  && request.volumeId() == null
                  && request.chapterId() == null;
          case VOLUME ->
              request.libraryId() != null
                  && request.workId() != null
                  && request.volumeId() != null
                  && request.chapterId() == null;
          case CHAPTER ->
              request.libraryId() != null
                  && request.workId() != null
                  && request.volumeId() == null
                  && request.chapterId() != null;
        };
    if (!valid) throw new IllegalArgumentException("scope target IDs do not match glossary scope");
  }

  private static String languageTag(String value, String field) {
    String tag = requiredText(value, field, 35);
    if (codePointLength(tag) < 2)
      throw new IllegalArgumentException(field + " must be a BCP 47 language tag");
    try {
      return new Locale.Builder().setLanguageTag(tag).build().toLanguageTag();
    } catch (java.util.IllformedLocaleException exception) {
      throw new IllegalArgumentException(field + " must be a BCP 47 language tag");
    }
  }

  private static String requiredText(String value, String field, int maxLength) {
    if (value == null) throw new IllegalArgumentException(field + " is required");
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    int length = codePointLength(normalized);
    if (length < 1 || length > maxLength) {
      throw new IllegalArgumentException(field + " must contain 1–" + maxLength + " characters");
    }
    if (normalized.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " must not contain control characters");
    }
    return normalized;
  }

  private static int codePointLength(String value) {
    return value.codePointCount(0, value.length());
  }

  public record CreateRequest(
      String name,
      GlossaryMatcher.Scope scope,
      String sourceLanguage,
      String targetLanguage,
      UUID libraryId,
      UUID workId,
      UUID volumeId,
      UUID chapterId) {}

  public record Page(List<GlossaryCatalogRepository.GlossaryRecord> items, String nextCursor) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
