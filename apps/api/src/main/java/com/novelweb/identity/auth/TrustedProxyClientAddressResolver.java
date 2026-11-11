package com.novelweb.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Resolves a client IP without accepting forwarding headers from untrusted peers. */
final class TrustedProxyClientAddressResolver {

  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
  private static final int MAX_FORWARDED_HOPS = 20;

  private final List<Network> trustedProxies;

  TrustedProxyClientAddressResolver(String trustedProxyCidrs) {
    this.trustedProxies = parseNetworks(trustedProxyCidrs);
  }

  byte[] resolve(HttpServletRequest request) {
    InetAddress peer = parseAddress(request.getRemoteAddr());
    if (!isTrusted(peer)) {
      return peer.getAddress();
    }

    List<InetAddress> forwarded = parseForwardedChain(request);
    if (forwarded.isEmpty()) {
      return peer.getAddress();
    }

    InetAddress client = peer;
    for (int index = forwarded.size() - 1; index >= 0 && isTrusted(client); index--) {
      client = forwarded.get(index);
    }
    return client.getAddress();
  }

  private boolean isTrusted(InetAddress address) {
    return trustedProxies.stream().anyMatch(network -> network.contains(address.getAddress()));
  }

  private static List<InetAddress> parseForwardedChain(HttpServletRequest request) {
    Enumeration<String> headerValues = request.getHeaders(FORWARDED_FOR_HEADER);
    if (headerValues == null || !headerValues.hasMoreElements()) {
      return List.of();
    }

    List<String> values = new ArrayList<>();
    while (headerValues.hasMoreElements()) {
      String header = headerValues.nextElement();
      if (header == null || header.isBlank()) {
        return List.of();
      }
      for (String item : header.split(",", -1)) {
        String candidate = item.trim();
        if (candidate.isEmpty() || values.size() >= MAX_FORWARDED_HOPS) {
          return List.of();
        }
        values.add(candidate);
      }
    }

    try {
      return values.stream().map(TrustedProxyClientAddressResolver::parseAddress).toList();
    } catch (IllegalArgumentException exception) {
      // A malformed chain falls back to the known socket peer, which may aggregate traffic at the
      // proxy but cannot let a caller choose an arbitrary rate-limit bucket.
      return List.of();
    }
  }

  private static List<Network> parseNetworks(String rawCidrs) {
    if (rawCidrs == null || rawCidrs.isBlank()) {
      return List.of();
    }

    String[] entries = rawCidrs.split(",", -1);
    List<Network> networks = new ArrayList<>(entries.length);
    for (String entry : entries) {
      String cidr = entry.trim();
      if (cidr.isEmpty()) {
        throw new IllegalArgumentException("Trusted proxy CIDR list contains an empty entry");
      }
      networks.add(Network.parse(cidr));
    }
    return Collections.unmodifiableList(networks);
  }

  private static InetAddress parseAddress(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("IP address is missing");
    }
    String candidate = value.trim();
    if (candidate.indexOf(':') < 0) {
      return parseIpv4(candidate);
    }
    if (!candidate.matches("[0-9A-Fa-f:.]+")) {
      throw new IllegalArgumentException("IP address is not a numeric IPv6 literal");
    }
    try {
      InetAddress address = InetAddress.getByName(candidate);
      if (address.getAddress().length != 16) {
        throw new IllegalArgumentException("IP address is not an IPv6 literal");
      }
      return address;
    } catch (UnknownHostException exception) {
      throw new IllegalArgumentException("IP address is invalid", exception);
    }
  }

  private static InetAddress parseIpv4(String value) {
    String[] octets = value.split("\\.", -1);
    if (octets.length != 4) {
      throw new IllegalArgumentException("IP address is not an IPv4 literal");
    }
    byte[] address = new byte[4];
    for (int index = 0; index < octets.length; index++) {
      if (!octets[index].matches("0|[1-9][0-9]{0,2}")) {
        throw new IllegalArgumentException("IP address is not a canonical IPv4 literal");
      }
      int octet = Integer.parseInt(octets[index]);
      if (octet > 255) {
        throw new IllegalArgumentException("IPv4 octet is out of range");
      }
      address[index] = (byte) octet;
    }
    try {
      return InetAddress.getByAddress(address);
    } catch (UnknownHostException exception) {
      throw new IllegalArgumentException("IPv4 address is invalid", exception);
    }
  }

  private record Network(byte[] address, int prefixLength) {

    private Network {
      address = address.clone();
    }

    static Network parse(String cidr) {
      String[] parts = cidr.split("/", -1);
      if (parts.length > 2 || parts[0].isBlank()) {
        throw new IllegalArgumentException("Trusted proxy CIDR is invalid");
      }
      byte[] address = parseAddress(parts[0]).getAddress();
      int maximumPrefix = address.length * Byte.SIZE;
      int prefix = maximumPrefix;
      if (parts.length == 2) {
        if (!parts[1].matches("0|[1-9][0-9]{0,2}")) {
          throw new IllegalArgumentException("Trusted proxy CIDR prefix is invalid");
        }
        prefix = Integer.parseInt(parts[1]);
        if (prefix == 0 || prefix > maximumPrefix) {
          throw new IllegalArgumentException("Trusted proxy CIDR prefix is out of range");
        }
      }
      return new Network(address, prefix);
    }

    boolean contains(byte[] candidate) {
      if (candidate.length != address.length) {
        return false;
      }
      int fullBytes = prefixLength / Byte.SIZE;
      int remainingBits = prefixLength % Byte.SIZE;
      for (int index = 0; index < fullBytes; index++) {
        if (candidate[index] != address[index]) {
          return false;
        }
      }
      if (remainingBits == 0) {
        return true;
      }
      int mask = 0xff << (Byte.SIZE - remainingBits);
      return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
    }
  }
}
