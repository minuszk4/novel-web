package com.novelweb.library.publication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PublicationPurgeTargetRegistryTest {

  private static final String TOKEN = "A".repeat(32);

  @Test
  void requiresExactTargetCoverageAndOnlyAcceptsCredentialedHttpsTargets() {
    var target =
        new PublicationPurgeProperties.TargetSettings("https://purge.example.test/v1/work", TOKEN);
    var properties =
        new PublicationPurgeProperties(
            false, "CDN", 5, Duration.ofSeconds(30), Map.of("cdn", target));

    PublicationPurgeTargetRegistry registry = PublicationPurgeTargetRegistry.from(properties);

    assertEquals(1, registry.targetCodes().size());
    assertTrue(
        registry
            .target(PublicationPurgeTargetRegistry.TargetCode.CDN)
            .toString()
            .contains("REDACTED"));
    assertTrue(properties.toString().contains("REDACTED"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PublicationPurgeTargetRegistry.from(
                new PublicationPurgeProperties(
                    false, "CDN,FEED_CACHE", 5, Duration.ofSeconds(30), Map.of("cdn", target))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PublicationPurgeTargetRegistry.from(
                new PublicationPurgeProperties(
                    false,
                    "CDN",
                    5,
                    Duration.ofSeconds(30),
                    Map.of(
                        "cdn",
                        new PublicationPurgeProperties.TargetSettings(
                            "http://purge.example.test/v1/work", TOKEN)))));
  }

  @Test
  void doesNotAllowEnablingWorkerWithoutConfiguredRequiredTargets() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PublicationPurgeTargetRegistry.from(
                new PublicationPurgeProperties(true, "", 5, Duration.ofSeconds(30), Map.of())));
  }
}
