package com.novelweb.platform.system;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.novelweb.identity.auth.GoogleSignInConfiguration;
import com.novelweb.payment.momo.MomoTestGatewaySettings;
import java.net.URI;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
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
        controller(googleConfiguration, false, false, false, false, false, false, new String[0])
            .status();

    assertTrue(status.integrations().googleOidcEnabled());
    assertFalse(status.integrations().momoTestEnabled());
    assertFalse(status.features().commerceSandboxEnabled());
    assertFalse(status.features().cultivationEnabled());
  }

  @Test
  void reportsCommerceOnlyWhenPostgresValidMomoSettingsAndTheShopOptInAreAllPresent() {
    GoogleSignInConfiguration googleConfiguration = new GoogleSignInConfiguration("", "", "");
    ApiSystemStatus enabled =
        controller(googleConfiguration, true, true, false, false, false, true, "postgres").status();
    ApiSystemStatus shopOptInMissing =
        controller(googleConfiguration, false, false, false, false, false, true, "postgres")
            .status();
    ApiSystemStatus settingsMissing =
        controller(googleConfiguration, true, true, false, false, false, false, "postgres")
            .status();
    ApiSystemStatus postgresMissing =
        controller(googleConfiguration, true, true, false, false, false, true).status();

    assertTrue(enabled.integrations().momoTestEnabled());
    assertTrue(enabled.features().commerceSandboxEnabled());
    assertTrue(shopOptInMissing.integrations().momoTestEnabled());
    assertFalse(shopOptInMissing.features().commerceSandboxEnabled());
    assertFalse(settingsMissing.integrations().momoTestEnabled());
    assertFalse(settingsMissing.features().commerceSandboxEnabled());
    assertFalse(postgresMissing.integrations().momoTestEnabled());
    assertFalse(postgresMissing.features().commerceSandboxEnabled());
  }

  @Test
  void keepsGoogleDisabledWhenConfigurationIsIncomplete() {
    GoogleSignInConfiguration googleConfiguration = new GoogleSignInConfiguration("", "", "");
    ApiSystemStatus status =
        controller(googleConfiguration, true, false, false, false, false, false, new String[0])
            .status();

    assertFalse(status.integrations().googleOidcEnabled());
    assertFalse(status.integrations().momoTestEnabled());
    assertTrue(status.features().cultivationEnabled());
    assertFalse(status.features().commerceSandboxEnabled());
  }

  @Test
  void fakeTranslationPreviewRequiresBothOptInsAndBothSpringProfiles() {
    GoogleSignInConfiguration googleConfiguration = new GoogleSignInConfiguration("", "", "");
    ApiSystemStatus enabled =
        controller(
                googleConfiguration,
                false,
                false,
                true,
                true,
                true,
                false,
                "postgres",
                "translation-fake")
            .status();
    ApiSystemStatus apiOptInMissing =
        controller(
                googleConfiguration,
                false,
                false,
                true,
                false,
                true,
                false,
                "postgres",
                "translation-fake")
            .status();
    ApiSystemStatus workerOptInMissing =
        controller(
                googleConfiguration,
                false,
                false,
                true,
                true,
                false,
                false,
                "postgres",
                "translation-fake")
            .status();
    ApiSystemStatus providerOptInMissing =
        controller(
                googleConfiguration,
                false,
                false,
                false,
                true,
                true,
                false,
                "postgres",
                "translation-fake")
            .status();
    ApiSystemStatus profileMissing =
        controller(googleConfiguration, false, false, true, true, true, false, "postgres").status();

    assertTrue(enabled.features().translationFakePreviewEnabled());
    assertFalse(apiOptInMissing.features().translationFakePreviewEnabled());
    assertFalse(workerOptInMissing.features().translationFakePreviewEnabled());
    assertFalse(providerOptInMissing.features().translationFakePreviewEnabled());
    assertFalse(profileMissing.features().translationFakePreviewEnabled());
  }

  @Test
  void publicCatalogRequiresPostgresProfileAndExplicitOptIn() {
    ApiSystemStatus enabled = controllerWithCatalog(true, "postgres").status();
    ApiSystemStatus optInMissing = controllerWithCatalog(false, "postgres").status();
    ApiSystemStatus profileMissing = controllerWithCatalog(true).status();

    assertTrue(enabled.features().publicCatalogEnabled());
    assertFalse(optInMissing.features().publicCatalogEnabled());
    assertFalse(profileMissing.features().publicCatalogEnabled());
  }

  private static SystemStatusController controller(
      GoogleSignInConfiguration googleConfiguration,
      boolean cultivation,
      boolean commerce,
      boolean translationEnabled,
      boolean translationApiEnabled,
      boolean translationWorkerEnabled,
      boolean momoSettingsPresent,
      String... activeProfiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(activeProfiles);
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    if (momoSettingsPresent) beanFactory.addBean("momoTestSettings", momoSettings());
    ObjectProvider<MomoTestGatewaySettings> momoSettings =
        beanFactory.getBeanProvider(MomoTestGatewaySettings.class);
    return new SystemStatusController(
        "test",
        googleConfiguration,
        cultivation,
        commerce,
        translationEnabled,
        translationApiEnabled,
        translationWorkerEnabled,
        momoSettings,
        environment);
  }

  private static SystemStatusController controllerWithCatalog(
      boolean publicCatalogRequested, String... activeProfiles) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(activeProfiles);
    environment.setProperty(
        "novel.features.public-catalog-enabled", Boolean.toString(publicCatalogRequested));
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    ObjectProvider<MomoTestGatewaySettings> momoSettings =
        beanFactory.getBeanProvider(MomoTestGatewaySettings.class);
    return new SystemStatusController(
        "test",
        new GoogleSignInConfiguration("", "", ""),
        false,
        false,
        false,
        false,
        false,
        momoSettings,
        environment);
  }

  private static MomoTestGatewaySettings momoSettings() {
    return new MomoTestGatewaySettings(
        "synthetic-partner",
        "synthetic-access",
        "synthetic-secret",
        URI.create("https://test-payment.momo.vn"),
        URI.create("https://novel.example/api/v1/payments/momo/ipn"),
        URI.create("https://novel.example/app/payment/result"));
  }
}
