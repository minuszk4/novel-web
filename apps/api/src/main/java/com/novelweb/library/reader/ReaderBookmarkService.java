package com.novelweb.library.reader;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class ReaderBookmarkService {

  private static final int MAX_PAGE_SIZE = 100;
  private static final Pattern STABLE_ANCHOR =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$");
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._~-]{16,128}$");

  private final ReaderBookmarkRepository repository;
  private final ReaderCursorCodec cursorCodec;

  public ReaderBookmarkService(ReaderBookmarkRepository repository, ReaderCursorCodec cursorCodec) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
  }

  public BookmarkList list(UUID actorUserId, UUID workId, String cursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    validateLimit(limit);
    cursorCodec.requireConfigured();
    ReaderCursorCodec.Context context = ReaderCursorCodec.bookmarksContext(actorUserId, workId);
    ReaderCursorCodec.KeysetPosition after =
        cursor == null ? null : cursorCodec.decodeKeyset(cursor, context);
    ReaderBookmarkRepository.Page page = repository.findForWork(actorUserId, workId, after, limit);
    String nextCursor = null;
    if (page.hasMore() && !page.items().isEmpty()) {
      ReaderBookmarkRepository.BookmarkRecord last = page.items().get(page.items().size() - 1);
      nextCursor =
          cursorCodec.encodeKeyset(
              context, new ReaderCursorCodec.KeysetPosition(last.createdAt(), last.id()));
    }
    return new BookmarkList(page.items(), nextCursor);
  }

  public ReaderBookmarkRepository.BookmarkRecord create(
      UUID actorUserId, UUID workId, String idempotencyKey, CreateBookmarkRequest request) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new InvalidBookmarkRequestException(
          "Idempotency-Key must contain 16 to 128 safe characters");
    }
    if (request == null || request.hasUnknownFields()) {
      throw new InvalidBookmarkRequestException(
          "Bookmark request is invalid or contains an unsupported field");
    }
    if (request.chapterId() == null
        || !request.segmentAnchorProvided()
        || request.label() == null) {
      throw new InvalidBookmarkRequestException("chapterId, segmentAnchor and label are required");
    }
    String label = request.label().trim();
    int labelLength = label.codePointCount(0, label.length());
    if (labelLength < 1 || labelLength > 160 || containsControlCharacter(label)) {
      throw new InvalidBookmarkRequestException(
          "label must contain between 1 and 160 printable characters");
    }
    String anchor = request.segmentAnchor();
    if (anchor != null && !STABLE_ANCHOR.matcher(anchor).matches()) {
      throw new InvalidBookmarkRequestException("segmentAnchor must be a stable source anchor");
    }
    return repository.create(
        actorUserId,
        workId,
        request.chapterId(),
        anchor,
        label,
        idempotencyKey,
        requestHash(request.chapterId(), anchor, label));
  }

  public void delete(UUID actorUserId, UUID bookmarkId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(bookmarkId, "bookmark id is required");
    repository.delete(actorUserId, bookmarkId);
  }

  private static void validateLimit(int limit) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
    }
  }

  private static byte[] requestHash(UUID chapterId, String anchor, String label) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeLong(chapterId.getMostSignificantBits());
        output.writeLong(chapterId.getLeastSignificantBits());
        output.writeBoolean(anchor != null);
        if (anchor != null) output.writeUTF(anchor);
        output.writeUTF(label);
      }
      return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Unable to fingerprint Reader bookmark request", exception);
    }
  }

  private static boolean containsControlCharacter(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }

  public record BookmarkList(
      List<ReaderBookmarkRepository.BookmarkRecord> items, String nextCursor) {
    public BookmarkList {
      items = List.copyOf(items);
    }
  }

  public static final class CreateBookmarkRequest {
    private UUID chapterId;
    private String segmentAnchor;
    private String label;
    private boolean segmentAnchorProvided;
    private boolean unknownFields;

    public CreateBookmarkRequest() {}

    public CreateBookmarkRequest(UUID chapterId, String segmentAnchor, String label) {
      this.chapterId = chapterId;
      this.segmentAnchor = segmentAnchor;
      this.label = label;
      this.segmentAnchorProvided = true;
    }

    public UUID chapterId() {
      return chapterId;
    }

    public String segmentAnchor() {
      return segmentAnchor;
    }

    public String label() {
      return label;
    }

    public boolean segmentAnchorProvided() {
      return segmentAnchorProvided;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setChapterId(UUID chapterId) {
      this.chapterId = chapterId;
    }

    public void setSegmentAnchor(String segmentAnchor) {
      this.segmentAnchor = segmentAnchor;
      this.segmentAnchorProvided = true;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public static final class InvalidBookmarkRequestException extends RuntimeException {
    public InvalidBookmarkRequestException(String message) {
      super(message);
    }
  }
}
