package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.translation.provider.TranslationProviderException;
import com.novelweb.translation.provider.TranslationProviderFailure;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TranslationAttemptFailurePolicyTests {

  @Test
  void schedulesDeterministicFakeLocalTimeoutWithBoundedBackoff() {
    var decision =
        TranslationAttemptFailurePolicy.decide(
            "fake-local",
            2,
            3,
            new TranslationProviderException(TranslationProviderFailure.TIMEOUT, null));

    assertEquals("timeout", decision.outcome());
    assertEquals("TIMEOUT", decision.failureCode());
    assertTrue(decision.retryScheduled());
    assertEquals(Duration.ofSeconds(2), decision.retryDelay());
  }

  @Test
  void capsProviderSuppliedDelayForAContractuallySafeRateLimit() {
    var decision =
        TranslationAttemptFailurePolicy.decide(
            "fake-local",
            1,
            3,
            new TranslationProviderException(
                TranslationProviderFailure.RATE_LIMITED, Duration.ofHours(2)));

    assertTrue(decision.retryScheduled());
    assertEquals(Duration.ofSeconds(300), decision.retryDelay());
  }

  @Test
  void doesNotRetryTerminalFailureOrAnExhaustedAttempt() {
    var terminal =
        TranslationAttemptFailurePolicy.decide(
            "fake-local",
            1,
            3,
            new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null));
    var exhausted =
        TranslationAttemptFailurePolicy.decide(
            "fake-local",
            3,
            3,
            new TranslationProviderException(TranslationProviderFailure.TIMEOUT, null));

    assertFalse(terminal.retryScheduled());
    assertEquals("invalid_response", terminal.outcome());
    assertFalse(exhausted.retryScheduled());
    assertEquals("timeout", exhausted.outcome());
  }

  @Test
  void neverSchedulesAutomaticRetryForAProviderOutsideFakeLocal() {
    var decision =
        TranslationAttemptFailurePolicy.decide(
            "deep-l",
            1,
            3,
            new TranslationProviderException(TranslationProviderFailure.RATE_LIMITED, null));

    assertFalse(decision.retryScheduled());
    assertEquals("rate_limited", decision.outcome());
  }
}
