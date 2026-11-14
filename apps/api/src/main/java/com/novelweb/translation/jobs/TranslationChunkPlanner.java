package com.novelweb.translation.jobs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Builds deterministic, bounded provider requests from one immutable revision's segments. */
@Component
@Profile("postgres")
public final class TranslationChunkPlanner {

  private static final String REQUEST_PREFIX =
      "{\"attemptId\":\"00000000-0000-0000-0000-000000000000\","
          + "\"languagePair\":{\"sourceLanguage\":";
  private static final String REQUEST_TARGET_PREFIX = ",\"targetLanguage\":";
  private static final String REQUEST_SEGMENTS_PREFIX = "},\"segments\":[";
  private static final String REQUEST_SUFFIX = "],\"glossaryInstructions\":[]}";
  private static final String SEGMENT_PREFIX = "{\"id\":";
  private static final String SEGMENT_TEXT_PREFIX = ",\"sourceText\":";
  private static final String SEGMENT_SUFFIX = "}";
  public static final int MAX_JOB_SEGMENTS = 5000;
  public static final long MAX_JOB_SOURCE_BYTES = 10_485_760;
  private static final Limits DEFAULT_LIMITS =
      new Limits(100, 10_000, 200_000, 1_048_576, MAX_JOB_SEGMENTS, MAX_JOB_SOURCE_BYTES);

  private final Limits limits;

  public TranslationChunkPlanner() {
    this(DEFAULT_LIMITS);
  }

  TranslationChunkPlanner(Limits limits) {
    this.limits = Objects.requireNonNull(limits, "chunk limits are required");
  }

  public List<UnitPlan> plan(
      String sourceLanguage, String targetLanguage, List<SourceSegment> sourceSegments) {
    validateLanguage(sourceLanguage);
    validateLanguage(targetLanguage);
    if (sourceLanguage.equalsIgnoreCase(targetLanguage)) {
      throw new UnsupportedLanguagePairException();
    }
    if (!(sourceLanguage.equalsIgnoreCase("vi") && targetLanguage.equalsIgnoreCase("en"))
        && !(sourceLanguage.equalsIgnoreCase("en") && targetLanguage.equalsIgnoreCase("vi"))) {
      throw new UnsupportedLanguagePairException();
    }
    if (sourceSegments == null || sourceSegments.isEmpty()) {
      throw new NoTranslatableSegmentsException();
    }
    if (sourceSegments.size() > limits.maxTotalSegments()) {
      throw new InputLimitExceededException("segment_count");
    }

    List<MeasuredSegment> measured = new ArrayList<>(sourceSegments.size());
    Set<UUID> seenIds = new HashSet<>();
    int previousOrdinal = 0;
    long totalSourceBytes = 0;
    for (SourceSegment segment : sourceSegments) {
      validateSegment(segment, seenIds, previousOrdinal);
      previousOrdinal = segment.sourceOrdinal();
      int codePoints = segment.sourceText().codePointCount(0, segment.sourceText().length());
      byte[] sourceBytes = segment.sourceText().getBytes(StandardCharsets.UTF_8);
      if (codePoints > limits.maxCodePointsPerSegment()) {
        throw new SegmentTooLargeException(segment.sourceOrdinal(), "code_points");
      }
      totalSourceBytes += sourceBytes.length;
      if (totalSourceBytes > limits.maxTotalSourceBytes()) {
        throw new InputLimitExceededException("source_bytes");
      }
      byte[] expectedChecksum = sha256(sourceBytes);
      if (!MessageDigest.isEqual(expectedChecksum, segment.checksum())) {
        throw new InvalidSourceSegmentsException("segment_checksum");
      }
      long serializedSegmentBytes =
          utf8Length(SEGMENT_PREFIX)
              + jsonStringUpperBound(segment.id().toString())
              + utf8Length(SEGMENT_TEXT_PREFIX)
              + jsonStringUpperBound(segment.sourceText())
              + utf8Length(SEGMENT_SUFFIX);
      measured.add(new MeasuredSegment(segment, codePoints, serializedSegmentBytes));
    }

    long requestBaseBytes =
        utf8Length(REQUEST_PREFIX)
            + jsonStringUpperBound(sourceLanguage)
            + utf8Length(REQUEST_TARGET_PREFIX)
            + jsonStringUpperBound(targetLanguage)
            + utf8Length(REQUEST_SEGMENTS_PREFIX)
            + utf8Length(REQUEST_SUFFIX);
    List<UnitPlan> units = new ArrayList<>();
    List<SourceSegment> currentSegments = new ArrayList<>(limits.maxSegmentsPerUnit());
    long currentCodePoints = 0;
    long currentSerializedBytes = requestBaseBytes;
    for (MeasuredSegment segment : measured) {
      long commaBytes = currentSegments.isEmpty() ? 0 : 1;
      boolean exceedsUnit =
          currentSegments.size() >= limits.maxSegmentsPerUnit()
              || currentCodePoints + segment.codePoints() > limits.maxCodePointsPerUnit()
              || currentSerializedBytes + commaBytes + segment.serializedBytes()
                  > limits.maxSerializedBytesPerUnit();
      if (exceedsUnit && !currentSegments.isEmpty()) {
        units.add(
            unit(units.size() + 1, currentSegments, currentCodePoints, currentSerializedBytes));
        currentSegments = new ArrayList<>(limits.maxSegmentsPerUnit());
        currentCodePoints = 0;
        currentSerializedBytes = requestBaseBytes;
        commaBytes = 0;
      }
      if (segment.codePoints() > limits.maxCodePointsPerUnit()
          || currentSerializedBytes + commaBytes + segment.serializedBytes()
              > limits.maxSerializedBytesPerUnit()) {
        throw new SegmentTooLargeException(segment.source().sourceOrdinal(), "unit_payload");
      }
      currentSegments.add(segment.source());
      currentCodePoints += segment.codePoints();
      currentSerializedBytes += commaBytes + segment.serializedBytes();
    }
    if (!currentSegments.isEmpty()) {
      units.add(unit(units.size() + 1, currentSegments, currentCodePoints, currentSerializedBytes));
    }
    return List.copyOf(units);
  }

  private static void validateSegment(
      SourceSegment segment, Set<UUID> seenIds, int previousOrdinal) {
    if (segment == null
        || segment.id() == null
        || segment.sourceOrdinal() < 1
        || segment.sourceOrdinal() <= previousOrdinal
        || segment.segmentType() == null
        || !Set.of("paragraph", "heading", "quote").contains(segment.segmentType())
        || segment.stableAnchor() == null
        || segment.sourceText() == null
        || segment.sourceText().isBlank()
        || segment.checksum() == null
        || segment.checksum().length != 32
        || !seenIds.add(segment.id())) {
      throw new InvalidSourceSegmentsException("segment_order_or_shape");
    }
  }

  private static void validateLanguage(String language) {
    if (language == null || !language.matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) {
      throw new UnsupportedLanguagePairException();
    }
  }

  private static UnitPlan unit(
      int unitNo, List<SourceSegment> segments, long codePoints, long serializedBytes) {
    return new UnitPlan(unitNo, List.copyOf(segments), codePoints, serializedBytes);
  }

  private static long utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  /** Upper bound for JSON string bytes, including quotes and control/non-ASCII escaping. */
  private static long jsonStringUpperBound(String value) {
    long bytes = 2;
    for (int offset = 0; offset < value.length(); ) {
      int codePoint = value.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (codePoint == '"' || codePoint == '\\') bytes += 2;
      else if (codePoint <= 0x1f) bytes += 6;
      else if (codePoint <= 0x7f) bytes++;
      else if (codePoint <= 0xffff) bytes += 6;
      else bytes += 12;
    }
    return bytes;
  }

  private static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record Limits(
      int maxSegmentsPerUnit,
      int maxCodePointsPerSegment,
      int maxCodePointsPerUnit,
      long maxSerializedBytesPerUnit,
      int maxTotalSegments,
      long maxTotalSourceBytes) {
    public Limits {
      if (maxSegmentsPerUnit < 1
          || maxSegmentsPerUnit > 5000
          || maxCodePointsPerSegment < 1
          || maxCodePointsPerSegment > 1_000_000
          || maxCodePointsPerUnit < maxCodePointsPerSegment
          || maxCodePointsPerUnit > 10_000_000
          || maxSerializedBytesPerUnit < 512
          || maxSerializedBytesPerUnit > 10_485_760
          || maxTotalSegments < maxSegmentsPerUnit
          || maxTotalSegments > 5000
          || maxTotalSourceBytes < 1
          || maxTotalSourceBytes > 10_485_760) {
        throw new IllegalArgumentException("invalid translation chunk limits");
      }
    }
  }

  public record SourceSegment(
      UUID id,
      int sourceOrdinal,
      String segmentType,
      String stableAnchor,
      String sourceText,
      byte[] checksum) {
    public SourceSegment {
      checksum = checksum == null ? null : checksum.clone();
    }

    @Override
    public byte[] checksum() {
      return checksum == null ? null : checksum.clone();
    }
  }

  public record UnitPlan(
      int unitNo, List<SourceSegment> segments, long sourceCodePoints, long serializedBytes) {
    public UnitPlan {
      segments = List.copyOf(segments);
    }
  }

  private record MeasuredSegment(SourceSegment source, int codePoints, long serializedBytes) {}

  public static final class NoTranslatableSegmentsException extends RuntimeException {}

  public static final class InputLimitExceededException extends RuntimeException {
    private final String limit;

    public InputLimitExceededException(String limit) {
      this.limit = limit;
    }

    public String limit() {
      return limit;
    }
  }

  public static final class SegmentTooLargeException extends RuntimeException {
    private final int sourceOrdinal;
    private final String limit;

    public SegmentTooLargeException(int sourceOrdinal, String limit) {
      this.sourceOrdinal = sourceOrdinal;
      this.limit = limit;
    }

    public int sourceOrdinal() {
      return sourceOrdinal;
    }

    public String limit() {
      return limit;
    }
  }

  public static final class InvalidSourceSegmentsException extends RuntimeException {
    private final String reason;

    public InvalidSourceSegmentsException(String reason) {
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }

  public static final class UnsupportedLanguagePairException extends RuntimeException {}
}
