package com.novelweb.platform.system;

import com.novelweb.identity.auth.GoogleSignInConfiguration;
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
  private final boolean translationFakePreviewEnabled;

  public SystemStatusController(
      @Value("${novel.environment:local}") String environment,
      GoogleSignInConfiguration googleSignInConfiguration,
      @Value("${novel.features.cultivation-enabled:false}") boolean cultivationEnabled,
      @Value("${novel.translation.fake.enabled:false}") boolean fakeTranslationEnabled,
      @Value("${novel.translation.fake.api-enabled:false}") boolean fakeTranslationApiEnabled,
      @Value("${novel.translation.fake.worker.enabled:false}") boolean fakeTranslationWorkerEnabled,
      Environment springEnvironment) {
    this.environment = environment;
    this.googleSignInConfiguration = googleSignInConfiguration;
    this.cultivationEnabled = cultivationEnabled;
    this.translationFakePreviewEnabled =
        springEnvironment.acceptsProfiles(Profiles.of("postgres & translation-fake"))
            && fakeTranslationEnabled
            && fakeTranslationApiEnabled
            && fakeTranslationWorkerEnabled;
  }

  @GetMapping("/status")
  public ApiSystemStatus status() {
    return new ApiSystemStatus(
        "v1",
        environment,
        new ApiSystemStatus.Integrations(
            googleSignInConfiguration.isReadyForSignIn(), false, "TEST"),
        new ApiSystemStatus.Features(
            false, cultivationEnabled, false, translationFakePreviewEnabled));
  }
}
