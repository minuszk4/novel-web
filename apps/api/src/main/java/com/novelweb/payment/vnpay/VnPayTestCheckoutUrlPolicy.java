package com.novelweb.payment.vnpay;

import java.net.URI;
import java.net.URISyntaxException;

/** Restricts hosted checkout redirects to the official VNPAY TEST payment endpoint. */
public final class VnPayTestCheckoutUrlPolicy {

  private VnPayTestCheckoutUrlPolicy() {}

  public static boolean allows(String checkoutUrl) {
    if (checkoutUrl == null || checkoutUrl.length() > 8_192) return false;
    try {
      URI uri = new URI(checkoutUrl);
      return isPaymentEndpoint(uri.toString())
          && uri.getRawQuery() != null
          && uri.getRawQuery().contains("vnp_SecureHash=");
    } catch (URISyntaxException exception) {
      return false;
    }
  }

  public static boolean isPaymentEndpoint(String url) {
    try {
      URI uri = new URI(url);
      return uri.isAbsolute()
          && "https".equalsIgnoreCase(uri.getScheme())
          && "sandbox.vnpayment.vn".equalsIgnoreCase(uri.getHost())
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && uri.getRawUserInfo() == null
          && "/paymentv2/vpcpay.html".equals(uri.getRawPath())
          && uri.getRawFragment() == null;
    } catch (URISyntaxException | NullPointerException exception) {
      return false;
    }
  }
}
