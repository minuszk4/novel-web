package com.novelweb.platform.system;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.identity.auth.GoogleSignInConfiguration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SystemStatusControllerTests {

  @Test
  void reportsGoogleConfigurationReadinessWithoutEnablingOtherIntegrations() {
    GoogleSignInConfiguration googleConfiguration =
        new GoogleSignInConfiguration(
            "1234567890-example.apps.googleusercontent.com",
            "https://api.example.test/api/v1/auth/google/credential",
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
    ApiSystemStatus status =
        new SystemStatusController(
                "test", googleConfiguration, false, false, false, false, new MockEnvironment())
            .status();

    assertTrue(status.integrations().googleOidcEnabled());
    assertFalse(status.integrations().momoTestEnabled());
    assertFalse(status.features().commerceSandboxEnabled());
    assertFalse(status.features().cultivationEnabled());
  }

  @Test
  void keepsGoogleDisabledWhenConfigurationIsIncomplete() {
    GoogleSignInConfiguration googleConfiguration = new GoogleSignInConfiguration("", "", "");
    ApiSystemStatus status =
        new SystemStatusController(
                "test", googleConfiguration, true, false, false, false, new MockEnvironment())
            .status();

    assertFalse(status.integrations().googleOidcEnabled());
    assertTrue(status.features().cultivationEnabled());
  }

  @Test
  void fakeTranslationPreviewRequiresBothOptInsAndBothSpringProfiles() {
    GoogleSignInConfiguration googleConfiguration = new GoogleSignInConfiguration("", "", "");
    MockEnvironment profiles = new MockEnvironment();
    profiles.setActiveProfiles("postgres", "translation-fake");

    ApiSystemStatus enabled =
        new SystemStatusController("local", googleConfiguration, false, true, true, true, profiles)
            .status();
    ApiSystemStatus apiOptInMissing =
        new SystemStatusController("local", googleConfiguration, false, true, false, true, profiles)
            .status();
    ApiSystemStatus providerOptInMissing =
        new SystemStatusController("local", googleConfiguration, false, false, true, true, profiles)
            .status();
    ApiSystemStatus workerOptInMissing =
        new SystemStatusController("local", googleConfiguration, false, true, true, false, profiles)
            .status();
    ApiSystemStatus profileMissing =
        new SystemStatusController(
                "local", googleConfiguration, false, true, true, true, new MockEnvironment())
            .status();

    assertTrue(enabled.features().translationFakePreviewEnabled());
    assertFalse(apiOptInMissing.features().translationFakePreviewEnabled());
    assertFalse(providerOptInMissing.features().translationFakePreviewEnabled());
    assertFalse(workerOptInMissing.features().translationFakePreviewEnabled());
    assertFalse(profileMissing.features().translationFakePreviewEnabled());
  }
}
