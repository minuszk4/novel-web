package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TranslationChunkPlannerTests {

  @Test
  void plannerKeepsSourceOrderAndGreedilySplitsAtCodePointLimit() {
    TranslationChunkPlanner planner =
        new TranslationChunkPlanner(new TranslationChunkPlanner.Limits(3, 10, 10, 4096, 10, 1024));
    List<TranslationChunkPlanner.SourceSegment> source =
        List.of(segment(1, "one"), segment(2, "two😀"), segment(3, "last"));

    List<TranslationChunkPlanner.UnitPlan> units = planner.plan("vi", "en", source);

    assertEquals(2, units.size());
    assertEquals(1, units.get(0).unitNo());
    assertEquals(List.of(source.get(0).id(), source.get(1).id()), ids(units.get(0)));
    assertEquals(7, units.get(0).sourceCodePoints());
    assertEquals(List.of(source.get(2).id()), ids(units.get(1)));
    assertEquals(4, units.get(1).sourceCodePoints());
    assertTrue(units.stream().allMatch(unit -> unit.serializedBytes() <= 4096));
  }

  @Test
  void plannerSplitsAtSegmentCountAndProducesRepeatableUnitBoundaries() {
    TranslationChunkPlanner planner =
        new TranslationChunkPlanner(new TranslationChunkPlanner.Limits(2, 10, 20, 4096, 10, 1024));
    List<TranslationChunkPlanner.SourceSegment> source =
        List.of(segment(1, "a"), segment(2, "b"), segment(3, "c"), segment(4, "d"));

    List<TranslationChunkPlanner.UnitPlan> first = planner.plan("vi", "en", source);
    List<TranslationChunkPlanner.UnitPlan> second = planner.plan("vi", "en", source);

    assertEquals(2, first.size());
    assertEquals(
        List.of(1, 2), first.stream().map(TranslationChunkPlanner.UnitPlan::unitNo).toList());
    assertEquals(
        first.stream().map(TranslationChunkPlanner.UnitPlan::segments).toList(),
        second.stream().map(TranslationChunkPlanner.UnitPlan::segments).toList());
  }

  @Test
  void plannerRejectsAnOversizedSerializedSegmentInsteadOfReturningPartialUnits() {
    TranslationChunkPlanner planner =
        new TranslationChunkPlanner(new TranslationChunkPlanner.Limits(4, 200, 200, 512, 10, 4096));

    assertThrows(
        TranslationChunkPlanner.SegmentTooLargeException.class,
        () -> planner.plan("vi", "en", List.of(segment(1, "\u0001".repeat(100)))));
  }

  @Test
  void plannerRejectsInvalidOrderChecksumAndEmptyRevision() {
    TranslationChunkPlanner planner =
        new TranslationChunkPlanner(new TranslationChunkPlanner.Limits(4, 20, 40, 4096, 10, 1024));
    TranslationChunkPlanner.SourceSegment first = segment(1, "first");
    TranslationChunkPlanner.SourceSegment second = segment(2, "second");

    assertThrows(
        TranslationChunkPlanner.InvalidSourceSegmentsException.class,
        () -> planner.plan("vi", "en", List.of(second, first)));
    assertThrows(
        TranslationChunkPlanner.InvalidSourceSegmentsException.class,
        () ->
            planner.plan(
                "vi",
                "en",
                List.of(
                    new TranslationChunkPlanner.SourceSegment(
                        first.id(),
                        first.sourceOrdinal(),
                        first.segmentType(),
                        first.stableAnchor(),
                        first.sourceText(),
                        new byte[32]))));
    assertThrows(
        TranslationChunkPlanner.NoTranslatableSegmentsException.class,
        () -> planner.plan("vi", "en", List.of()));
  }

  @Test
  void plannerCountsUnicodeCodePointsAndRejectsUnsupportedShape() {
    TranslationChunkPlanner planner =
        new TranslationChunkPlanner(new TranslationChunkPlanner.Limits(4, 2, 2, 4096, 10, 1024));
    TranslationChunkPlanner.SourceSegment unicode = segment(1, "龙😀");

    List<TranslationChunkPlanner.UnitPlan> units = planner.plan("vi", "en", List.of(unicode));

    assertEquals(2, units.get(0).sourceCodePoints());
    TranslationChunkPlanner.SourceSegment image =
        new TranslationChunkPlanner.SourceSegment(
            UUID.randomUUID(), 2, "image_ref", "image-1", "asset", sha256("asset"));
    assertThrows(
        TranslationChunkPlanner.InvalidSourceSegmentsException.class,
        () -> planner.plan("vi", "en", List.of(unicode, image)));
    assertThrows(
        TranslationChunkPlanner.UnsupportedLanguagePairException.class,
        () -> planner.plan("zh", "zh", List.of(unicode)));
  }

  @Test
  void plannerAcceptsTheExactSerializedByteBoundaryAndRejectsOneByteOver() {
    TranslationChunkPlanner.SourceSegment source = segment(1, "😀".repeat(100));
    TranslationChunkPlanner.UnitPlan measured =
        new TranslationChunkPlanner(new TranslationChunkPlanner.Limits(2, 200, 200, 4096, 10, 1024))
            .plan("vi", "en", List.of(source))
            .getFirst();
    int exactLimit = Math.toIntExact(measured.serializedBytes());
    TranslationChunkPlanner exactPlanner =
        new TranslationChunkPlanner(
            new TranslationChunkPlanner.Limits(2, 200, 200, exactLimit, 10, 1024));

    assertEquals(
        exactLimit, exactPlanner.plan("vi", "en", List.of(source)).getFirst().serializedBytes());
    TranslationChunkPlanner oneByteShortPlanner =
        new TranslationChunkPlanner(
            new TranslationChunkPlanner.Limits(2, 200, 200, exactLimit - 1L, 10, 1024));
    assertThrows(
        TranslationChunkPlanner.SegmentTooLargeException.class,
        () -> oneByteShortPlanner.plan("vi", "en", List.of(source)));
  }

  @Test
  void plannerEnforcesWholeJobSegmentAndSourceByteLimits() {
    TranslationChunkPlanner planner =
        new TranslationChunkPlanner(new TranslationChunkPlanner.Limits(2, 10, 20, 4096, 3, 5));

    assertEquals(
        2,
        planner
            .plan("vi", "en", List.of(segment(1, "a"), segment(2, "b"), segment(3, "c")))
            .size());
    TranslationChunkPlanner.InputLimitExceededException tooManySegments =
        assertThrows(
            TranslationChunkPlanner.InputLimitExceededException.class,
            () ->
                planner.plan(
                    "vi",
                    "en",
                    List.of(segment(1, "a"), segment(2, "b"), segment(3, "c"), segment(4, "d"))));
    assertEquals("segment_count", tooManySegments.limit());

    TranslationChunkPlanner.InputLimitExceededException tooManyBytes =
        assertThrows(
            TranslationChunkPlanner.InputLimitExceededException.class,
            () -> planner.plan("vi", "en", List.of(segment(1, "龙😀"), segment(2, "abc"))));
    assertEquals("source_bytes", tooManyBytes.limit());
  }

  @Test
  void plannerHandlesTheMaximumBookSizeAndRepeatableRetryBoundaries() {
    TranslationChunkPlanner planner = new TranslationChunkPlanner();
    List<TranslationChunkPlanner.SourceSegment> source =
        IntStream.rangeClosed(1, TranslationChunkPlanner.MAX_JOB_SEGMENTS)
            .mapToObj(ordinal -> segment(ordinal, "x"))
            .toList();

    List<TranslationChunkPlanner.UnitPlan> firstAttempt = planner.plan("vi", "en", source);
    List<TranslationChunkPlanner.UnitPlan> retryPlan = planner.plan("vi", "en", source);

    assertEquals(50, firstAttempt.size());
    assertEquals(TranslationChunkPlanner.MAX_JOB_SEGMENTS, segmentCount(firstAttempt));
    assertEquals(
        firstAttempt.stream().map(TranslationChunkPlanner.UnitPlan::segments).toList(),
        retryPlan.stream().map(TranslationChunkPlanner.UnitPlan::segments).toList());
    assertEquals(
        source.stream().map(TranslationChunkPlanner.SourceSegment::id).toList(),
        firstAttempt.stream()
            .flatMap(unit -> unit.segments().stream())
            .map(TranslationChunkPlanner.SourceSegment::id)
            .toList());
    assertThrows(
        TranslationChunkPlanner.InputLimitExceededException.class,
        () -> planner.plan("vi", "en", sourceWithSegmentCount(5_001)));
  }

  @Test
  void plannerAcceptsExactTenMiBBookAndRejectsOneByteBeyond() {
    TranslationChunkPlanner planner = new TranslationChunkPlanner();
    List<TranslationChunkPlanner.SourceSegment> exactLimit = sourceForByteLimit(10_485_760);
    List<TranslationChunkPlanner.UnitPlan> plan = planner.plan("vi", "en", exactLimit);

    assertEquals(10_485_760, exactLimit.stream().mapToLong(s -> utf8Bytes(s.sourceText())).sum());
    assertEquals(53, plan.size());
    assertEquals(exactLimit.size(), segmentCount(plan));
    TranslationChunkPlanner.InputLimitExceededException overLimit =
        assertThrows(
            TranslationChunkPlanner.InputLimitExceededException.class,
            () -> planner.plan("vi", "en", sourceForByteLimit(10_485_761)));
    assertEquals("source_bytes", overLimit.limit());
  }

  private static List<UUID> ids(TranslationChunkPlanner.UnitPlan unit) {
    return unit.segments().stream().map(TranslationChunkPlanner.SourceSegment::id).toList();
  }

  private static int segmentCount(List<TranslationChunkPlanner.UnitPlan> units) {
    return units.stream().mapToInt(unit -> unit.segments().size()).sum();
  }

  private static List<TranslationChunkPlanner.SourceSegment> sourceForByteLimit(int byteCount) {
    List<TranslationChunkPlanner.SourceSegment> source = new java.util.ArrayList<>();
    int remaining = byteCount;
    while (remaining > 0) {
      int length = Math.min(10_000, remaining);
      source.add(segment(source.size() + 1, "x".repeat(length)));
      remaining -= length;
    }
    return List.copyOf(source);
  }

  private static List<TranslationChunkPlanner.SourceSegment> sourceWithSegmentCount(int count) {
    return IntStream.rangeClosed(1, count).mapToObj(ordinal -> segment(ordinal, "x")).toList();
  }

  private static long utf8Bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static TranslationChunkPlanner.SourceSegment segment(int ordinal, String text) {
    UUID id = new UUID(0, ordinal);
    return new TranslationChunkPlanner.SourceSegment(
        id, ordinal, ordinal == 1 ? "heading" : "paragraph", "p-" + ordinal, text, sha256(text));
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
