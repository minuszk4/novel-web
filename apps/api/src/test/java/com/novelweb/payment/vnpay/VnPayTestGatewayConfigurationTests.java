package com.novelweb.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelweb.payment.credit.CreditPackCatalog;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class VnPayTestGatewayConfigurationTests {

  private static final String HASH_SECRET = "synthetic-vnpay-config-secret";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(VnPayTestGatewayConfiguration.class)
          .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

  @Test
  void adapterBeansAreAbsentUnlessTheExplicitTestFlagIsTrue() {
    contextRunner
        .withPropertyValues("novel.payment.vnpay.test.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(VnPayTestGatewaySettings.class);
              assertThat(context).doesNotHaveBean(VnPayTestGatewayClient.class);
              assertThat(context).doesNotHaveBean(VnPayTestQueryDrClient.class);
              assertThat(context).doesNotHaveBean(CreditPackCatalog.class);
            });
  }

  @Test
  void createsAdapterOnlyFromCompleteTestConfiguration() {
    contextRunner
        .withPropertyValues(validProperties())
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(VnPayTestGatewaySettings.class);
              assertThat(context).hasSingleBean(VnPayTestGatewayClient.class);
              assertThat(context).hasSingleBean(VnPayTestQueryDrClient.class);
              assertThat(context).hasSingleBean(CreditPackCatalog.class);
              assertThat(context.getBean(CreditPackCatalog.class).offers()).hasSize(1);
              assertThat(context.getBean(VnPayTestGatewayProperties.class).toString())
                  .doesNotContain(HASH_SECRET);
            });
  }

  @Test
  void rejectsTestAdapterWhenTheApplicationEnvironmentIsProduction() {
    assertProductionConfigurationRejected("novel.environment=production");
  }

  @Test
  void rejectsTestAdapterWhenTheProductionSpringProfileIsActive() {
    assertProductionConfigurationRejected("spring.profiles.active=prod", "novel.environment=local");
  }

  private void assertProductionConfigurationRejected(String... environmentProperties) {
    contextRunner
        .withPropertyValues(validProperties())
        .withPropertyValues(environmentProperties)
        .run(
            context -> {
              assertThat(context).hasFailed();
              StringWriter trace = new StringWriter();
              context.getStartupFailure().printStackTrace(new PrintWriter(trace));
              assertThat(trace.toString())
                  .contains("VNPAY TEST payment integration is forbidden in production")
                  .doesNotContain(HASH_SECRET);
            });
  }

  private static String[] validProperties() {
    return new String[] {
      "novel.payment.vnpay.test.enabled=true",
      "novel.payment.vnpay.test.tmn-code=ABCD1234",
      "novel.payment.vnpay.test.hash-secret=" + HASH_SECRET,
      "NOVEL_APP_BASE_URL=https://novel.example"
    };
  }
}
