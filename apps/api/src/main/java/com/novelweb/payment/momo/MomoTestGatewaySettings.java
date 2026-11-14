package com.novelweb.payment.momo;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Validated, server-only credentials and callback settings for the MoMo TEST environment. */
public record MomoTestGatewaySettings(
    String partnerCode,
    String accessKey,
    String secretKey,
    URI baseUri,
    URI ipnUri,
    URI redirectUri) {

  public MomoTestGatewaySettings {
    requireNonBlank(partnerCode, "MoMo TEST partner code is required");
    if (partnerCode.length() > 50) {
      throw new IllegalArgumentException("MoMo TEST partner code is invalid");
    }
    requireNonBlank(accessKey, "MoMo TEST access key is required");
    requireNonBlank(secretKey, "MoMo TEST secret key is required");
    Objects.requireNonNull(baseUri, "MoMo TEST base URL is required");
    Objects.requireNonNull(ipnUri, "MoMo TEST IPN URL is required");
    Objects.requireNonNull(redirectUri, "MoMo TEST redirect URL is required");
    String normalizedBase = stripTrailingSlash(baseUri.toString());
    if (!MomoTestQueryUrlPolicy.allows(normalizedBase + "/v2/gateway/api/query")
        || !MomoTestCreateUrlPolicy.allows(normalizedBase + "/v2/gateway/api/create")) {
      throw new IllegalArgumentException("MoMo TEST gateway URL is not allowed");
    }
    if (!isSafeHttpsCallback(ipnUri) || !isSafeHttpsCallback(redirectUri)) {
      throw new IllegalArgumentException("MoMo TEST callback URL is not allowed");
    }
    baseUri = URI.create(normalizedBase);
  }

  public static MomoTestGatewaySettings fromEnvironment(Map<String, String> values) {
    MomoTestConfigurationValidator.ValidationResult validation =
        MomoTestConfigurationValidator.validate(values);
    if (!validation.ready()) {
      throw new IllegalArgumentException("MoMo TEST gateway configuration is invalid");
    }
    String baseUrl = stripTrailingSlash(values.get("MOMO_TEST_BASE_URL"));
    return new MomoTestGatewaySettings(
        values.get("MOMO_TEST_PARTNER_CODE"),
        values.get("MOMO_TEST_ACCESS_KEY"),
        values.get("MOMO_TEST_SECRET_KEY"),
        URI.create(baseUrl),
        URI.create(values.get("MOMO_TEST_IPN_URL")),
        URI.create(values.get("MOMO_TEST_REDIRECT_URL")));
  }

  public URI createEndpoint() {
    return baseUri.resolve("/v2/gateway/api/create");
  }

  public URI queryEndpoint() {
    return baseUri.resolve("/v2/gateway/api/query");
  }

  @Override
  public String toString() {
    return "MomoTestGatewaySettings[partnerCode="
        + partnerCode
        + ", accessKey=[REDACTED], secretKey=[REDACTED], baseUri="
        + baseUri
        + ", ipnUri=[REDACTED], redirectUri=[REDACTED]"
        + "]";
  }

  private static boolean isSafeHttpsCallback(URI uri) {
    int port = uri.getPort();
    return "https".equalsIgnoreCase(uri.getScheme())
        && uri.getHost() != null
        && uri.getRawUserInfo() == null
        && uri.getRawFragment() == null
        && (port == -1 || (port >= 1 && port <= 65_535));
  }

  private static String stripTrailingSlash(String value) {
    Objects.requireNonNull(value, "MoMo TEST base URL is required");
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private static void requireNonBlank(String value, String message) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
  }
}
