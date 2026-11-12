package com.novelweb.library.reader;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class ReaderAnnotationService {

  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_CODE_POINTS = 2000;
  private static final Pattern STABLE_ANCHOR =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$");
  private static final Set<String> COLORS = Set.of("amber", "mint", "sky", "rose");

  private final ReaderAnnotationRepository repository;
  private final ReaderCursorCodec cursorCodec;

  public ReaderAnnotationService(
      ReaderAnnotationRepository repository, ReaderCursorCodec cursorCodec) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
  }

  public AnnotationList list(
      UUID actorUserId, UUID workId, UUID chapterId, String cursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    validateLimit(limit);
    cursorCodec.requireConfigured();
    ReaderCursorCodec.Context context =
        ReaderCursorCodec.annotationsContext(actorUserId, workId, chapterId);
    ReaderCursorCodec.KeysetPosition after =
        cursor == null ? null : cursorCodec.decodeKeyset(cursor, context);
    ReaderAnnotationRepository.Page page =
        repository.findForWork(actorUserId, workId, chapterId, after, limit);
    String nextCursor = null;
    if (page.hasMore() && !page.items().isEmpty()) {
      ReaderAnnotationRepository.AnnotationRecord last = page.items().get(page.items().size() - 1);
      nextCursor =
          cursorCodec.encodeKeyset(
              context, new ReaderCursorCodec.KeysetPosition(last.updatedAt(), last.id()));
    }
    return new AnnotationList(page.items(), nextCursor);
  }

  public ReaderAnnotationRepository.CreateResult create(
      UUID actorUserId, UUID workId, CreateAnnotationRequest request) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (request == null || request.hasUnknownFields()) {
      throw new InvalidAnnotationRequestException("Annotation request is invalid or unsupported");
    }
    if (request.chapterId() == null
        || request.sourceRevisionId() == null
        || request.startAnchor() == null
        || request.startOffset() == null
        || request.endAnchor() == null
        || request.endOffset() == null
        || request.quoteSnapshot() == null
        || request.note() == null
        || request.color() == null) {
      throw new InvalidAnnotationRequestException(
          "All annotation range and content fields are required");
    }
    if (!STABLE_ANCHOR.matcher(request.startAnchor()).matches()
        || !STABLE_ANCHOR.matcher(request.endAnchor()).matches()) {
      throw new InvalidAnnotationRequestException(
          "Annotation anchors must be stable source anchors");
    }
    if (request.startOffset() < 0 || request.endOffset() < 0) {
      throw new InvalidAnnotationRequestException("Annotation offsets must be non-negative");
    }
    int quoteLength = codePointLength(request.quoteSnapshot());
    if (quoteLength < 1 || quoteLength > MAX_CODE_POINTS || request.quoteSnapshot().isBlank()) {
      throw new InvalidAnnotationRequestException(
          "Selected quote must contain 1 to 2000 non-whitespace Unicode code points");
    }
    validateText(request.note(), "note");
    validateColor(request.color());
    return repository.create(
        actorUserId,
        workId,
        request.chapterId(),
        request.sourceRevisionId(),
        request.startAnchor(),
        request.startOffset(),
        request.endAnchor(),
        request.endOffset(),
        request.quoteSnapshot(),
        request.note(),
        request.color());
  }

  public ReaderAnnotationRepository.AnnotationRecord update(
      UUID actorUserId, UUID annotationId, long expectedVersion, UpdateAnnotationRequest request) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(annotationId, "annotation id is required");
    if (request == null
        || request.hasUnknownFields()
        || !request.noteProvided()
        || !request.colorProvided()) {
      throw new InvalidAnnotationRequestException(
          "Both note and color are required for an annotation edit");
    }
    validateText(request.note(), "note");
    validateColor(request.color());
    return repository.update(
        actorUserId, annotationId, expectedVersion, request.note(), request.color());
  }

  public void delete(UUID actorUserId, UUID annotationId) {
    repository.delete(
        Objects.requireNonNull(actorUserId, "actor user id is required"),
        Objects.requireNonNull(annotationId, "annotation id is required"));
  }

  public void deleteForWork(UUID actorUserId, UUID workId) {
    repository.deleteForWork(
        Objects.requireNonNull(actorUserId, "actor user id is required"),
        Objects.requireNonNull(workId, "work id is required"));
  }

  private static void validateLimit(int limit) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
    }
  }

  private static void validateColor(String color) {
    if (!COLORS.contains(color)) {
      throw new InvalidAnnotationRequestException("Color must be amber, mint, sky or rose");
    }
  }

  private static void validateText(String value, String field) {
    if (value == null
        || codePointLength(value) > MAX_CODE_POINTS
        || containsDisallowedControl(value)) {
      throw new InvalidAnnotationRequestException(
          field + " must be at most 2000 printable characters");
    }
  }

  private static int codePointLength(String value) {
    return value.codePointCount(0, value.length());
  }

  private static boolean containsDisallowedControl(String value) {
    return value
        .codePoints()
        .anyMatch(
            codePoint ->
                Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\r'
                    && codePoint != '\t');
  }

  public record AnnotationList(
      List<ReaderAnnotationRepository.AnnotationRecord> items, String nextCursor) {
    public AnnotationList {
      items = List.copyOf(items);
    }
  }

  public static final class CreateAnnotationRequest {
    private UUID chapterId;
    private UUID sourceRevisionId;
    private String startAnchor;
    private Integer startOffset;
    private String endAnchor;
    private Integer endOffset;
    private String quoteSnapshot;
    private String note;
    private String color;
    private boolean unknownFields;

    public UUID chapterId() {
      return chapterId;
    }

    public UUID sourceRevisionId() {
      return sourceRevisionId;
    }

    public String startAnchor() {
      return startAnchor;
    }

    public Integer startOffset() {
      return startOffset;
    }

    public String endAnchor() {
      return endAnchor;
    }

    public Integer endOffset() {
      return endOffset;
    }

    public String quoteSnapshot() {
      return quoteSnapshot;
    }

    public String note() {
      return note;
    }

    public String color() {
      return color;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setChapterId(UUID value) {
      chapterId = value;
    }

    public void setSourceRevisionId(UUID value) {
      sourceRevisionId = value;
    }

    public void setStartAnchor(String value) {
      startAnchor = value;
    }

    public void setStartOffset(Integer value) {
      startOffset = value;
    }

    public void setEndAnchor(String value) {
      endAnchor = value;
    }

    public void setEndOffset(Integer value) {
      endOffset = value;
    }

    public void setQuoteSnapshot(String value) {
      quoteSnapshot = value;
    }

    public void setNote(String value) {
      note = value;
    }

    public void setColor(String value) {
      color = value;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public static final class UpdateAnnotationRequest {
    private String note;
    private String color;
    private boolean noteProvided;
    private boolean colorProvided;
    private boolean unknownFields;

    public String note() {
      return note;
    }

    public String color() {
      return color;
    }

    public boolean noteProvided() {
      return noteProvided;
    }

    public boolean colorProvided() {
      return colorProvided;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setNote(String value) {
      note = value;
      noteProvided = true;
    }

    public void setColor(String value) {
      color = value;
      colorProvided = true;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public static final class InvalidAnnotationRequestException extends RuntimeException {
    public InvalidAnnotationRequestException(String message) {
      super(message);
    }
  }
}
