package com.novelweb.payment.vnpay;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit opt-in and bounded local scheduling settings for VNPAY TEST QueryDR. */
@ConfigurationProperties(prefix = "novel.payment.vnpay.test.reconciliation")
public record VnPayTestReconciliationProperties(
    boolean enabled, Duration pollDelay, int batchSize, String serverIpAddress) {

  public VnPayTestReconciliationProperties {
    pollDelay = pollDelay == null ? Duration.ofSeconds(30) : pollDelay;
    batchSize = batchSize == 0 ? 5 : batchSize;
    serverIpAddress =
        serverIpAddress == null || serverIpAddress.isBlank() ? "127.0.0.1" : serverIpAddress.trim();
    if (pollDelay.compareTo(Duration.ofSeconds(30)) < 0) {
      throw new IllegalArgumentException(
          "VNPAY TEST reconciliation poll delay must be at least 30 seconds");
    }
    if (batchSize < 1 || batchSize > 20) {
      throw new IllegalArgumentException(
          "VNPAY TEST reconciliation batch size must be between 1 and 20");
    }
  }
}
