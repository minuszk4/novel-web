package com.novelweb.platform;

import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Prevents test-only or development-only integrations from running in production. */
public final class NonProductionIntegrationGuard {

  private static final Set<String> PRODUCTION_ENVIRONMENTS = Set.of("prod", "production", "live");
  private static final Profiles PRODUCTION_PROFILES = Profiles.of("prod", "production");

  private NonProductionIntegrationGuard() {}

  public static void requireNonProduction(
      String integrationName, String applicationEnvironment, Environment springEnvironment) {
    String normalizedEnvironment =
        applicationEnvironment == null
            ? ""
            : applicationEnvironment.trim().toLowerCase(Locale.ROOT);
    if (PRODUCTION_ENVIRONMENTS.contains(normalizedEnvironment)
        || (springEnvironment != null && springEnvironment.acceptsProfiles(PRODUCTION_PROFILES))) {
      throw new IllegalStateException(
          integrationName + " TEST/development integration is forbidden in production");
    }
  }
}
