package com.novelweb.payment.momo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime MoMo TEST settings; credential and callback values are redacted from diagnostic text. */
@ConfigurationProperties(prefix = "novel.payment.momo.test")
public record MomoTestGatewayProperties(
    boolean enabled,
    String environment,
    String partnerCode,
    String accessKey,
    String secretKey,
    String baseUrl,
    String ipnUrl,
    String redirectUrl) {

  @Override
  public String toString() {
    return "MomoTestGatewayProperties[enabled="
        + enabled
        + ", environment="
        + environment
        + ", partnerCode="
        + partnerCode
        + ", accessKey=[REDACTED], secretKey=[REDACTED], baseUrl="
        + baseUrl
        + ", ipnUrl=[REDACTED], redirectUrl=[REDACTED]]";
  }
}
