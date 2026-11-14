package com.novelweb.translation.provider;

import java.time.Duration;
import java.util.Set;

/** Runtime-advertised limits; callers must check them before quoting or submitting work. */
public record TranslationProviderCapabilities(
    Set<TranslationLanguagePair> supportedLanguagePairs,
    int maxSegmentsPerRequest,
    int maxCodePointsPerSegment,
    int maxCodePointsPerRequest,
    int requestsPerMinute,
    Duration requestTimeout,
    boolean glossaryInstructionsSupported) {

  public TranslationProviderCapabilities {
    if (supportedLanguagePairs == null || supportedLanguagePairs.isEmpty()) {
      throw new IllegalArgumentException("At least one supported language pair is required");
    }
    supportedLanguagePairs = Set.copyOf(supportedLanguagePairs);
    if (maxSegmentsPerRequest < 1 || maxSegmentsPerRequest > 5000) {
      throw new IllegalArgumentException("Maximum segments must be between 1 and 5000");
    }
    if (maxCodePointsPerSegment < 1 || maxCodePointsPerSegment > 1_000_000) {
      throw new IllegalArgumentException("Maximum segment size must be between 1 and 1000000");
    }
    if (maxCodePointsPerRequest < maxCodePointsPerSegment || maxCodePointsPerRequest > 10_000_000) {
      throw new IllegalArgumentException(
          "Maximum request size must cover one segment and be at most 10000000");
    }
    if (requestsPerMinute < 1 || requestsPerMinute > 1_000_000) {
      throw new IllegalArgumentException("Request rate must be between 1 and 1000000 per minute");
    }
    if (requestTimeout == null
        || requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(Duration.ofMinutes(15)) > 0) {
      throw new IllegalArgumentException(
          "Provider timeout must be positive and at most 15 minutes");
    }
  }

  public boolean supports(TranslationLanguagePair pair) {
    return supportedLanguagePairs.contains(pair);
  }
}
