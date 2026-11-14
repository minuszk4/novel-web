package com.novelweb.payment.momo;

import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Installs MoMo TEST adapter beans only after an explicit environment opt-in. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MomoTestGatewayProperties.class)
@ConditionalOnProperty(prefix = "novel.payment.momo.test", name = "enabled", havingValue = "true")
public class MomoTestGatewayConfiguration {

  @Bean
  MomoTestGatewaySettings momoTestGatewaySettings(MomoTestGatewayProperties properties) {
    return MomoTestGatewaySettings.fromEnvironment(
        Map.of(
            "MOMO_ENV",
            valueOrEmpty(properties.environment()),
            "MOMO_TEST_PARTNER_CODE",
            valueOrEmpty(properties.partnerCode()),
            "MOMO_TEST_ACCESS_KEY",
            valueOrEmpty(properties.accessKey()),
            "MOMO_TEST_SECRET_KEY",
            valueOrEmpty(properties.secretKey()),
            "MOMO_TEST_BASE_URL",
            valueOrEmpty(properties.baseUrl()),
            "MOMO_TEST_IPN_URL",
            valueOrEmpty(properties.ipnUrl()),
            "MOMO_TEST_REDIRECT_URL",
            valueOrEmpty(properties.redirectUrl())));
  }

  private static String valueOrEmpty(String value) {
    return Objects.toString(value, "");
  }

  @Bean
  MomoTestHttpTransport momoTestHttpTransport() {
    return MomoTestHttpTransport.jdk();
  }

  @Bean
  MomoTestGatewayClient momoTestGatewayClient(
      MomoTestGatewaySettings settings,
      ObjectMapper objectMapper,
      MomoTestHttpTransport transport) {
    return new MomoTestGatewayClient(settings, objectMapper, transport);
  }
}
