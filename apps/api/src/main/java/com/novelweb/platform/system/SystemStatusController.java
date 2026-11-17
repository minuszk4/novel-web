package com.novelweb.platform.system;

import com.novelweb.identity.auth.GoogleSignInConfiguration;
import com.novelweb.library.PublicCatalogRateLimiter;
import com.novelweb.payment.momo.MomoTestGatewaySettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemStatusController {

  private final String environment;
  private final GoogleSignInConfiguration googleSignInConfiguration;
  private final boolean cultivationEnabled;
  private final boolean momoTestEnabled;
  private final boolean commerceSandboxEnabled;
  private final boolean publicCatalogEnabled;
  private final boolean publicationReleaseEnabled;
  private final boolean translationFakePreviewEnabled;
  private final boolean translationManualSubmissionEnabled;
  private final boolean translationPublicationEnabled;

  public SystemStatusController(
      @Value("${novel.environment:local}") String environment,
      GoogleSignInConfiguration googleSignInConfiguration,
      @Value("${novel.features.cultivation-enabled:false}") boolean cultivationEnabled,
      @Value("${novel.features.commerce-sandbox-enabled:false}") boolean commerceSandboxRequested,
      @Value("${novel.translation.fake.enabled:false}") boolean fakeTranslationEnabled,
      @Value("${novel.translation.fake.api-enabled:false}") boolean fakeTranslationApiEnabled,
      @Value("${novel.translation.fake.worker.enabled:false}") boolean fakeTranslationWorkerEnabled,
      @Value("${novel.translation.review.enabled:false}")
          boolean manualTranslationSubmissionRequested,
      @Value("${novel.translation.publication.enabled:false}")
          boolean translationPublicationRequested,
      ObjectProvider<MomoTestGatewaySettings> momoSettings,
      ObjectProvider<PublicCatalogRateLimiter> publicCatalogRateLimiter,
      Environment springEnvironment) {
    this.environment = environment;
    this.googleSignInConfiguration = googleSignInConfiguration;
    this.cultivationEnabled = cultivationEnabled;
    boolean postgresProfile = springEnvironment.acceptsProfiles(Profiles.of("postgres"));
    this.momoTestEnabled = postgresProfile && momoSettings.getIfAvailable() != null;
    this.commerceSandboxEnabled = postgresProfile && commerceSandboxRequested && momoTestEnabled;
    PublicCatalogRateLimiter catalogRateLimiter = publicCatalogRateLimiter.getIfAvailable();
    this.publicCatalogEnabled =
        postgresProfile
            && springEnvironment.getProperty(
                "novel.features.public-catalog-enabled", Boolean.class, false)
            && catalogRateLimiter != null
            && catalogRateLimiter.isConfigured();
    this.publicationReleaseEnabled =
        postgresProfile
            && springEnvironment.getProperty(
                "novel.publication.release-enabled", Boolean.class, false);
    this.translationFakePreviewEnabled =
        springEnvironment.acceptsProfiles(Profiles.of("postgres & translation-fake"))
            && fakeTranslationEnabled
            && fakeTranslationApiEnabled
            && fakeTranslationWorkerEnabled;
    this.translationManualSubmissionEnabled =
        this.translationFakePreviewEnabled && manualTranslationSubmissionRequested;
    this.translationPublicationEnabled =
        this.translationManualSubmissionEnabled && translationPublicationRequested;
  }

  @GetMapping("/status")
  public ApiSystemStatus status() {
    return new ApiSystemStatus(
        "v1",
        environment,
        new ApiSystemStatus.Integrations(
            googleSignInConfiguration.isReadyForSignIn(), momoTestEnabled, "TEST"),
        new ApiSystemStatus.Features(
            false,
            cultivationEnabled,
            commerceSandboxEnabled,
            publicCatalogEnabled,
            publicationReleaseEnabled,
            translationFakePreviewEnabled,
            translationManualSubmissionEnabled,
            translationPublicationEnabled));
  }
}
