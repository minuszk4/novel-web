package com.novelweb.payment.momo;

import com.novelweb.payment.credit.CreditLedgerService;
import com.novelweb.payment.credit.CreditPackCatalog;
import com.novelweb.payment.credit.CreditTopUpCheckoutService;
import com.novelweb.payment.credit.CreditTopUpOrderService;
import com.novelweb.platform.ProviderCallMetrics;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

/** Installs MoMo TEST adapter beans only after an explicit environment opt-in. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  MomoTestGatewayProperties.class,
  MomoTestReconciliationProperties.class
})
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

  @Bean
  CreditPackCatalog momoTestCreditPackCatalog() {
    return CreditPackCatalog.momoTestFixture();
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
      MomoTestHttpTransport transport,
      ObjectProvider<ProviderCallMetrics> metricsProvider) {
    ProviderCallMetrics metrics = metricsProvider.getIfAvailable();
    return new MomoTestGatewayClient(
        settings, objectMapper, transport, metrics == null ? ProviderCallMetrics.noop() : metrics);
  }

  @Bean
  @Profile("postgres")
  CreditTopUpCheckoutService creditTopUpCheckoutService(
      CreditTopUpOrderService orders, MomoTestGatewayClient gatewayClient) {
    return new CreditTopUpCheckoutService(orders, gatewayClient::createCreditTopUp);
  }

  @Bean
  @Profile("postgres")
  MomoTestIpnService momoTestIpnService(
      DataSource dataSource, CreditLedgerService creditLedger, MomoTestGatewaySettings settings) {
    return new MomoTestIpnService(dataSource, creditLedger, settings);
  }

  @Bean
  @Profile("postgres")
  @ConditionalOnProperty(
      prefix = "novel.payment.momo.test.reconciliation",
      name = "enabled",
      havingValue = "true")
  MomoTestOrderReconciliationService momoTestOrderReconciliationService(
      DataSource dataSource,
      CreditLedgerService creditLedger,
      MomoTestGatewayClient gatewayClient,
      MomoTestGatewaySettings settings,
      MomoTestReconciliationProperties reconciliationProperties) {
    return new MomoTestOrderReconciliationService(
        dataSource,
        creditLedger,
        settings.partnerCode(),
        gatewayClient::queryOrder,
        reconciliationProperties.batchSize());
  }
}
