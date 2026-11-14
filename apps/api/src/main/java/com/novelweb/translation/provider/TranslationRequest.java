package com.novelweb.translation.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One bounded attempt. It contains source material, never provider credentials or user keys. */
public record TranslationRequest(
    UUID attemptId,
    TranslationLanguagePair languagePair,
    List<Segment> segments,
    List<GlossaryInstruction> glossaryInstructions) {

  public TranslationRequest {
    if (attemptId == null || languagePair == null || segments == null || segments.isEmpty()) {
      throw new IllegalArgumentException(
          "Translation attempt, languages and segments are required");
    }
    if (segments.size() > 5000) {
      throw new IllegalArgumentException("At most 5000 segments may be submitted per attempt");
    }
    segments = List.copyOf(segments);
    Set<UUID> segmentIds = new HashSet<>();
    for (Segment segment : segments) {
      if (!segmentIds.add(segment.id())) {
        throw new IllegalArgumentException("Segment IDs must be unique within an attempt");
      }
    }
    glossaryInstructions =
        glossaryInstructions == null ? List.of() : List.copyOf(glossaryInstructions);
    if (glossaryInstructions.size() > 500) {
      throw new IllegalArgumentException("At most 500 glossary instructions may be submitted");
    }
    for (GlossaryInstruction instruction : glossaryInstructions) {
      if (codePoints(instruction.sourceTerm()) > 512
          || (instruction.targetTerm() != null && codePoints(instruction.targetTerm()) > 4096)) {
        throw new IllegalArgumentException("Glossary instruction text exceeds its bound");
      }
    }
  }

  public long sourceCodePoints() {
    return segments.stream().mapToLong(segment -> codePoints(segment.sourceText())).sum();
  }

  public long requestCodePoints() {
    long glossaryCodePoints =
        glossaryInstructions.stream()
            .mapToLong(
                instruction ->
                    (long) codePoints(instruction.sourceTerm())
                        + (instruction.targetTerm() == null
                            ? 0
                            : codePoints(instruction.targetTerm())))
            .sum();
    return sourceCodePoints() + glossaryCodePoints;
  }

  public void validateAgainst(TranslationProviderCapabilities capabilities)
      throws TranslationProviderException {
    if (capabilities == null) {
      throw new IllegalArgumentException("Provider capabilities are required");
    }
    if (!capabilities.supports(languagePair)) {
      throw new TranslationProviderException(TranslationProviderFailure.UNSUPPORTED_LANGUAGE, null);
    }
    if (segments.size() > capabilities.maxSegmentsPerRequest()
        || requestCodePoints() > capabilities.maxCodePointsPerRequest()
        || segments.stream()
            .anyMatch(
                segment ->
                    segment.sourceText().codePointCount(0, segment.sourceText().length())
                        > capabilities.maxCodePointsPerSegment())
        || (!glossaryInstructions.isEmpty() && !capabilities.glossaryInstructionsSupported())) {
      throw new TranslationProviderException(TranslationProviderFailure.INPUT_REJECTED, null);
    }
  }

  private static int codePoints(String value) {
    return value.codePointCount(0, value.length());
  }

  @Override
  public String toString() {
    return "TranslationRequest[attemptId="
        + attemptId
        + ", languagePair="
        + languagePair
        + ", segmentCount="
        + segments.size()
        + ", glossaryInstructionCount="
        + glossaryInstructions.size()
        + "]";
  }

  public record Segment(UUID id, String sourceText) {

    public Segment {
      if (id == null || sourceText == null || sourceText.isBlank()) {
        throw new IllegalArgumentException("Segment ID and source text are required");
      }
    }

    @Override
    public String toString() {
      return "Segment[id=" + id + ", sourceCodePoints=" + codePoints(sourceText) + "]";
    }
  }

  public record GlossaryInstruction(String sourceTerm, String targetTerm, boolean doNotTranslate) {

    public GlossaryInstruction {
      if (sourceTerm == null || sourceTerm.isBlank()) {
        throw new IllegalArgumentException("Glossary source term is required");
      }
      if (!doNotTranslate && (targetTerm == null || targetTerm.isBlank())) {
        throw new IllegalArgumentException("Glossary target term is required");
      }
    }

    @Override
    public String toString() {
      return "GlossaryInstruction[doNotTranslate=" + doNotTranslate + "]";
    }
  }
}
