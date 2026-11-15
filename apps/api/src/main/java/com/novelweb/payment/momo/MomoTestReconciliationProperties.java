package com.novelweb.payment.momo;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicitly opted-in, bounded scheduler settings for MoMo TEST status queries. */
@ConfigurationProperties(prefix = "novel.payment.momo.test.reconciliation")
public record MomoTestReconciliationProperties(boolean enabled, Duration pollDelay, int batchSize) {

  public MomoTestReconciliationProperties {
    pollDelay = pollDelay == null ? Duration.ofSeconds(30) : pollDelay;
    batchSize = batchSize == 0 ? 5 : batchSize;
    if (pollDelay.compareTo(Duration.ofSeconds(30)) < 0) {
      throw new IllegalArgumentException(
          "MoMo TEST reconciliation poll delay must be at least 30 seconds");
    }
    if (batchSize < 1 || batchSize > 20) {
      throw new IllegalArgumentException(
          "MoMo TEST reconciliation batch size must be between 1 and 20");
    }
  }
}
