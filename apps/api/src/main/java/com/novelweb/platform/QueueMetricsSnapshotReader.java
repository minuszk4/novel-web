package com.novelweb.platform;

/** Reads one privacy-safe aggregate snapshot of durable background queues. */
@FunctionalInterface
public interface QueueMetricsSnapshotReader {
  QueueMetricsSnapshot readSnapshot();
}
