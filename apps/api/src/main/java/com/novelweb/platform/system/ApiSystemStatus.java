package com.novelweb.platform.system;

public record ApiSystemStatus(
    String apiVersion, String environment, Integrations integrations, Features features) {

  public record Integrations(
      boolean googleOidcEnabled,
      boolean momoTestEnabled,
      String momoEnvironment,
      boolean vnpayTestEnabled,
      String vnpayEnvironment) {
    public Integrations(
        boolean googleOidcEnabled, boolean momoTestEnabled, String momoEnvironment) {
      this(googleOidcEnabled, momoTestEnabled, momoEnvironment, false, "OFF");
    }
  }

  public record Features(
      boolean importProcessingEnabled,
      boolean cultivationEnabled,
      boolean commerceSandboxEnabled,
      boolean publicCatalogEnabled,
      boolean publicationReleaseEnabled,
      boolean publicationSchedulerApiEnabled,
      boolean translationFakePreviewEnabled,
      boolean translationManualSubmissionEnabled,
      boolean translationPublicationEnabled) {}
}
