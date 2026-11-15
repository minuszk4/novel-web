package com.novelweb.library;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class LibraryService {

  private final LibraryRepository repository;
  private final LibraryCursorCodec cursorCodec;

  public LibraryService(LibraryRepository repository, LibraryCursorCodec cursorCodec) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
  }

  public LibraryRepository.LibraryRecord createLibrary(UUID ownerUserId, String requestedName) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    String name = requiredText(requestedName, "name", 120);
    return repository.createLibrary(ownerUserId, name);
  }

  public LibraryRepository.LibraryRecord createWorkspaceLibrary(
      UUID actorUserId, UUID workspaceId, String requestedName) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    String name = requiredText(requestedName, "name", 120);
    return repository.createWorkspaceLibrary(actorUserId, workspaceId, name);
  }

  public LibraryRepository.LibraryRecord movePersonalLibraryToWorkspace(
      UUID actorUserId, UUID libraryId, UUID workspaceId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    return repository.movePersonalLibraryToWorkspace(actorUserId, libraryId, workspaceId);
  }

  public List<LibraryRepository.LibraryRecord> listLibraries(UUID ownerUserId) {
    return listLibraries(ownerUserId, null, 100).items();
  }

  public Page<LibraryRepository.LibraryRecord> listLibraries(
      UUID ownerUserId, String cursor, int requestedLimit) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    validateLimit(requestedLimit);
    cursorCodec.requireConfigured();
    String accessScopeFingerprint = repository.libraryListAccessFingerprint(ownerUserId);
    LibraryCursorCodec.CursorContext context =
        LibraryCursorCodec.libraryContext(ownerUserId, accessScopeFingerprint);
    LibraryCursorCodec.Position position = decodeCursor(cursor, context);
    LibraryRepository.Slice<LibraryRepository.LibraryRecord> slice =
        repository.findLibraries(ownerUserId, requestedLimit, position);
    return page(slice, context);
  }

  public LibraryRepository.WorkRecord createPrivateImportedWork(
      UUID ownerUserId,
      UUID libraryId,
      String requestedTitle,
      String requestedAuthor,
      String requestedLanguage,
      byte[] sourceHash) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    String title = requiredText(requestedTitle, "title", 300);
    String author = optionalText(requestedAuthor, "author", 200);
    String language = requiredText(requestedLanguage, "language", 35);
    if (sourceHash == null || sourceHash.length != 32) {
      throw new IllegalArgumentException("source hash must be a 32-byte SHA-256 digest");
    }
    if (language.length() < 2)
      throw new IllegalArgumentException("language must be at least 2 characters");
    return repository.createPrivateImportedWork(
        ownerUserId, libraryId, title, author, language, sourceHash.clone());
  }

  public LibraryRepository.WorkRecord createPrivateAuthoredWork(
      UUID ownerUserId,
      String requestedTitle,
      String requestedAuthor,
      String requestedLanguage,
      String idempotencyKey) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    String title = requiredText(requestedTitle, "title", 300);
    String author = requestedAuthor == null ? "" : optionalText(requestedAuthor, "author", 200);
    String language = requiredText(requestedLanguage, "language", 35);
    if (language.length() < 2)
      throw new IllegalArgumentException("language must be at least 2 characters");
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{16,128}")) {
      throw new IllegalArgumentException("Idempotency-Key must contain 16 to 128 safe characters");
    }
    return repository.createPrivateAuthoredWork(
        ownerUserId,
        title,
        author,
        language,
        idempotencyKey,
        authoredWorkRequestHash(title, author, language));
  }

  public LibraryRepository.ChapterRecord createPrivateAuthoredChapter(
      UUID ownerUserId, UUID workId, String requestedTitle, String idempotencyKey) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    String title = requestedTitle == null ? "" : requestedTitle.trim();
    int titleLength = title.codePointCount(0, title.length());
    if (titleLength < 1 || titleLength > 300) {
      throw new IllegalArgumentException("title must contain between 1 and 300 characters");
    }
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{16,128}")) {
      throw new IllegalArgumentException("Idempotency-Key must contain 16 to 128 safe characters");
    }
    return repository.createPrivateAuthoredChapter(
        ownerUserId, workId, title, idempotencyKey, authoredChapterRequestHash(title));
  }

  public LibraryRepository.ChapterOutlinePage listPrivateAuthoredChapters(
      UUID ownerUserId, UUID workId, int afterOrdinal, int limit) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    return repository.listPrivateAuthoredChapters(ownerUserId, workId, afterOrdinal, limit);
  }

  public LibraryRepository.AuthoredWorkPage listPrivateAuthoredWorks(
      UUID ownerUserId, Instant beforeCreatedAt, UUID beforeWorkId, int limit) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    return repository.listPrivateAuthoredWorks(ownerUserId, beforeCreatedAt, beforeWorkId, limit);
  }

  public LibraryRepository.PrivateAuthoredWorkDetails getPrivateAuthoredWorkDetails(
      UUID ownerUserId, UUID workId) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    return repository.getPrivateAuthoredWorkDetails(ownerUserId, workId);
  }

  private static byte[] authoredWorkRequestHash(String title, String author, String language) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String value : List.of(title, author, language)) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static byte[] authoredChapterRequestHash(String title) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = title.getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
      return digest.digest();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public Optional<LibraryRepository.WorkRecord> findOwnedWork(UUID ownerUserId, UUID workId) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    return repository.findWork(ownerUserId, workId);
  }

  public List<LibraryRepository.WorkRecord> listWorks(
      UUID ownerUserId, UUID libraryId, String query, int requestedLimit) {
    return listWorks(ownerUserId, libraryId, query, null, null, null, requestedLimit).items();
  }

  public Page<LibraryRepository.WorkRecord> listWorks(
      UUID ownerUserId,
      UUID libraryId,
      String requestedQuery,
      String requestedTag,
      String requestedStatus,
      String cursor,
      int requestedLimit) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    String normalizedQuery = requestedQuery == null ? "" : requestedQuery.trim();
    if (normalizedQuery.length() > 160) {
      throw new IllegalArgumentException("query must be at most 160 characters");
    }
    String tag = requestedTag == null || requestedTag.isBlank() ? "" : tagSlug(requestedTag);
    String status =
        requestedStatus == null || requestedStatus.isBlank()
            ? ""
            : requestedStatus.trim().toLowerCase(Locale.ROOT);
    if (!status.isEmpty() && !Set.of("active", "archived").contains(status)) {
      throw new IllegalArgumentException("status must be active or archived");
    }
    validateLimit(requestedLimit);
    cursorCodec.requireConfigured();
    String accessScopeFingerprint = repository.workAccessFingerprint(ownerUserId, libraryId);
    LibraryCursorCodec.CursorContext context =
        LibraryCursorCodec.workContext(
            ownerUserId, libraryId, normalizedQuery, tag, status, accessScopeFingerprint);
    LibraryCursorCodec.Position position = decodeCursor(cursor, context);
    LibraryRepository.Slice<LibraryRepository.WorkRecord> slice =
        repository.findWorks(
            ownerUserId, libraryId, normalizedQuery, tag, status, requestedLimit, position);
    return page(slice, context);
  }

  public LibraryRepository.TagRecord addTagToWork(
      UUID ownerUserId, UUID workId, String requestedName) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    String name = Normalizer.normalize(requiredText(requestedName, "tag", 80), Normalizer.Form.NFC);
    String slug = tagSlug(name);
    return repository.addTagToWork(ownerUserId, workId, slug, name);
  }

  private LibraryCursorCodec.Position decodeCursor(
      String cursor, LibraryCursorCodec.CursorContext context) {
    return cursor == null ? null : cursorCodec.decode(cursor, context);
  }

  private <T> Page<T> page(
      LibraryRepository.Slice<T> slice, LibraryCursorCodec.CursorContext context) {
    String nextCursor = slice.hasMore() ? cursorCodec.encode(context, slice.nextPosition()) : null;
    return new Page<>(slice.items(), nextCursor);
  }

  private static void validateLimit(int limit) {
    if (limit < 1 || limit > 100)
      throw new IllegalArgumentException("limit must be between 1 and 100");
  }

  private static String tagSlug(String value) {
    String normalized =
        Normalizer.normalize(requiredText(value, "tag", 80), Normalizer.Form.NFC)
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s-]+", "-");
    if (normalized.length() > 80 || !normalized.matches("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*")) {
      throw new IllegalArgumentException(
          "tag must contain letters or digits separated by spaces or hyphens");
    }
    return normalized;
  }

  public LibraryRepository.VolumeRecord createVolume(
      UUID ownerUserId, UUID workId, int ordinal, String requestedTitle) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (ordinal < 1) throw new IllegalArgumentException("ordinal must be positive");
    return repository.createVolume(
        ownerUserId, workId, ordinal, requiredText(requestedTitle, "title", 300));
  }

  public LibraryRepository.ChapterRecord createChapter(
      UUID ownerUserId,
      UUID workId,
      UUID volumeId,
      int ordinal,
      String requestedTitle,
      String requestedLanguage) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (ordinal < 1) throw new IllegalArgumentException("ordinal must be positive");
    String language = requiredText(requestedLanguage, "language", 35);
    if (language.length() < 2)
      throw new IllegalArgumentException("language must be at least 2 characters");
    return repository.createChapter(
        ownerUserId,
        workId,
        volumeId,
        ordinal,
        requiredText(requestedTitle, "title", 300),
        language);
  }

  public LibraryRepository.RevisionRecord appendChapterRevision(
      UUID ownerUserId,
      UUID chapterId,
      String requestedFormat,
      String normalizedContent,
      List<NewSegment> requestedSegments) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    ValidatedRevision revision =
        validateRevision(requestedFormat, normalizedContent, requestedSegments);
    return repository.appendChapterRevision(
        ownerUserId,
        chapterId,
        revision.sourceFormat(),
        revision.normalizedContent(),
        sha256(revision.normalizedContent()),
        revision.segments());
  }

  public LibraryRepository.DraftRevisionWrite savePrivateAuthoredDraftRevision(
      UUID ownerUserId,
      UUID workId,
      UUID chapterId,
      int expectedRevisionNo,
      String idempotencyKey,
      String requestedFormat,
      String normalizedContent,
      List<NewSegment> requestedSegments) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    if (expectedRevisionNo < 0) throw new IllegalArgumentException("revision must not be negative");
    String key = requiredText(idempotencyKey, "idempotency key", 128);
    if (!key.matches("[A-Za-z0-9._~-]{16,128}")) {
      throw new IllegalArgumentException("Idempotency-Key must contain 16 to 128 safe characters");
    }
    ValidatedRevision revision =
        validateRevision(requestedFormat, normalizedContent, requestedSegments);
    byte[] requestHash = draftRequestHash(expectedRevisionNo, revision);
    return repository.savePrivateAuthoredDraftRevision(
        ownerUserId,
        workId,
        chapterId,
        expectedRevisionNo,
        key,
        requestHash,
        revision.sourceFormat(),
        revision.normalizedContent(),
        sha256(revision.normalizedContent()),
        revision.segments());
  }

  public LibraryRepository.PrivateAuthoredDraftRecord getPrivateAuthoredDraft(
      UUID ownerUserId, UUID workId, UUID chapterId) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    return repository
        .findPrivateAuthoredDraft(ownerUserId, workId, chapterId)
        .orElseThrow(LibraryRepository.ChapterNotFoundException::new);
  }

  public Optional<LibraryRepository.ChapterRecord> findOwnedChapter(
      UUID ownerUserId, UUID chapterId) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    return repository.findChapter(ownerUserId, chapterId);
  }

  public LibraryRepository.AssetRecord registerAsset(
      UUID ownerUserId,
      UUID libraryId,
      String storageKey,
      byte[] contentHash,
      String requestedMediaType,
      long byteSize,
      String requestedOriginalName) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    if (contentHash == null || contentHash.length != 32) {
      throw new IllegalArgumentException("asset content hash must be a 32-byte SHA-256 digest");
    }
    if (byteSize < 1 || byteSize > 5L * 1024 * 1024 * 1024) {
      throw new IllegalArgumentException("asset size must be between 1 byte and 5 GiB");
    }
    String normalizedKey = requiredText(storageKey, "storage key", 512);
    if (normalizedKey.startsWith("/")
        || normalizedKey.contains("\\")
        || normalizedKey.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("storage key must be a safe relative object key");
    }
    for (String part : normalizedKey.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals("..")) {
        throw new IllegalArgumentException("storage key must not contain traversal segments");
      }
    }
    String mediaType =
        requiredText(requestedMediaType, "media type", 160).toLowerCase(java.util.Locale.ROOT);
    if (!mediaType.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) {
      throw new IllegalArgumentException("media type must be a concrete type/subtype value");
    }
    String originalName = requiredText(requestedOriginalName, "original name", 512);
    return repository.registerAsset(
        ownerUserId,
        libraryId,
        normalizedKey,
        contentHash.clone(),
        mediaType,
        byteSize,
        originalName);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static ValidatedRevision validateRevision(
      String requestedFormat, String normalizedContent, List<NewSegment> requestedSegments) {
    String sourceFormat = requiredText(requestedFormat, "source format", 24);
    if (!Set.of("plain_text", "markdown", "xhtml", "epub_xhtml").contains(sourceFormat)) {
      throw new IllegalArgumentException("unsupported source format");
    }
    Objects.requireNonNull(normalizedContent, "normalized content is required");
    int normalizedBytes = normalizedContent.getBytes(StandardCharsets.UTF_8).length;
    if (normalizedBytes == 0 || normalizedBytes > 10_485_760) {
      throw new IllegalArgumentException(
          "normalized content must be non-empty and not exceed 10 MiB");
    }
    List<NewSegment> sourceSegments =
        requestedSegments == null ? List.of() : List.copyOf(requestedSegments);
    if (sourceSegments.size() > 10_000) throw new IllegalArgumentException("too many segments");
    List<LibraryRepository.SegmentInput> segments = new ArrayList<>(sourceSegments.size());
    Set<String> anchors = new HashSet<>();
    long totalSegmentBytes = 0;
    for (int index = 0; index < sourceSegments.size(); index++) {
      NewSegment segment = Objects.requireNonNull(sourceSegments.get(index), "segment is required");
      if (segment.ordinal() != index + 1) {
        throw new IllegalArgumentException("segment ordinals must be contiguous and start at one");
      }
      String type = requiredText(segment.type(), "segment type", 16);
      if (!Set.of("paragraph", "heading", "quote", "image_ref").contains(type)) {
        throw new IllegalArgumentException("unsupported segment type");
      }
      String sourceText =
          Objects.requireNonNull(segment.sourceText(), "segment source text is required");
      int segmentBytes = sourceText.getBytes(StandardCharsets.UTF_8).length;
      if (segmentBytes > 1_048_576) {
        throw new IllegalArgumentException("segment source text exceeds 1 MiB");
      }
      totalSegmentBytes += segmentBytes;
      if (totalSegmentBytes > 10_485_760) {
        throw new IllegalArgumentException("all segment source text must not exceed 10 MiB");
      }
      String anchor = requiredText(segment.stableAnchor(), "stable anchor", 160);
      if (!anchor.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}") || !anchors.add(anchor)) {
        throw new IllegalArgumentException(
            "stable anchors must be unique and use the supported format");
      }
      segments.add(
          new LibraryRepository.SegmentInput(
              segment.ordinal(), type, sourceText, anchor, sha256(sourceText)));
    }
    return new ValidatedRevision(sourceFormat, normalizedContent, List.copyOf(segments));
  }

  private static byte[] draftRequestHash(int expectedRevisionNo, ValidatedRevision revision) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(expectedRevisionNo).array());
      updateHashField(digest, revision.sourceFormat());
      updateHashField(digest, revision.normalizedContent());
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(revision.segments().size()).array());
      for (LibraryRepository.SegmentInput segment : revision.segments()) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(segment.ordinal()).array());
        updateHashField(digest, segment.type());
        updateHashField(digest, segment.sourceText());
        updateHashField(digest, segment.stableAnchor());
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void updateHashField(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  private static String requiredText(String value, String field, int maxLength) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty() || normalized.length() > maxLength) {
      throw new IllegalArgumentException(
          field + " is required and must not exceed " + maxLength + " characters");
    }
    return normalized;
  }

  private static String optionalText(String value, String field, int maxLength) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
    }
    return normalized;
  }

  public record NewSegment(int ordinal, String type, String sourceText, String stableAnchor) {}

  private record ValidatedRevision(
      String sourceFormat,
      String normalizedContent,
      List<LibraryRepository.SegmentInput> segments) {}

  public record Page<T>(List<T> items, String nextCursor) {
    public Page {
      items = List.copyOf(items);
    }
  }
}
