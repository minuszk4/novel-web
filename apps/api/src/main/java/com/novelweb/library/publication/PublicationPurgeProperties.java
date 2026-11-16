package com.novelweb.library.publication;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional external purge targets; endpoint credentials are never included in diagnostics. */
@ConfigurationProperties(prefix = "novel.publication.purge")
public record PublicationPurgeProperties(
    boolean workerEnabled,
    String requiredTargets,
    int batchSize,
    Duration pollDelay,
    Map<String, TargetSettings> targets) {

  public PublicationPurgeProperties {
    requiredTargets = requiredTargets == null ? "" : requiredTargets;
    targets = targets == null ? Map.of() : Map.copyOf(targets);
  }

  @Override
  public String toString() {
    return "PublicationPurgeProperties[workerEnabled="
        + workerEnabled
        + ", requiredTargets="
        + requiredTargets
        + ", batchSize="
        + batchSize
        + ", pollDelay="
        + pollDelay
        + ", targets="
        + targets.keySet()
        + ", credentials=[REDACTED]]";
  }

  public record TargetSettings(String endpoint, String bearerToken) {
    @Override
    public String toString() {
      return "TargetSettings[endpoint=[REDACTED], bearerToken=[REDACTED]]";
    }
  }
}
