package com.novelweb.payment.vnpay;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Validated server-only VNPAY TEST credentials and public callback URLs. */
public record VnPayTestGatewaySettings(
    String tmnCode, String hashSecret, URI paymentUri, URI ipnUri, URI returnUri) {

  public static final URI SANDBOX_PAYMENT_URI =
      URI.create("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html");
  public static final String IPN_PATH = "/api/v1/payments/vnpay/ipn";

  public VnPayTestGatewaySettings {
    require(tmnCode, "VNPAY TEST TmnCode is required");
    require(hashSecret, "VNPAY TEST HashSecret is required");
    if (!tmnCode.matches("[A-Za-z0-9]{8}") || hashSecret.length() > 256) {
      throw new IllegalArgumentException("VNPAY TEST credential is invalid");
    }
    Objects.requireNonNull(paymentUri, "VNPAY sandbox payment URL is required");
    Objects.requireNonNull(ipnUri, "VNPAY TEST IPN URL is required");
    Objects.requireNonNull(returnUri, "VNPAY TEST return URL is required");
    if (!VnPayTestCheckoutUrlPolicy.isPaymentEndpoint(paymentUri.toString())) {
      throw new IllegalArgumentException("VNPAY payment URL must use the official sandbox");
    }
    if (!isPublicHttps(ipnUri) || !IPN_PATH.equals(ipnUri.getRawPath())) {
      throw new IllegalArgumentException("VNPAY TEST IPN URL must be public HTTPS at the IPN path");
    }
    if (!isPublicHttps(returnUri) || returnUri.toString().length() > 255) {
      throw new IllegalArgumentException("VNPAY TEST return URL must be public HTTPS");
    }
  }

  public static VnPayTestGatewaySettings fromProperties(
      VnPayTestGatewayProperties properties, String publicAppOrigin) {
    if (properties == null || blank(properties.tmnCode()) || blank(properties.hashSecret())) {
      throw new IllegalArgumentException("VNPAY TEST gateway configuration is incomplete");
    }
    URI origin = parsePublicOrigin(publicAppOrigin);
    URI paymentUri = SANDBOX_PAYMENT_URI;
    URI ipnUri = origin.resolve(IPN_PATH);
    URI returnUri = origin.resolve("/app/payment/result");
    return new VnPayTestGatewaySettings(
        properties.tmnCode().trim(), properties.hashSecret().trim(), paymentUri, ipnUri, returnUri);
  }

  @Override
  public String toString() {
    return "VnPayTestGatewaySettings[tmnCode="
        + tmnCode
        + ", hashSecret=[REDACTED], paymentUri="
        + paymentUri
        + ", ipnUri=[REDACTED], returnUri=[REDACTED]]";
  }

  private static boolean isPublicHttps(URI uri) {
    String host = uri.getHost();
    if (host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return false;
    String normalized = host.toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    return "https".equalsIgnoreCase(uri.getScheme())
        && (port == -1 || port == 443)
        && !normalized.equals("localhost")
        && !normalized.equals("127.0.0.1")
        && !normalized.equals("::1")
        && !normalized.endsWith(".localhost");
  }

  private static URI parsePublicOrigin(String value) {
    if (blank(value)) throw new IllegalArgumentException("VNPAY public app origin is required");
    try {
      URI origin = URI.create(value.trim().replaceAll("/+$", ""));
      if (origin.getRawPath() != null && !origin.getRawPath().isEmpty()) {
        throw new IllegalArgumentException("VNPAY public app origin must not include a path");
      }
      if (origin.getRawQuery() != null || origin.getRawFragment() != null) {
        throw new IllegalArgumentException("VNPAY public app origin must not include query data");
      }
      if (!isPublicHttps(origin)) {
        throw new IllegalArgumentException(
            "VNPAY TEST callbacks require a public HTTPS app origin");
      }
      return origin;
    } catch (IllegalArgumentException exception) {
      if (exception.getMessage() != null && exception.getMessage().startsWith("VNPAY")) {
        throw exception;
      }
      throw new IllegalArgumentException("VNPAY public app origin is invalid", exception);
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static void require(String value, String error) {
    if (blank(value)) throw new IllegalArgumentException(error);
  }
}
