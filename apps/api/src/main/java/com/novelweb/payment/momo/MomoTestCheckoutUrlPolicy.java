package com.novelweb.payment.momo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Restricts browser redirects to the documented MoMo Test one-time checkout URL. */
public final class MomoTestCheckoutUrlPolicy {

  private static final String HOST = "test-payment.momo.vn";
  private static final String PATH = "/v2/gateway/pay";
  private static final int MAX_URL_LENGTH = 4_096;

  private MomoTestCheckoutUrlPolicy() {}

  public static boolean allows(String checkoutUrl) {
    if (checkoutUrl == null || checkoutUrl.isBlank() || checkoutUrl.length() > MAX_URL_LENGTH) {
      return false;
    }
    try {
      URI uri = new URI(checkoutUrl);
      String scheme = uri.getScheme();
      return uri.isAbsolute()
          && scheme != null
          && scheme.toLowerCase(Locale.ROOT).equals("https")
          && uri.getHost() != null
          && uri.getHost().equalsIgnoreCase(HOST)
          && uri.getRawUserInfo() == null
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && PATH.equals(uri.getRawPath())
          && uri.getRawQuery() != null
          && !uri.getRawQuery().isBlank()
          && uri.getRawFragment() == null;
    } catch (URISyntaxException exception) {
      return false;
    }
  }
}
