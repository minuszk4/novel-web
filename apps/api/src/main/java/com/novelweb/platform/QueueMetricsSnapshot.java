package com.novelweb.platform;

/** Bounded aggregate counts for durable background work; contains no work identifiers. */
public record QueueMetricsSnapshot(
    long activeImports,
    long queuedImportParses,
    long runningImportParses,
    long oldestQueuedImportParseSeconds,
    long pendingTranslationJobs,
    long runningTranslationJobs,
    long oldestPendingTranslationSeconds,
    long unresolvedMomoOrders,
    long dueMomoOrders,
    long reviewRequiredMomoOrders,
    long oldestDueMomoOrderSeconds) {

  public static QueueMetricsSnapshot empty() {
    return new QueueMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }
}
