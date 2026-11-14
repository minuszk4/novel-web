package com.novelweb.payment.momo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MomoTestConfigurationValidatorTests {

  private static final String TEST_BASE_URL = "https://test-payment.momo.vn";
  private static final String HTTPS_IPN_URL = "https://novel.example/api/v1/payments/momo/ipn";

  @Test
  void acceptsCompleteServerSideTestConfiguration() {
    assertThat(MomoTestConfigurationValidator.validate(validSettings()).ready()).isTrue();
    Map<String, String> trailingSlash = validSettings();
    trailingSlash.put("MOMO_TEST_BASE_URL", TEST_BASE_URL + "/");
    assertThat(MomoTestConfigurationValidator.validate(trailingSlash).ready()).isTrue();
  }

  @Test
  void reportsEveryMissingSettingWithoutReturningConfiguredValues() {
    MomoTestConfigurationValidator.ValidationResult result =
        MomoTestConfigurationValidator.validate(Map.of());

    assertThat(result.ready()).isFalse();
    assertThat(result.problems())
        .containsExactlyInAnyOrder(
            MomoTestConfigurationValidator.Problem.TEST_ENVIRONMENT_REQUIRED,
            MomoTestConfigurationValidator.Problem.PARTNER_CODE_REQUIRED,
            MomoTestConfigurationValidator.Problem.ACCESS_KEY_REQUIRED,
            MomoTestConfigurationValidator.Problem.SECRET_KEY_REQUIRED,
            MomoTestConfigurationValidator.Problem.TEST_BASE_URL_NOT_ALLOWED,
            MomoTestConfigurationValidator.Problem.HTTPS_IPN_URL_REQUIRED);
    Map<String, String> invalid = validSettings();
    invalid.put("MOMO_ENV", "production");
    MomoTestConfigurationValidator.ValidationResult invalidResult =
        MomoTestConfigurationValidator.validate(invalid);
    assertThat(invalidResult.toString()).doesNotContain("synthetic-secret", "synthetic-access");
    assertThat(MomoTestConfigurationValidator.validate(null).ready()).isFalse();
  }

  @Test
  void refusesProductionOrUnrecognizedEnvironmentModes() {
    Map<String, String> settings = validSettings();
    settings.put("MOMO_ENV", "production");
    assertThat(MomoTestConfigurationValidator.validate(settings).problems())
        .contains(MomoTestConfigurationValidator.Problem.TEST_ENVIRONMENT_REQUIRED);
    settings.put("MOMO_ENV", " TEST ");
    assertThat(MomoTestConfigurationValidator.validate(settings).problems())
        .contains(MomoTestConfigurationValidator.Problem.TEST_ENVIRONMENT_REQUIRED);
  }

  @Test
  void rejectsMissingOrLookalikeTestHostsInBaseUrl() {
    Map<String, String> settings = validSettings();
    settings.put("MOMO_TEST_BASE_URL", "https://payment.momo.vn");
    assertThat(MomoTestConfigurationValidator.validate(settings).problems())
        .contains(MomoTestConfigurationValidator.Problem.TEST_BASE_URL_NOT_ALLOWED);
    settings.put("MOMO_TEST_BASE_URL", "https://test-payment.momo.vn.attacker.example");
    assertThat(MomoTestConfigurationValidator.validate(settings).problems())
        .contains(MomoTestConfigurationValidator.Problem.TEST_BASE_URL_NOT_ALLOWED);
    settings.put("MOMO_TEST_BASE_URL", "https://test-payment.momo.vn:444");
    assertThat(MomoTestConfigurationValidator.validate(settings).problems())
        .contains(MomoTestConfigurationValidator.Problem.TEST_BASE_URL_NOT_ALLOWED);
  }

  @Test
  void rejectsInsecureOrMalformedIpnCallbacks() {
    Map<String, String> settings = validSettings();
    for (String url :
        new String[] {
          "http://novel.example/api/v1/payments/momo/ipn",
          "https://user@novel.example/api/v1/payments/momo/ipn",
          "https://novel.example/api/v1/payments/momo/ipn#fragment",
          "not-a-url"
        }) {
      settings.put("MOMO_TEST_IPN_URL", url);
      assertThat(MomoTestConfigurationValidator.validate(settings).problems())
          .as("IPN URL %s", url)
          .contains(MomoTestConfigurationValidator.Problem.HTTPS_IPN_URL_REQUIRED);
    }
  }

  private static Map<String, String> validSettings() {
    Map<String, String> settings = new HashMap<>();
    settings.put("MOMO_ENV", "test");
    settings.put("MOMO_TEST_PARTNER_CODE", "synthetic-partner");
    settings.put("MOMO_TEST_ACCESS_KEY", "synthetic-access");
    settings.put("MOMO_TEST_SECRET_KEY", "synthetic-secret");
    settings.put("MOMO_TEST_BASE_URL", TEST_BASE_URL);
    settings.put("MOMO_TEST_IPN_URL", HTTPS_IPN_URL);
    return settings;
  }
}
