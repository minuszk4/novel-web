package com.novelweb.payment.vnpay;

import com.novelweb.payment.credit.CreditLedgerService;
import com.novelweb.payment.credit.CreditPackCatalog;
import com.novelweb.payment.credit.CreditTopUpCheckoutService;
import com.novelweb.payment.credit.CreditTopUpOrderService;
import com.novelweb.payment.credit.CreditTopUpOrderService.Provider;
import com.novelweb.platform.NonProductionIntegrationGuard;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/** Installs the VNPAY sandbox adapter only after explicit opt-in and complete TEST settings. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  VnPayTestGatewayProperties.class,
  VnPayTestReconciliationProperties.class
})
@ConditionalOnProperty(prefix = "novel.payment.vnpay.test", name = "enabled", havingValue = "true")
public class VnPayTestGatewayConfiguration {

  @Bean
  VnPayTestGatewaySettings vnPayTestGatewaySettings(
      VnPayTestGatewayProperties properties,
      @Value("${NOVEL_APP_BASE_URL:http://localhost:4200}") String publicAppOrigin,
      @Value("${novel.environment:local}") String applicationEnvironment,
      Environment springEnvironment) {
    NonProductionIntegrationGuard.requireNonProduction(
        "VNPAY", applicationEnvironment, springEnvironment);
    return VnPayTestGatewaySettings.fromProperties(properties, publicAppOrigin);
  }

  @Bean
  VnPayTestGatewayClient vnPayTestGatewayClient(VnPayTestGatewaySettings settings) {
    return new VnPayTestGatewayClient(settings);
  }

  @Bean
  VnPayTestQueryDrTransport vnPayTestQueryDrTransport() {
    return VnPayTestQueryDrTransport.jdk();
  }

  @Bean
  VnPayTestQueryDrClient vnPayTestQueryDrClient(
      VnPayTestGatewaySettings settings,
      ObjectMapper objectMapper,
      VnPayTestQueryDrTransport transport) {
    return new VnPayTestQueryDrClient(settings, objectMapper, transport);
  }

  @Bean
  CreditPackCatalog vnPayTestCreditPackCatalog() {
    return CreditPackCatalog.vnPayTestFixture();
  }

  @Bean
  @Profile("postgres")
  CreditTopUpCheckoutService vnPayCreditTopUpCheckoutService(
      CreditTopUpOrderService orders, VnPayTestGatewayClient gatewayClient) {
    return new CreditTopUpCheckoutService(
        orders,
        Provider.VNPAY,
        gatewayClient::createCreditTopUp,
        VnPayTestCheckoutUrlPolicy::allows);
  }

  @Bean
  @Profile("postgres")
  VnPayTestIpnService vnPayTestIpnService(
      DataSource dataSource, CreditLedgerService creditLedger, VnPayTestGatewaySettings settings) {
    return new VnPayTestIpnService(dataSource, creditLedger, settings);
  }

  @Bean
  @Profile("postgres")
  @ConditionalOnProperty(
      prefix = "novel.payment.vnpay.test.reconciliation",
      name = "enabled",
      havingValue = "true")
  VnPayTestOrderReconciliationService vnPayTestOrderReconciliationService(
      DataSource dataSource,
      CreditLedgerService creditLedger,
      VnPayTestGatewaySettings settings,
      VnPayTestQueryDrClient queryDrClient,
      VnPayTestReconciliationProperties reconciliationProperties) {
    return new VnPayTestOrderReconciliationService(
        dataSource,
        creditLedger,
        settings.tmnCode(),
        reconciliationProperties.serverIpAddress(),
        queryDrClient::query,
        reconciliationProperties.batchSize());
  }
}
