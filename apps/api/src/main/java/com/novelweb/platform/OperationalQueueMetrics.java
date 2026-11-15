package com.novelweb.platform;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes low-cardinality, aggregate-only gauges on the opt-in observability profile. */
@Component
@Profile("postgres & observability")
public class OperationalQueueMetrics {

  private static final Logger LOGGER = LoggerFactory.getLogger(OperationalQueueMetrics.class);

  private final QueueMetricsSnapshotReader snapshotReader;
  private final AtomicReference<QueueMetricsSnapshot> snapshot =
      new AtomicReference<>(QueueMetricsSnapshot.empty());
  private final AtomicLong lastSuccessfulRefreshEpochSeconds = new AtomicLong();
  private final AtomicLong lastRefreshSucceeded = new AtomicLong();
  private final Counter refreshFailures;

  public OperationalQueueMetrics(
      QueueMetricsSnapshotReader snapshotReader, MeterRegistry registry) {
    this.snapshotReader = snapshotReader;
    this.refreshFailures = registry.counter("novel.queue.snapshot.refresh.failures");
    registerGauge(registry, "novel.import.staging.active", QueueMetricsSnapshot::activeImports);
    registerGauge(registry, "novel.import.parse.queued", QueueMetricsSnapshot::queuedImportParses);
    registerGauge(
        registry, "novel.import.parse.running", QueueMetricsSnapshot::runningImportParses);
    registerGauge(
        registry,
        "novel.import.parse.oldest.queued.seconds",
        QueueMetricsSnapshot::oldestQueuedImportParseSeconds);
    registerGauge(
        registry, "novel.translation.jobs.pending", QueueMetricsSnapshot::pendingTranslationJobs);
    registerGauge(
        registry, "novel.translation.jobs.running", QueueMetricsSnapshot::runningTranslationJobs);
    registerGauge(
        registry,
        "novel.translation.jobs.oldest.pending.seconds",
        QueueMetricsSnapshot::oldestPendingTranslationSeconds);
    registerGauge(
        registry,
        "novel.momo.reconciliation.unresolved",
        QueueMetricsSnapshot::unresolvedMomoOrders);
    registerGauge(registry, "novel.momo.reconciliation.due", QueueMetricsSnapshot::dueMomoOrders);
    registerGauge(
        registry,
        "novel.momo.reconciliation.review.required",
        QueueMetricsSnapshot::reviewRequiredMomoOrders);
    registerGauge(
        registry,
        "novel.momo.reconciliation.oldest.due.seconds",
        QueueMetricsSnapshot::oldestDueMomoOrderSeconds);
    Gauge.builder(
            "novel.queue.snapshot.last.success.epoch.seconds",
            lastSuccessfulRefreshEpochSeconds,
            AtomicLong::get)
        .register(registry);
    Gauge.builder(
            "novel.queue.snapshot.last.refresh.succeeded", lastRefreshSucceeded, AtomicLong::get)
        .register(registry);
  }

  @Scheduled(
      initialDelayString = "${novel.observability.queue-snapshot.initial-delay:5s}",
      fixedDelayString = "${novel.observability.queue-snapshot.refresh-delay:15s}")
  public void refresh() {
    try {
      QueueMetricsSnapshot next = snapshotReader.readSnapshot();
      if (next == null) throw new IllegalStateException("Queue snapshot is unavailable");
      snapshot.set(next);
      lastSuccessfulRefreshEpochSeconds.set(Instant.now().getEpochSecond());
      lastRefreshSucceeded.set(1);
    } catch (RuntimeException exception) {
      refreshFailures.increment();
      lastRefreshSucceeded.set(0);
      LOGGER.warn("Operational queue metrics refresh failed; retaining the last good snapshot");
    }
  }

  private void registerGauge(
      MeterRegistry registry,
      String name,
      java.util.function.ToLongFunction<QueueMetricsSnapshot> value) {
    Gauge.builder(name, snapshot, current -> value.applyAsLong(current.get())).register(registry);
  }
}
