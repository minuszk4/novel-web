package com.novelweb.library.publication;

import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, fail-closed registry of explicitly configured purge destinations. */
public final class PublicationPurgeTargetRegistry {

  private static final Duration MAX_POLL_DELAY = Duration.ofMinutes(5);
  private static final int MAX_BATCH_SIZE = 20;

  private final Map<TargetCode, Target> targets;
  private final int batchSize;
  private final Duration pollDelay;
  private final boolean workerEnabled;

  private PublicationPurgeTargetRegistry(
      Map<TargetCode, Target> targets, int batchSize, Duration pollDelay, boolean workerEnabled) {
    this.targets = Map.copyOf(targets);
    this.batchSize = batchSize;
    this.pollDelay = pollDelay;
    this.workerEnabled = workerEnabled;
  }

  public static PublicationPurgeTargetRegistry from(PublicationPurgeProperties properties) {
    Objects.requireNonNull(properties, "publication purge properties are required");
    if (properties.batchSize() < 1 || properties.batchSize() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException("publication purge batch size must be between 1 and 20");
    }
    Duration pollDelay =
        Objects.requireNonNull(properties.pollDelay(), "purge poll delay is required");
    if (pollDelay.isNegative() || pollDelay.isZero() || pollDelay.compareTo(MAX_POLL_DELAY) > 0) {
      throw new IllegalArgumentException(
          "publication purge poll delay must be between 1 ms and 5 min");
    }

    Set<TargetCode> required = parseRequiredTargets(properties.requiredTargets());
    Map<TargetCode, Target> configured = new EnumMap<>(TargetCode.class);
    for (Map.Entry<String, PublicationPurgeProperties.TargetSettings> entry :
        properties.targets().entrySet()) {
      TargetCode code = parseTargetCode(entry.getKey());
      PublicationPurgeProperties.TargetSettings settings =
          Objects.requireNonNull(entry.getValue(), "purge target settings are required");
      String endpointValue = blankToNull(settings.endpoint());
      String token = blankToNull(settings.bearerToken());
      if (endpointValue == null && token == null) continue;
      if (endpointValue == null || token == null) {
        throw new IllegalArgumentException("each configured purge target needs endpoint and token");
      }
      URI endpoint = parseEndpoint(endpointValue);
      if (token.length() < 32 || token.length() > 512 || containsControlCharacter(token)) {
        throw new IllegalArgumentException(
            "purge target bearer token has an invalid length or character");
      }
      if (configured.put(code, new Target(code, endpoint, token)) != null) {
        throw new IllegalArgumentException("duplicate publication purge target");
      }
    }

    if (!required.equals(configured.keySet())) {
      throw new IllegalArgumentException(
          "required publication purge targets must exactly match configured targets");
    }
    if (properties.workerEnabled() && required.isEmpty()) {
      throw new IllegalArgumentException(
          "publication purge worker needs at least one required target");
    }
    return new PublicationPurgeTargetRegistry(
        configured, properties.batchSize(), pollDelay, properties.workerEnabled());
  }

  public Set<TargetCode> targetCodes() {
    return targets.keySet();
  }

  public Target target(TargetCode code) {
    return targets.get(code);
  }

  public int batchSize() {
    return batchSize;
  }

  public Duration pollDelay() {
    return pollDelay;
  }

  public boolean workerEnabled() {
    return workerEnabled;
  }

  private static Set<TargetCode> parseRequiredTargets(String value) {
    Set<TargetCode> result = new LinkedHashSet<>();
    if (value == null || value.isBlank()) return Set.of();
    for (String element : value.split(",")) {
      if (element.isBlank())
        throw new IllegalArgumentException("purge target list contains an empty item");
      if (!result.add(parseTargetCode(element))) {
        throw new IllegalArgumentException("purge target list contains a duplicate item");
      }
    }
    return Set.copyOf(result);
  }

  private static TargetCode parseTargetCode(String raw) {
    try {
      return TargetCode.valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("unsupported publication purge target", exception);
    }
  }

  private static URI parseEndpoint(String value) {
    final URI endpoint;
    try {
      endpoint = URI.create(value.trim());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("publication purge endpoint is invalid", exception);
    }
    if (!"https".equalsIgnoreCase(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getHost().isBlank()
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null) {
      throw new IllegalArgumentException(
          "publication purge endpoint must be an HTTPS URL without credentials");
    }
    return endpoint;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static boolean containsControlCharacter(String value) {
    return value.chars().anyMatch(Character::isISOControl);
  }

  public enum TargetCode {
    CDN,
    SEARCH_INDEX,
    FEED_CACHE,
    SIGNED_LINKS
  }

  public record Target(TargetCode code, URI endpoint, String bearerToken) {
    @Override
    public String toString() {
      return "Target[code=" + code + ", endpoint=[REDACTED], bearerToken=[REDACTED]]";
    }
  }
}
