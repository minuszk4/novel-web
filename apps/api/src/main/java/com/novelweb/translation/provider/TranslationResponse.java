package com.novelweb.translation.provider;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Validated output mapped to the original stable segment IDs. */
public record TranslationResponse(
    UUID attemptId,
    String providerRequestId,
    String providerModel,
    List<SegmentResult> segments,
    long billedUnits,
    TranslationBillingUnit billingUnit) {

  public TranslationResponse {
    if (attemptId == null || providerRequestId == null || providerRequestId.isBlank()) {
      throw new IllegalArgumentException("Attempt and provider request IDs are required");
    }
    if (providerRequestId.length() > 256
        || providerRequestId.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Provider request ID is invalid");
    }
    if (providerModel == null
        || providerModel.isBlank()
        || providerModel.length() > 128
        || providerModel.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Provider model is required and must be bounded");
    }
    if (segments == null || segments.isEmpty()) {
      throw new IllegalArgumentException("At least one translated segment is required");
    }
    segments = List.copyOf(segments);
    if (billedUnits < 0 || billingUnit == null) {
      throw new IllegalArgumentException("Billed units and their meter must be valid");
    }
  }

  public void validateAgainst(TranslationRequest request) throws TranslationProviderException {
    if (request == null || !attemptId.equals(request.attemptId())) {
      throw new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null);
    }
    Set<UUID> expectedIds =
        request.segments().stream().map(TranslationRequest.Segment::id).collect(Collectors.toSet());
    Set<UUID> actualIds = segments.stream().map(SegmentResult::id).collect(Collectors.toSet());
    if (segments.size() != request.segments().size()
        || actualIds.size() != segments.size()
        || !expectedIds.equals(actualIds)) {
      throw new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null);
    }
  }

  @Override
  public String toString() {
    return "TranslationResponse[attemptId="
        + attemptId
        + ", providerRequestId="
        + providerRequestId
        + ", providerModel="
        + providerModel
        + ", segmentCount="
        + segments.size()
        + ", billedUnits="
        + billedUnits
        + ", billingUnit="
        + billingUnit
        + "]";
  }

  public record SegmentResult(UUID id, String translatedText) {

    public SegmentResult {
      if (id == null || translatedText == null || translatedText.isBlank()) {
        throw new IllegalArgumentException("Translated segment ID and text are required");
      }
    }

    @Override
    public String toString() {
      return "SegmentResult[id="
          + id
          + ", translatedCodePoints="
          + translatedText.codePointCount(0, translatedText.length())
          + "]";
    }
  }
}
