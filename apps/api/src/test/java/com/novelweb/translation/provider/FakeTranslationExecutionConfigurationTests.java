package com.novelweb.translation.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class FakeTranslationExecutionConfigurationTests {

  @Test
  void rejectsFakeProviderWhenTheApplicationEnvironmentIsProduction() {
    runner("production", "postgres", "translation-fake")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertFailure(
                  context.getStartupFailure(),
                  "Fake translation TEST/development integration is forbidden in production");
            });
  }

  @Test
  void rejectsFakeProviderWhenTheProductionSpringProfileIsActive() {
    runner("local", "postgres", "translation-fake", "prod")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertFailure(
                  context.getStartupFailure(),
                  "Fake translation TEST/development integration is forbidden in production");
            });
  }

  @Test
  void allowsTheExplicitFakeProviderInLocalDocker() {
    runner("local-docker", "postgres", "translation-fake")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(TranslationProviderExecutionGate.class);
            });
  }

  private static ApplicationContextRunner runner(
      String applicationEnvironment, String... activeProfiles) {
    return new ApplicationContextRunner()
        .withUserConfiguration(
            FakeTranslationExecutionConfiguration.class, FakeProviderBeanConfiguration.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles(activeProfiles))
        .withPropertyValues(
            "novel.translation.fake.enabled=true", "novel.environment=" + applicationEnvironment);
  }

  private static void assertFailure(Throwable failure, String expectedMessage) {
    StringWriter trace = new StringWriter();
    failure.printStackTrace(new PrintWriter(trace));
    assertThat(trace.toString()).contains(expectedMessage);
  }

  @Configuration(proxyBeanMethods = false)
  static class FakeProviderBeanConfiguration {

    @Bean
    FakeTranslationProvider fakeTranslationProvider() {
      return new FakeTranslationProvider();
    }
  }
}
