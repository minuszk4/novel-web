package com.novelweb.payment;

import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Prevents payment sandbox adapters from being installed in a production runtime. */
public final class SandboxPaymentEnvironmentGuard {

  private static final Set<String> PRODUCTION_ENVIRONMENTS = Set.of("prod", "production", "live");
  private static final Profiles PRODUCTION_PROFILES = Profiles.of("prod", "production");

  private SandboxPaymentEnvironmentGuard() {}

  public static void requireNonProduction(
      String providerName, String applicationEnvironment, Environment springEnvironment) {
    String normalizedEnvironment =
        applicationEnvironment == null
            ? ""
            : applicationEnvironment.trim().toLowerCase(Locale.ROOT);
    if (PRODUCTION_ENVIRONMENTS.contains(normalizedEnvironment)
        || (springEnvironment != null && springEnvironment.acceptsProfiles(PRODUCTION_PROFILES))) {
      throw new IllegalStateException(
          providerName + " TEST payment integration is forbidden in production");
    }
  }
}
