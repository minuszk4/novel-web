package com.novelweb.platform.system;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.identity.auth.GoogleSignInConfiguration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SystemStatusControllerTests {

  @Test
  void reportsGoogleConfigurationReadinessWithoutEnablingOtherIntegrations() {
    GoogleSignInConfiguration googleConfiguration =
        new GoogleSignInConfiguration(
            "1234567890-example.apps.googleusercontent.com",
            "https://api.example.test/api/v1/auth/google/credential",
            Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
    ApiSystemStatus status = new SystemStatusController("test", googleConfiguration).status();

    assertTrue(status.integrations().googleOidcEnabled());
    assertFalse(status.integrations().momoTestEnabled());
    assertFalse(status.features().commerceSandboxEnabled());
  }

  @Test
  void keepsGoogleDisabledWhenConfigurationIsIncomplete() {
    GoogleSignInConfiguration googleConfiguration = new GoogleSignInConfiguration("", "", "");
    ApiSystemStatus status = new SystemStatusController("test", googleConfiguration).status();

    assertFalse(status.integrations().googleOidcEnabled());
  }
}
