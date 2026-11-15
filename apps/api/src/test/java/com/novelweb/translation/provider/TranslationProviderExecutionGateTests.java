package com.novelweb.translation.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.platform.ProviderCallMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class TranslationProviderExecutionGateTests {

  private static final TranslationLanguagePair VI_EN = new TranslationLanguagePair("vi", "en");

  @Test
  void executesWithinLimitsAndReturnsValidatedOutput() throws Exception {
    FakeTranslationProvider provider = new FakeTranslationProvider();
    TranslationRequest request = request("Xin chào.");

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 0, 100))) {
      var result = gate.execute(request);

      assertEquals(BigDecimal.ZERO, result.estimate().amount());
      assertEquals(1, result.translationAttempts());
      assertEquals(
          "[fake vi->en] Xin chào.", result.response().segments().getFirst().translatedText());
    }
  }

  @Test
  void doesNotRejectSequentialProviderCallsDuringWorkerThreadHandoff() throws Exception {
    AtomicInteger translateCalls = new AtomicInteger();
    TranslationProvider provider =
        provider(
            BigDecimal.ZERO,
            request -> {
              translateCalls.incrementAndGet();
              return response(request, "translated", request.sourceCodePoints());
            });
    TranslationProviderExecutionPolicy base = policy("0.01", 0, 100);
    TranslationProviderExecutionPolicy oneConcurrentCall =
        new TranslationProviderExecutionPolicy(
            base.maxSegmentsPerAttempt(),
            base.maxCodePointsPerAttempt(),
            base.maxEstimatedCost(),
            base.currency(),
            base.maxRequestsPerMinute(),
            1,
            base.requestTimeout(),
            base.maxRateLimitRetries(),
            base.retryBaseDelay(),
            base.maxRetryDelay());

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, oneConcurrentCall)) {
      for (int index = 0; index < 100; index++) {
        gate.execute(request("source " + index));
      }

      assertEquals(100, translateCalls.get());
    }
  }

  @Test
  void rejectsInputBeyondApplicationHardCapBeforeCallingProvider() {
    AtomicInteger translateCalls = new AtomicInteger();
    TranslationProvider provider =
        provider(
            new BigDecimal("0.01"),
            request -> {
              translateCalls.incrementAndGet();
              return response(request, "translated", request.sourceCodePoints());
            });

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 0, 4))) {
      TranslationProviderException failure =
          assertThrows(
              TranslationProviderException.class, () -> gate.execute(request("This is too long")));

      assertEquals(TranslationProviderFailure.INPUT_REJECTED, failure.failure());
      assertEquals(0, translateCalls.get());
    }
  }

  @Test
  void includesGlossaryTextInApplicationHardCap() {
    AtomicInteger translateCalls = new AtomicInteger();
    TranslationProvider provider =
        provider(
            BigDecimal.ZERO,
            request -> {
              translateCalls.incrementAndGet();
              return response(request, "translated", request.sourceCodePoints());
            });
    TranslationRequest request =
        new TranslationRequest(
            UUID.randomUUID(),
            VI_EN,
            List.of(new TranslationRequest.Segment(UUID.randomUUID(), "x")),
            List.of(new TranslationRequest.GlossaryInstruction("aa", "bbbbbb", false)));

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 0, 8))) {
      TranslationProviderException failure =
          assertThrows(TranslationProviderException.class, () -> gate.execute(request));

      assertEquals(TranslationProviderFailure.INPUT_REJECTED, failure.failure());
      assertEquals(0, translateCalls.get());
    }
  }

  @Test
  void rejectsEstimateOverHardCostCapBeforeTranslating() {
    AtomicInteger translateCalls = new AtomicInteger();
    TranslationProvider provider =
        provider(
            new BigDecimal("0.02"),
            request -> {
              translateCalls.incrementAndGet();
              return response(request, "translated", request.sourceCodePoints());
            });

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 0, 100))) {
      TranslationProviderException failure =
          assertThrows(TranslationProviderException.class, () -> gate.execute(request("source")));

      assertEquals(TranslationProviderFailure.COST_LIMIT_EXCEEDED, failure.failure());
      assertEquals(0, translateCalls.get());
      assertFalse(failure.getMessage().contains("source"));
    }
  }

  @Test
  void enforcesTimeoutAndReturnsSanitizedFailureWithoutRetrying() {
    AtomicInteger translateCalls = new AtomicInteger();
    TranslationProvider provider =
        provider(
            BigDecimal.ZERO,
            request -> {
              translateCalls.incrementAndGet();
              LockSupport.parkNanos(Duration.ofMillis(300).toNanos());
              return response(request, "translated", request.sourceCodePoints());
            });
    TranslationProviderExecutionPolicy base = policy("0.01", 0, 100);
    TranslationProviderExecutionPolicy limits =
        new TranslationProviderExecutionPolicy(
            base.maxSegmentsPerAttempt(),
            base.maxCodePointsPerAttempt(),
            base.maxEstimatedCost(),
            base.currency(),
            base.maxRequestsPerMinute(),
            base.maxConcurrentCalls(),
            Duration.ofMillis(20),
            base.maxRateLimitRetries(),
            base.retryBaseDelay(),
            base.maxRetryDelay());

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, limits)) {
      TranslationProviderException failure =
          assertThrows(
              TranslationProviderException.class, () -> gate.execute(request("private source")));

      assertEquals(TranslationProviderFailure.TIMEOUT, failure.failure());
      assertEquals("Translation provider: TIMEOUT", failure.getMessage());
      assertFalse(failure.getMessage().contains("private source"));
      assertEquals(1, translateCalls.get());
    }
  }

  @Test
  void recordsTranslationSuccessAndTimeoutWithOnlyBoundedProviderTags() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProviderCallMetrics metrics = new ProviderCallMetrics(registry);
    FakeTranslationProvider successfulProvider = new FakeTranslationProvider();

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(successfulProvider, policy("0.01", 0, 100), metrics)) {
      gate.execute(request("private translation source"));
    } catch (TranslationProviderException exception) {
      throw new AssertionError("The fake translation should succeed", exception);
    }

    TranslationProvider timeoutProvider =
        provider(
            BigDecimal.ZERO,
            request -> {
              LockSupport.parkNanos(Duration.ofMillis(300).toNanos());
              return response(request, "translated", request.sourceCodePoints());
            });
    TranslationProviderExecutionPolicy base = policy("0.01", 0, 100);
    TranslationProviderExecutionPolicy shortTimeout =
        new TranslationProviderExecutionPolicy(
            base.maxSegmentsPerAttempt(),
            base.maxCodePointsPerAttempt(),
            base.maxEstimatedCost(),
            base.currency(),
            base.maxRequestsPerMinute(),
            base.maxConcurrentCalls(),
            Duration.ofMillis(20),
            base.maxRateLimitRetries(),
            base.retryBaseDelay(),
            base.maxRetryDelay());
    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(timeoutProvider, shortTimeout, metrics)) {
      TranslationProviderException failure =
          assertThrows(
              TranslationProviderException.class,
              () -> gate.execute(request("another private source")));
      assertEquals(TranslationProviderFailure.TIMEOUT, failure.failure());
    }

    assertEquals(
        1,
        registry
            .get("novel.provider.request.duration")
            .tags("provider", "translation_fake", "operation", "translate", "outcome", "success")
            .timer()
            .count());
    assertEquals(
        1,
        registry
            .get("novel.provider.request.duration")
            .tags("provider", "other", "operation", "translate", "outcome", "timeout")
            .timer()
            .count());
    assertTrue(
        registry.getMeters().stream()
            .allMatch(
                meter ->
                    meter.getId().getTags().stream()
                        .allMatch(
                            tag ->
                                Set.of("provider", "operation", "outcome")
                                    .contains(tag.getKey()))));
    assertFalse(registry.getMeters().toString().contains("private translation source"));
  }

  @Test
  void retriesOnlyExplicitRateLimitResponsesAndHonorsAttemptLimit() throws Exception {
    AtomicInteger translateCalls = new AtomicInteger();
    TranslationProvider provider =
        provider(
            BigDecimal.ZERO,
            request -> {
              if (translateCalls.incrementAndGet() == 1) {
                throw new TranslationProviderException(
                    TranslationProviderFailure.RATE_LIMITED, Duration.ofMillis(1));
              }
              return response(request, "translated", request.sourceCodePoints());
            });

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 1, 100))) {
      var result = gate.execute(request("source"));

      assertEquals(2, result.translationAttempts());
      assertEquals(2, translateCalls.get());
    }
  }

  @Test
  void doesNotRetryAmbiguousTemporaryFailures() {
    AtomicInteger translateCalls = new AtomicInteger();
    TranslationProvider provider =
        provider(
            BigDecimal.ZERO,
            request -> {
              translateCalls.incrementAndGet();
              throw new TranslationProviderException(TranslationProviderFailure.TEMPORARY, null);
            });

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 3, 100))) {
      TranslationProviderException failure =
          assertThrows(TranslationProviderException.class, () -> gate.execute(request("source")));

      assertEquals(TranslationProviderFailure.TEMPORARY, failure.failure());
      assertEquals(1, translateCalls.get());
    }
  }

  @Test
  void rejectsActualBillingAboveThePreflightEstimate() {
    TranslationProvider provider =
        new TranslationProvider() {
          @Override
          public String id() {
            return "test";
          }

          @Override
          public TranslationProviderCapabilities capabilities() {
            return TranslationProviderExecutionGateTests.capabilities();
          }

          @Override
          public TranslationCostEstimate estimateCost(TranslationRequest request) {
            return new TranslationCostEstimate(
                BigDecimal.ZERO,
                "USD",
                request.sourceCodePoints(),
                TranslationBillingUnit.CODE_POINT,
                "test-v1");
          }

          @Override
          public TranslationResponse translate(TranslationRequest request) {
            return response(request, "translated", request.sourceCodePoints() + 1);
          }
        };

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 0, 100))) {
      TranslationProviderException failure =
          assertThrows(TranslationProviderException.class, () -> gate.execute(request("source")));

      assertEquals(TranslationProviderFailure.BILLING_LIMIT_EXCEEDED, failure.failure());
    }
  }

  @Test
  void rejectsResponseThatDoesNotMapToTheOriginalSourceSegments() {
    TranslationProvider provider =
        provider(
            BigDecimal.ZERO,
            request ->
                new TranslationResponse(
                    request.attemptId(),
                    "test-request",
                    "test-model",
                    List.of(new TranslationResponse.SegmentResult(UUID.randomUUID(), "unmapped")),
                    request.sourceCodePoints(),
                    TranslationBillingUnit.CODE_POINT));

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 0, 100))) {
      TranslationProviderException failure =
          assertThrows(TranslationProviderException.class, () -> gate.execute(request("source")));

      assertEquals(TranslationProviderFailure.INVALID_RESPONSE, failure.failure());
      assertEquals("Translation provider: INVALID_RESPONSE", failure.getMessage());
    }
  }

  @Test
  void rejectsAnOversizedTranslatedSegmentAsAnInvalidProviderResponse() {
    TranslationProvider provider =
        provider(
            BigDecimal.ZERO,
            request ->
                new TranslationResponse(
                    request.attemptId(),
                    "test-request",
                    "test-model",
                    List.of(
                        new TranslationResponse.SegmentResult(
                            request.segments().getFirst().id(), "x".repeat(1_000_001))),
                    request.sourceCodePoints(),
                    TranslationBillingUnit.CODE_POINT));

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 0, 100))) {
      TranslationProviderException failure =
          assertThrows(TranslationProviderException.class, () -> gate.execute(request("source")));

      assertEquals(TranslationProviderFailure.INVALID_RESPONSE, failure.failure());
    }
  }

  @Test
  void enforcesProcessLocalRequestsPerMinuteBeforeTranslatingAgain() throws Exception {
    AtomicInteger translateCalls = new AtomicInteger();
    TranslationProvider provider =
        provider(
            BigDecimal.ZERO,
            request -> {
              translateCalls.incrementAndGet();
              return response(request, "translated", request.sourceCodePoints());
            });
    TranslationProviderExecutionPolicy base = policy("0.01", 0, 100);
    TranslationProviderExecutionPolicy oneCallPerMinute =
        new TranslationProviderExecutionPolicy(
            base.maxSegmentsPerAttempt(),
            base.maxCodePointsPerAttempt(),
            base.maxEstimatedCost(),
            base.currency(),
            1,
            base.maxConcurrentCalls(),
            base.requestTimeout(),
            base.maxRateLimitRetries(),
            base.retryBaseDelay(),
            base.maxRetryDelay());

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(
            provider, oneCallPerMinute, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))) {
      gate.execute(request("first"));
      TranslationProviderException failure =
          assertThrows(TranslationProviderException.class, () -> gate.execute(request("second")));

      assertEquals(TranslationProviderFailure.QUOTA_EXCEEDED, failure.failure());
      assertEquals(1, translateCalls.get());
    }
  }

  @Test
  void doesNotRetryRateLimitWhenRetryAfterExceedsConfiguredMaximum() {
    AtomicInteger translateCalls = new AtomicInteger();
    TranslationProvider provider =
        provider(
            BigDecimal.ZERO,
            request -> {
              translateCalls.incrementAndGet();
              throw new TranslationProviderException(
                  TranslationProviderFailure.RATE_LIMITED, Duration.ofSeconds(2));
            });

    try (TranslationProviderExecutionGate gate =
        new TranslationProviderExecutionGate(provider, policy("0.01", 3, 100))) {
      TranslationProviderException failure =
          assertThrows(TranslationProviderException.class, () -> gate.execute(request("source")));

      assertEquals(TranslationProviderFailure.RATE_LIMITED, failure.failure());
      assertEquals(1, translateCalls.get());
    }
  }

  @Test
  void rejectsInvalidExecutionPolicies() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TranslationProviderExecutionPolicy(
                1,
                1,
                BigDecimal.ZERO,
                "USD",
                1,
                257,
                Duration.ofSeconds(1),
                0,
                Duration.ofMillis(1),
                Duration.ofMillis(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TranslationProviderExecutionPolicy(
                1,
                1,
                BigDecimal.ZERO,
                "USD",
                1,
                1,
                Duration.ofSeconds(1),
                4,
                Duration.ofMillis(1),
                Duration.ofMillis(1)));
    assertTrue(policy("0.01", 1, 1).maxEstimatedCost().signum() >= 0);
  }

  private static TranslationProviderExecutionPolicy policy(
      String maxCost, int retries, long maxCodePoints) {
    return new TranslationProviderExecutionPolicy(
        100,
        maxCodePoints,
        new BigDecimal(maxCost),
        "USD",
        600,
        2,
        Duration.ofSeconds(1),
        retries,
        Duration.ofMillis(1),
        Duration.ofMillis(10));
  }

  private static TranslationRequest request(String source) {
    return new TranslationRequest(
        UUID.randomUUID(),
        VI_EN,
        List.of(new TranslationRequest.Segment(UUID.randomUUID(), source)),
        List.of());
  }

  private static TranslationProvider provider(BigDecimal estimate, Translator translator) {
    return new TranslationProvider() {
      @Override
      public String id() {
        return "test";
      }

      @Override
      public TranslationProviderCapabilities capabilities() {
        return TranslationProviderExecutionGateTests.capabilities();
      }

      @Override
      public TranslationCostEstimate estimateCost(TranslationRequest request) {
        return new TranslationCostEstimate(
            estimate,
            "USD",
            request.sourceCodePoints(),
            TranslationBillingUnit.CODE_POINT,
            "test-v1");
      }

      @Override
      public TranslationResponse translate(TranslationRequest request)
          throws TranslationProviderException {
        return translator.translate(request);
      }
    };
  }

  private static TranslationProviderCapabilities capabilities() {
    return new TranslationProviderCapabilities(
        Set.of(VI_EN), 100, 10_000, 200_000, 600, Duration.ofSeconds(2), true);
  }

  private static TranslationResponse response(
      TranslationRequest request, String translated, long billedUnits) {
    return new TranslationResponse(
        request.attemptId(),
        "test-request",
        "test-model",
        request.segments().stream()
            .map(segment -> new TranslationResponse.SegmentResult(segment.id(), translated))
            .toList(),
        billedUnits,
        TranslationBillingUnit.CODE_POINT);
  }

  @FunctionalInterface
  private interface Translator {
    TranslationResponse translate(TranslationRequest request) throws TranslationProviderException;
  }
}
