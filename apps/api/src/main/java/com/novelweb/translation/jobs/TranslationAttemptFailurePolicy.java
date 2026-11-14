package com.novelweb.translation.jobs;

import com.novelweb.translation.provider.TranslationProviderException;
import com.novelweb.translation.provider.TranslationProviderFailure;
import java.time.Duration;
import java.util.Objects;

/** Maps sanitized provider failure classes to durable outcomes and bounded fake-local backoff. */
public final class TranslationAttemptFailurePolicy {

  private static final long BASE_RETRY_SECONDS = 1;
  private static final long MAX_RETRY_SECONDS = 300;

  private TranslationAttemptFailurePolicy() {}

  public static FailureDecision decide(
      String providerId, int attemptNo, int maxAttempts, TranslationProviderException failure) {
    Objects.requireNonNull(failure, "provider failure is required");
    if (attemptNo < 1 || maxAttempts < 1 || attemptNo > maxAttempts) {
      throw new IllegalArgumentException("attempt number is outside its allowed range");
    }

    TranslationProviderFailure kind = failure.failure();
    String outcome = outcome(kind);
    boolean retryable =
        "fake-local".equals(providerId)
            && (kind == TranslationProviderFailure.RATE_LIMITED
                || kind == TranslationProviderFailure.TEMPORARY
                || kind == TranslationProviderFailure.TIMEOUT);
    boolean retryScheduled = retryable && attemptNo < maxAttempts;
    Duration retryDelay = retryScheduled ? retryDelay(attemptNo, failure.retryAfter(), kind) : null;
    return new FailureDecision(outcome, kind.name(), retryScheduled, retryDelay);
  }

  private static String outcome(TranslationProviderFailure failure) {
    return switch (failure) {
      case TEMPORARY -> "temporary";
      case TIMEOUT -> "timeout";
      case RATE_LIMITED -> "rate_limited";
      case QUOTA_EXCEEDED -> "quota_exceeded";
      case COST_LIMIT_EXCEEDED -> "cost_limit_exceeded";
      case BILLING_LIMIT_EXCEEDED -> "billing_limit_exceeded";
      case BILLING_DISABLED -> "billing_disabled";
      case UNSUPPORTED_LANGUAGE -> "unsupported_language";
      case INPUT_REJECTED -> "input_rejected";
      case INVALID_RESPONSE -> "invalid_response";
    };
  }

  private static Duration retryDelay(
      int attemptNo, Duration retryAfter, TranslationProviderFailure kind) {
    if (kind == TranslationProviderFailure.RATE_LIMITED && retryAfter != null) {
      return retryAfter.compareTo(Duration.ofSeconds(MAX_RETRY_SECONDS)) > 0
          ? Duration.ofSeconds(MAX_RETRY_SECONDS)
          : retryAfter.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : retryAfter;
    }
    long backoff = BASE_RETRY_SECONDS << Math.min(attemptNo - 1, 8);
    return Duration.ofSeconds(Math.min(backoff, MAX_RETRY_SECONDS));
  }

  public record FailureDecision(
      String outcome, String failureCode, boolean retryScheduled, Duration retryDelay) {
    public FailureDecision {
      Objects.requireNonNull(outcome, "attempt outcome is required");
      Objects.requireNonNull(failureCode, "failure code is required");
      if (retryScheduled != (retryDelay != null)) {
        throw new IllegalArgumentException("retry delay must match the scheduled retry state");
      }
    }
  }
}
