package com.novelweb.platform;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class NonProductionIntegrationGuardTests {

  @Test
  void rejectsEveryProductionEnvironmentAliasRegardlessOfCaseOrWhitespace() {
    for (String environment : List.of("prod", "production", "live", " Production ")) {
      assertThatThrownBy(
              () ->
                  NonProductionIntegrationGuard.requireNonProduction(
                      "VNPAY", environment, new MockEnvironment()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("VNPAY TEST/development integration is forbidden in production");
    }
  }

  @Test
  void allowsLocalTestAndStagingLabels() {
    for (String environment : List.of("local", "local-docker", "test", "staging")) {
      assertThatCode(
              () ->
                  NonProductionIntegrationGuard.requireNonProduction(
                      "VNPAY", environment, new MockEnvironment()))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void rejectsProductionSpringProfileEvenWithNonProductionEnvironmentLabel() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("postgres", "prod");

    assertThatThrownBy(
            () -> NonProductionIntegrationGuard.requireNonProduction("MoMo", "local", environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("MoMo TEST/development integration is forbidden in production");
  }
}
