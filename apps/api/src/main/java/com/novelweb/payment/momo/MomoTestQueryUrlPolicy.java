package com.novelweb.payment.momo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Restricts status-query calls to MoMo's documented HTTPS Test endpoint. */
public final class MomoTestQueryUrlPolicy {

  private static final String HOST = "test-payment.momo.vn";
  private static final String PATH = "/v2/gateway/api/query";
  private static final int MAX_URL_LENGTH = 2_048;

  private MomoTestQueryUrlPolicy() {}

  public static boolean allows(String endpointUrl) {
    if (endpointUrl == null || endpointUrl.isBlank() || endpointUrl.length() > MAX_URL_LENGTH) {
      return false;
    }
    try {
      URI uri = new URI(endpointUrl);
      String scheme = uri.getScheme();
      return uri.isAbsolute()
          && scheme != null
          && scheme.toLowerCase(Locale.ROOT).equals("https")
          && uri.getHost() != null
          && uri.getHost().equalsIgnoreCase(HOST)
          && uri.getRawUserInfo() == null
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && PATH.equals(uri.getRawPath())
          && uri.getRawQuery() == null
          && uri.getRawFragment() == null;
    } catch (URISyntaxException exception) {
      return false;
    }
  }
}
