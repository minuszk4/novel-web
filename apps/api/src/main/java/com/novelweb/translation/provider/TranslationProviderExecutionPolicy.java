package com.novelweb.translation.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.regex.Pattern;

/** Application-owned limits applied around one provider execution. */
public record TranslationProviderExecutionPolicy(
    int maxSegmentsPerAttempt,
    long maxCodePointsPerAttempt,
    BigDecimal maxEstimatedCost,
    String currency,
    int maxRequestsPerMinute,
    int maxConcurrentCalls,
    Duration requestTimeout,
    int maxRateLimitRetries,
    Duration retryBaseDelay,
    Duration maxRetryDelay) {

  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

  public TranslationProviderExecutionPolicy {
    if (maxSegmentsPerAttempt < 1 || maxSegmentsPerAttempt > 5000) {
      throw new IllegalArgumentException("Segment limit must be between 1 and 5000");
    }
    if (maxCodePointsPerAttempt < 1 || maxCodePointsPerAttempt > 10_000_000) {
      throw new IllegalArgumentException("Code-point limit must be between 1 and 10000000");
    }
    if (maxEstimatedCost == null || maxEstimatedCost.signum() < 0 || maxEstimatedCost.scale() > 8) {
      throw new IllegalArgumentException(
          "Maximum estimated cost must be a bounded non-negative amount");
    }
    if (currency == null || !CURRENCY.matcher(currency).matches()) {
      throw new IllegalArgumentException("Currency must be an uppercase three-letter code");
    }
    if (maxRequestsPerMinute < 1 || maxRequestsPerMinute > 100_000) {
      throw new IllegalArgumentException("Request limit must be between 1 and 100000 per minute");
    }
    if (maxConcurrentCalls < 1 || maxConcurrentCalls > 256) {
      throw new IllegalArgumentException("Concurrency limit must be between 1 and 256");
    }
    if (requestTimeout == null
        || requestTimeout.isZero()
        || requestTimeout.isNegative()
        || requestTimeout.compareTo(Duration.ofMinutes(15)) > 0) {
      throw new IllegalArgumentException("Request timeout must be positive and at most 15 minutes");
    }
    if (maxRateLimitRetries < 0 || maxRateLimitRetries > 3) {
      throw new IllegalArgumentException("Rate-limit retries must be between 0 and 3");
    }
    if (retryBaseDelay == null
        || retryBaseDelay.isNegative()
        || retryBaseDelay.isZero()
        || maxRetryDelay == null
        || maxRetryDelay.compareTo(retryBaseDelay) < 0
        || maxRetryDelay.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("Retry delays must be positive and capped at one minute");
    }
  }
}
