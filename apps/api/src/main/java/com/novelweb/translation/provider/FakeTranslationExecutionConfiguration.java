package com.novelweb.translation.provider;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Supplies an execution gate only for the explicitly enabled offline fake provider. */
@Configuration(proxyBeanMethods = false)
@Profile("postgres & translation-fake")
@ConditionalOnProperty(prefix = "novel.translation.fake", name = "enabled", havingValue = "true")
public class FakeTranslationExecutionConfiguration {

  @Bean(destroyMethod = "close")
  TranslationProviderExecutionGate fakeTranslationProviderExecutionGate(
      FakeTranslationProvider provider) {
    TranslationProviderExecutionPolicy policy =
        new TranslationProviderExecutionPolicy(
            100,
            200_000,
            BigDecimal.ZERO,
            "USD",
            600,
            1,
            Duration.ofSeconds(5),
            0,
            Duration.ofMillis(100),
            Duration.ofSeconds(1));
    return new TranslationProviderExecutionGate(provider, policy);
  }
}
