package com.novelweb.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OperationalQueueMetricsTests {

  @Test
  void refreshPublishesOnlyFixedAggregateMetricsAndRetainsLastGoodSnapshotOnFailure() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AtomicInteger reads = new AtomicInteger();
    QueueMetricsSnapshot expected = new QueueMetricsSnapshot(2, 3, 1, 17, 5, 2, 31, 4, 1, 1, 90);
    QueueMetricsSnapshotReader reader =
        () -> {
          if (reads.incrementAndGet() == 1) return expected;
          throw new IllegalStateException("synthetic database outage");
        };
    OperationalQueueMetrics metrics = new OperationalQueueMetrics(reader, registry);

    metrics.refresh();

    assertEquals(2.0, registry.get("novel.import.staging.active").gauge().value());
    assertEquals(3.0, registry.get("novel.import.parse.queued").gauge().value());
    assertEquals(1.0, registry.get("novel.import.parse.running").gauge().value());
    assertEquals(17.0, registry.get("novel.import.parse.oldest.queued.seconds").gauge().value());
    assertEquals(5.0, registry.get("novel.translation.jobs.pending").gauge().value());
    assertEquals(2.0, registry.get("novel.translation.jobs.running").gauge().value());
    assertEquals(
        31.0, registry.get("novel.translation.jobs.oldest.pending.seconds").gauge().value());
    assertEquals(4.0, registry.get("novel.momo.reconciliation.unresolved").gauge().value());
    assertEquals(1.0, registry.get("novel.momo.reconciliation.due").gauge().value());
    assertEquals(1.0, registry.get("novel.momo.reconciliation.review.required").gauge().value());
    assertEquals(
        90.0, registry.get("novel.momo.reconciliation.oldest.due.seconds").gauge().value());
    assertEquals(1.0, registry.get("novel.queue.snapshot.last.refresh.succeeded").gauge().value());
    assertTrue(registry.get("novel.queue.snapshot.last.success.epoch.seconds").gauge().value() > 0);

    metrics.refresh();

    assertEquals(2.0, registry.get("novel.import.staging.active").gauge().value());
    assertEquals(5.0, registry.get("novel.translation.jobs.pending").gauge().value());
    assertEquals(0.0, registry.get("novel.queue.snapshot.last.refresh.succeeded").gauge().value());
    assertEquals(1.0, registry.get("novel.queue.snapshot.refresh.failures").counter().count());
    assertTrue(registry.getMeters().stream().allMatch(meter -> meter.getId().getTags().isEmpty()));
  }
}
