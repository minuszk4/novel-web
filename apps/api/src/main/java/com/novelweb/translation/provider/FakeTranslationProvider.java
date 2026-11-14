package com.novelweb.translation.provider;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Offline development provider; its output is visibly marked and is not a real translation. */
@Component
@Profile("translation-fake")
@ConditionalOnProperty(prefix = "novel.translation.fake", name = "enabled", havingValue = "true")
public final class FakeTranslationProvider implements TranslationProvider {

  private static final TranslationLanguagePair VI_EN = new TranslationLanguagePair("vi", "en");
  private static final TranslationLanguagePair EN_VI = new TranslationLanguagePair("en", "vi");
  private static final TranslationProviderCapabilities CAPABILITIES =
      new TranslationProviderCapabilities(
          Set.of(VI_EN, EN_VI), 100, 10_000, 200_000, 600, Duration.ofSeconds(5), false);

  @Override
  public String id() {
    return "fake-local";
  }

  @Override
  public TranslationProviderCapabilities capabilities() {
    return CAPABILITIES;
  }

  @Override
  public TranslationCostEstimate estimateCost(TranslationRequest request)
      throws TranslationProviderException {
    validate(request);
    return new TranslationCostEstimate(
        BigDecimal.ZERO,
        "USD",
        request.sourceCodePoints(),
        TranslationBillingUnit.CODE_POINT,
        "fake-free-v1");
  }

  @Override
  public TranslationResponse translate(TranslationRequest request)
      throws TranslationProviderException {
    TranslationCostEstimate estimate = estimateCost(request);
    String prefix =
        "[fake "
            + request.languagePair().sourceLanguage()
            + "->"
            + request.languagePair().targetLanguage()
            + "] ";
    List<TranslationResponse.SegmentResult> results =
        request.segments().stream()
            .map(
                segment ->
                    new TranslationResponse.SegmentResult(
                        segment.id(), prefix + segment.sourceText()))
            .toList();
    TranslationResponse response =
        new TranslationResponse(
            request.attemptId(),
            "fake-" + request.attemptId(),
            id(),
            results,
            estimate.billableUnits(),
            estimate.billingUnit());
    response.validateAgainst(request);
    return response;
  }

  private void validate(TranslationRequest request) throws TranslationProviderException {
    if (request == null)
      throw new TranslationProviderException(TranslationProviderFailure.INPUT_REJECTED, null);
    request.validateAgainst(CAPABILITIES);
  }
}
