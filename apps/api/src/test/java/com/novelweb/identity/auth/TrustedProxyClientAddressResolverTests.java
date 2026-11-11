package com.novelweb.identity.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TrustedProxyClientAddressResolverTests {

  @Test
  void ignoresForwardedHeaderWhenSocketPeerIsNotTrusted() throws Exception {
    TrustedProxyClientAddressResolver resolver =
        new TrustedProxyClientAddressResolver("10.20.0.0/16");
    MockHttpServletRequest request = request("203.0.113.8");
    request.addHeader("X-Forwarded-For", "198.51.100.7");

    assertArrayEquals(address("203.0.113.8"), resolver.resolve(request));
  }

  @Test
  void usesClientAddressWhenSocketPeerIsTrusted() throws Exception {
    TrustedProxyClientAddressResolver resolver =
        new TrustedProxyClientAddressResolver("10.20.0.0/16");
    MockHttpServletRequest request = request("10.20.0.5");
    request.addHeader("X-Forwarded-For", "198.51.100.7");

    assertArrayEquals(address("198.51.100.7"), resolver.resolve(request));
  }

  @Test
  void walksProxyChainFromNearestHopAndIgnoresSpoofedValuesToTheLeft() throws Exception {
    TrustedProxyClientAddressResolver resolver =
        new TrustedProxyClientAddressResolver("10.20.0.0/16,192.0.2.0/24");
    MockHttpServletRequest request = request("10.20.0.5");
    request.addHeader("X-Forwarded-For", "203.0.113.99, 198.51.100.7, 192.0.2.15");

    assertArrayEquals(address("198.51.100.7"), resolver.resolve(request));
  }

  @Test
  void supportsIpv6PeersAndNetworks() throws Exception {
    TrustedProxyClientAddressResolver resolver =
        new TrustedProxyClientAddressResolver("2001:db8:1234::/48");
    MockHttpServletRequest request = request("2001:db8:1234::5");
    request.addHeader("X-Forwarded-For", "2001:db8:ffff::7");

    assertArrayEquals(address("2001:db8:ffff::7"), resolver.resolve(request));
  }

  @Test
  void fallsBackToSocketPeerWhenTrustedProxySendsMalformedForwardedChain() throws Exception {
    TrustedProxyClientAddressResolver resolver =
        new TrustedProxyClientAddressResolver("10.20.0.0/16");
    MockHttpServletRequest request = request("10.20.0.5");
    request.addHeader("X-Forwarded-For", "198.51.100.7, not-an-ip");

    assertArrayEquals(address("10.20.0.5"), resolver.resolve(request));
  }

  @Test
  void fallsBackToSocketPeerWhenForwardedChainExceedsHopLimit() throws Exception {
    TrustedProxyClientAddressResolver resolver =
        new TrustedProxyClientAddressResolver("10.20.0.0/16");
    MockHttpServletRequest request = request("10.20.0.5");
    request.addHeader(
        "X-Forwarded-For",
        "198.51.100.1, 198.51.100.2, 198.51.100.3, 198.51.100.4, 198.51.100.5, "
            + "198.51.100.6, 198.51.100.7, 198.51.100.8, 198.51.100.9, 198.51.100.10, "
            + "198.51.100.11, 198.51.100.12, 198.51.100.13, 198.51.100.14, 198.51.100.15, "
            + "198.51.100.16, 198.51.100.17, 198.51.100.18, 198.51.100.19, 198.51.100.20, "
            + "198.51.100.21");

    assertArrayEquals(address("10.20.0.5"), resolver.resolve(request));
  }

  @Test
  void rejectsMalformedTrustedProxyConfigurationAtStartup() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrustedProxyClientAddressResolver("10.20.0.0/16,,192.0.2.1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrustedProxyClientAddressResolver("10.20.0.0/33"));
    assertThrows(
        IllegalArgumentException.class, () -> new TrustedProxyClientAddressResolver("0.0.0.0/0"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrustedProxyClientAddressResolver("proxy.internal/24"));
  }

  private static MockHttpServletRequest request(String remoteAddress) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddress);
    return request;
  }

  private static byte[] address(String value) throws Exception {
    return InetAddress.getByName(value).getAddress();
  }
}
