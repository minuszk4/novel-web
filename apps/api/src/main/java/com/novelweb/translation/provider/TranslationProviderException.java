package com.novelweb.translation.provider;

import java.time.Duration;

/** Exception contract deliberately excludes raw provider messages and response bodies. */
public final class TranslationProviderException extends Exception {

  private static final long serialVersionUID = 1L;

  private final TranslationProviderFailure failure;
  private final Duration retryAfter;

  public TranslationProviderException(TranslationProviderFailure failure, Duration retryAfter) {
    super(safeMessage(failure));
    if (failure == null) throw new IllegalArgumentException("Provider failure is required");
    if (retryAfter != null && (retryAfter.isNegative() || retryAfter.isZero())) {
      throw new IllegalArgumentException("Retry delay must be positive when present");
    }
    this.failure = failure;
    this.retryAfter = retryAfter;
  }

  public TranslationProviderFailure failure() {
    return failure;
  }

  public Duration retryAfter() {
    return retryAfter;
  }

  private static String safeMessage(TranslationProviderFailure failure) {
    return failure == null ? "Translation provider failed" : "Translation provider: " + failure;
  }
}
