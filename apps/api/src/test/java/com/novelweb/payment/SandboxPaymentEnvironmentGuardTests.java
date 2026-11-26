package com.novelweb.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SandboxPaymentEnvironmentGuardTests {

  @Test
  void rejectsEveryProductionEnvironmentAliasRegardlessOfCaseOrWhitespace() {
    for (String environment : List.of("prod", "production", "live", " Production ")) {
      assertThatThrownBy(
              () ->
                  SandboxPaymentEnvironmentGuard.requireNonProduction(
                      "VNPAY", environment, new MockEnvironment()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("VNPAY TEST payment integration is forbidden in production");
    }
  }

  @Test
  void allowsLocalTestAndStagingLabels() {
    for (String environment : List.of("local", "local-docker", "test", "staging")) {
      assertThatCode(
              () ->
                  SandboxPaymentEnvironmentGuard.requireNonProduction(
                      "VNPAY", environment, new MockEnvironment()))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void rejectsProductionSpringProfileEvenWithNonProductionEnvironmentLabel() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("postgres", "prod");

    assertThatThrownBy(
            () -> SandboxPaymentEnvironmentGuard.requireNonProduction("MoMo", "local", environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MoMo TEST payment integration is forbidden in production");
  }
}
