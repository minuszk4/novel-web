package com.novelweb.translation.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Enforces application provider limits before output can be accepted. This must be a singleton per
 * provider process; its rolling quota is process-local, so multi-instance workers need shared
 * admission control. Provider adapters also need their own HTTP connect/response deadlines because
 * cancellation cannot guarantee that a remote request has stopped or was not billed.
 */
public final class TranslationProviderExecutionGate implements AutoCloseable {

  private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

  private final TranslationProvider provider;
  private final TranslationProviderExecutionPolicy policy;
  private final Clock clock;
  private final ThreadPoolExecutor calls;
  private final Deque<Instant> callTimes = new ArrayDeque<>();
  private final ReentrantLock rateLock = new ReentrantLock();
  private final AtomicInteger threadNumber = new AtomicInteger();
  private final int effectiveRequestsPerMinute;
  private final Duration effectiveTimeout;

  public TranslationProviderExecutionGate(
      TranslationProvider provider, TranslationProviderExecutionPolicy policy) {
    this(provider, policy, Clock.systemUTC());
  }

  TranslationProviderExecutionGate(
      TranslationProvider provider, TranslationProviderExecutionPolicy policy, Clock clock) {
    if (provider == null || policy == null || clock == null) {
      throw new IllegalArgumentException("Provider, execution policy and clock are required");
    }
    this.provider = provider;
    this.policy = policy;
    this.clock = clock;
    TranslationProviderCapabilities capabilities = provider.capabilities();
    this.effectiveRequestsPerMinute =
        Math.min(policy.maxRequestsPerMinute(), capabilities.requestsPerMinute());
    this.effectiveTimeout = min(policy.requestTimeout(), capabilities.requestTimeout());
    ThreadFactory threadFactory =
        task -> {
          Thread thread =
              new Thread(task, "translation-provider-call-" + threadNumber.incrementAndGet());
          thread.setDaemon(true);
          return thread;
        };
    this.calls =
        new ThreadPoolExecutor(
            policy.maxConcurrentCalls(),
            policy.maxConcurrentCalls(),
            0,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy());
  }

  public TranslationExecutionResult execute(TranslationRequest request)
      throws TranslationProviderException {
    validateRequest(request);
    // estimateCost is required to be a local pricing lookup; only translate consumes provider RPM.
    // estimateCost is required to be a local pricing lookup; only translate consumes provider RPM.
    TranslationCostEstimate estimate = call(() -> provider.estimateCost(request));
    validateEstimate(estimate);

    int attempts = 0;
    while (true) {
      attempts++;
      admitProviderCall();
      try {
        TranslationResponse response = call(() -> provider.translate(request));
        validateResponse(request, estimate, response);
        return new TranslationExecutionResult(estimate, response, attempts);
      } catch (TranslationProviderException failure) {
        if (failure.failure() != TranslationProviderFailure.RATE_LIMITED
            || attempts > policy.maxRateLimitRetries()) {
          throw failure;
        }
        Duration delay = retryDelay(attempts, failure.retryAfter());
        if (delay == null) throw failure;
        pause(delay);
      }
    }
  }

  private void validateRequest(TranslationRequest request) throws TranslationProviderException {
    if (request == null) {
      throw new TranslationProviderException(TranslationProviderFailure.INPUT_REJECTED, null);
    }
    request.validateAgainst(provider.capabilities());
    if (request.segments().size() > policy.maxSegmentsPerAttempt()
        || request.requestCodePoints() > policy.maxCodePointsPerAttempt()) {
      throw new TranslationProviderException(TranslationProviderFailure.INPUT_REJECTED, null);
    }
  }

  private void validateEstimate(TranslationCostEstimate estimate)
      throws TranslationProviderException {
    if (estimate == null || !estimate.currency().equals(policy.currency())) {
      throw new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null);
    }
    if (estimate.amount().compareTo(policy.maxEstimatedCost()) > 0) {
      throw new TranslationProviderException(TranslationProviderFailure.COST_LIMIT_EXCEEDED, null);
    }
  }

  private static void validateResponse(
      TranslationRequest request, TranslationCostEstimate estimate, TranslationResponse response)
      throws TranslationProviderException {
    if (response == null) {
      throw new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null);
    }
    response.validateAgainst(request);
    if (response.billingUnit() != estimate.billingUnit()) {
      throw new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null);
    }
    if (response.billedUnits() > estimate.billableUnits()) {
      throw new TranslationProviderException(
          TranslationProviderFailure.BILLING_LIMIT_EXCEEDED, null);
    }
  }

  private void admitProviderCall() throws TranslationProviderException {
    Instant now = clock.instant();
    rateLock.lock();
    try {
      while (!callTimes.isEmpty() && !callTimes.getFirst().plus(RATE_WINDOW).isAfter(now)) {
        callTimes.removeFirst();
      }
      if (callTimes.size() >= effectiveRequestsPerMinute) {
        throw new TranslationProviderException(TranslationProviderFailure.QUOTA_EXCEEDED, null);
      }
      callTimes.addLast(now);
    } finally {
      rateLock.unlock();
    }
  }

  private Duration retryDelay(int attempt, Duration retryAfter) {
    Duration delay = retryAfter;
    if (delay == null) {
      long multiplier = 1L << Math.max(0, attempt - 1);
      try {
        delay = policy.retryBaseDelay().multipliedBy(multiplier);
      } catch (ArithmeticException overflow) {
        return null;
      }
      long equalJitterNanos =
          Math.max(1, (long) (delay.toNanos() * ThreadLocalRandom.current().nextDouble(0.5, 1.0)));
      delay = Duration.ofNanos(equalJitterNanos);
    }
    return delay.isNegative() || delay.isZero() || delay.compareTo(policy.maxRetryDelay()) > 0
        ? null
        : delay;
  }

  private void pause(Duration delay) throws TranslationProviderException {
    try {
      TimeUnit.NANOSECONDS.sleep(delay.toNanos());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new TranslationProviderException(TranslationProviderFailure.TEMPORARY, null);
    }
  }

  private <T> T call(Callable<T> operation) throws TranslationProviderException {
    Future<T> future;
    try {
      future = calls.submit(operation);
    } catch (RejectedExecutionException rejected) {
      throw new TranslationProviderException(TranslationProviderFailure.QUOTA_EXCEEDED, null);
    }
    try {
      return future.get(effectiveTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException timeout) {
      future.cancel(true);
      throw new TranslationProviderException(TranslationProviderFailure.TIMEOUT, null);
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new TranslationProviderException(TranslationProviderFailure.TEMPORARY, null);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof TranslationProviderException providerFailure) {
        throw providerFailure;
      }
      if (cause instanceof IllegalArgumentException) {
        throw new TranslationProviderException(TranslationProviderFailure.INVALID_RESPONSE, null);
      }
      throw new TranslationProviderException(TranslationProviderFailure.TEMPORARY, null);
    }
  }

  private static Duration min(Duration left, Duration right) {
    return left.compareTo(right) <= 0 ? left : right;
  }

  @Override
  public void close() {
    calls.shutdownNow();
  }

  public record TranslationExecutionResult(
      TranslationCostEstimate estimate, TranslationResponse response, int translationAttempts) {
    public TranslationExecutionResult {
      if (estimate == null || response == null || translationAttempts < 1) {
        throw new IllegalArgumentException("A completed execution result is required");
      }
    }
  }
}
