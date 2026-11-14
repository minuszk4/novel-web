package com.novelweb.payment.momo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates server-side MoMo TEST settings without retaining or exposing credential values. */
public final class MomoTestConfigurationValidator {

  private static final String ENVIRONMENT_KEY = "MOMO_ENV";
  private static final String PARTNER_CODE_KEY = "MOMO_TEST_PARTNER_CODE";
  private static final String ACCESS_KEY = "MOMO_TEST_ACCESS_KEY";
  private static final String SECRET_KEY = "MOMO_TEST_SECRET_KEY";
  private static final String BASE_URL_KEY = "MOMO_TEST_BASE_URL";
  private static final String IPN_URL_KEY = "MOMO_TEST_IPN_URL";
  private static final String TEST_QUERY_PATH = "/v2/gateway/api/query";

  private MomoTestConfigurationValidator() {}

  /**
   * Returns only stable problem codes; configured values, including secrets, are never returned.
   */
  public static ValidationResult validate(Map<String, String> settings) {
    Map<String, String> config = settings == null ? Map.of() : settings;
    List<Problem> problems = new ArrayList<>();

    String environment = config.get(ENVIRONMENT_KEY);
    if (environment == null || !environment.toLowerCase(Locale.ROOT).equals("test")) {
      problems.add(Problem.TEST_ENVIRONMENT_REQUIRED);
    }
    if (isBlank(config.get(PARTNER_CODE_KEY))) problems.add(Problem.PARTNER_CODE_REQUIRED);
    if (isBlank(config.get(ACCESS_KEY))) problems.add(Problem.ACCESS_KEY_REQUIRED);
    if (isBlank(config.get(SECRET_KEY))) problems.add(Problem.SECRET_KEY_REQUIRED);
    if (!hasAllowedTestBaseUrl(config.get(BASE_URL_KEY))) {
      problems.add(Problem.TEST_BASE_URL_NOT_ALLOWED);
    }
    if (!isHttpsIpnUrl(config.get(IPN_URL_KEY))) problems.add(Problem.HTTPS_IPN_URL_REQUIRED);

    return new ValidationResult(problems);
  }

  private static boolean hasAllowedTestBaseUrl(String baseUrl) {
    if (isBlank(baseUrl)) return false;
    String normalizedBase =
        baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    return MomoTestQueryUrlPolicy.allows(normalizedBase + TEST_QUERY_PATH);
  }

  private static boolean isHttpsIpnUrl(String ipnUrl) {
    if (isBlank(ipnUrl) || ipnUrl.length() > 2_048) return false;
    try {
      URI uri = new URI(ipnUrl);
      String scheme = uri.getScheme();
      int port = uri.getPort();
      return uri.isAbsolute()
          && scheme != null
          && scheme.toLowerCase(Locale.ROOT).equals("https")
          && uri.getHost() != null
          && uri.getRawUserInfo() == null
          && uri.getRawFragment() == null
          && (port == -1 || (port >= 1 && port <= 65_535));
    } catch (URISyntaxException exception) {
      return false;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public enum Problem {
    TEST_ENVIRONMENT_REQUIRED,
    PARTNER_CODE_REQUIRED,
    ACCESS_KEY_REQUIRED,
    SECRET_KEY_REQUIRED,
    TEST_BASE_URL_NOT_ALLOWED,
    HTTPS_IPN_URL_REQUIRED
  }

  public record ValidationResult(List<Problem> problems) {

    public ValidationResult {
      problems = List.copyOf(problems);
    }

    public boolean ready() {
      return problems.isEmpty();
    }
  }
}
