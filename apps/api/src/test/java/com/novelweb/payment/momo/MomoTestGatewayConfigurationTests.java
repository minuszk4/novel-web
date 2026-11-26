package com.novelweb.payment.momo;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelweb.payment.credit.CreditPackCatalog;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MomoTestGatewayConfigurationTests {

  private static final String ACCESS_KEY = "synthetic-config-access";
  private static final String SECRET_KEY = "synthetic-config-secret";

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(MomoTestGatewayConfiguration.class)
          .withBean(ObjectMapper.class, () -> JsonMapper.builder().build());

  @Test
  void adapterBeansAreAbsentUnlessTheExplicitTestFlagIsTrue() {
    contextRunner
        .withPropertyValues("novel.payment.momo.test.enabled=false")
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(MomoTestGatewaySettings.class);
              assertThat(context).doesNotHaveBean(MomoTestGatewayClient.class);
              assertThat(context).doesNotHaveBean(MomoTestHttpTransport.class);
              assertThat(context).doesNotHaveBean(CreditPackCatalog.class);
            });
  }

  @Test
  void rejectsInvalidOrProductionConfigurationWithoutEchoingCredentials() {
    contextRunner
        .withPropertyValues(validProperties())
        .withPropertyValues("novel.payment.momo.test.environment=production")
        .run(
            context -> {
              assertThat(context).hasFailed();
              StringWriter trace = new StringWriter();
              context.getStartupFailure().printStackTrace(new PrintWriter(trace));
              assertThat(trace.toString())
                  .contains("MoMo TEST gateway configuration is invalid")
                  .doesNotContain(ACCESS_KEY, SECRET_KEY);
            });
  }

  @Test
  void rejectsTestAdapterWhenTheApplicationEnvironmentIsProduction() {
    contextRunner
        .withPropertyValues(validProperties())
        .withPropertyValues("novel.environment=production")
        .run(
            context -> {
              assertThat(context).hasFailed();
              StringWriter trace = new StringWriter();
              context.getStartupFailure().printStackTrace(new PrintWriter(trace));
              assertThat(trace.toString())
                  .contains("MoMo TEST payment integration is forbidden in production")
                  .doesNotContain(ACCESS_KEY, SECRET_KEY);
            });
  }

  @Test
  void rejectsTestAdapterWhenTheProductionSpringProfileIsActive() {
    contextRunner
        .withPropertyValues(validProperties())
        .withPropertyValues("spring.profiles.active=production", "novel.environment=local")
        .run(
            context -> {
              assertThat(context).hasFailed();
              StringWriter trace = new StringWriter();
              context.getStartupFailure().printStackTrace(new PrintWriter(trace));
              assertThat(trace.toString())
                  .contains("MoMo TEST payment integration is forbidden in production")
                  .doesNotContain(ACCESS_KEY, SECRET_KEY);
            });
  }

  @Test
  void createsTheClientOnlyFromACompleteTestConfigurationAndRedactsItsProperties() {
    contextRunner
        .withPropertyValues(validProperties())
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(MomoTestGatewaySettings.class);
              assertThat(context).hasSingleBean(MomoTestGatewayClient.class);
              assertThat(context).hasSingleBean(MomoTestHttpTransport.class);
              assertThat(context).hasSingleBean(CreditPackCatalog.class);
              assertThat(context.getBean(CreditPackCatalog.class).offers()).hasSize(1);
              assertThat(context.getBean(CreditPackCatalog.class).offers().getFirst().amountVnd())
                  .isEqualTo(1_000);
              assertThat(context.getBean(MomoTestGatewayProperties.class).toString())
                  .doesNotContain(ACCESS_KEY, SECRET_KEY);
            });
  }

  private static String[] validProperties() {
    return new String[] {
      "novel.payment.momo.test.enabled=true",
      "novel.payment.momo.test.environment=test",
      "novel.payment.momo.test.partner-code=MOMOTEST123",
      "novel.payment.momo.test.access-key=" + ACCESS_KEY,
      "novel.payment.momo.test.secret-key=" + SECRET_KEY,
      "novel.payment.momo.test.base-url=https://test-payment.momo.vn",
      "novel.payment.momo.test.ipn-url=https://novel.example/api/v1/payments/momo/ipn",
      "novel.payment.momo.test.redirect-url=https://novel.example/app/payment/result"
    };
  }
}
