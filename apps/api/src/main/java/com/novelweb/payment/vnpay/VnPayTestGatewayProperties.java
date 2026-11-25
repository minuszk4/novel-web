package com.novelweb.payment.vnpay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** VNPAY sandbox settings. Secret values are never included in diagnostic text. */
@ConfigurationProperties(prefix = "novel.payment.vnpay.test")
public record VnPayTestGatewayProperties(boolean enabled, String tmnCode, String hashSecret) {

  @Override
  public String toString() {
    return "VnPayTestGatewayProperties[enabled="
        + enabled
        + ", tmnCode="
        + tmnCode
        + ", hashSecret=[REDACTED]]";
  }
}
