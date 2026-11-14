package com.novelweb.translation.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TranslationProviderContractTests {

  private final FakeTranslationProvider provider = new FakeTranslationProvider();

  @Test
  void advertisesExactLanguagePairsBoundsRateTimeoutAndNoGlossarySupport() {
    TranslationProviderCapabilities capabilities = provider.capabilities();

    assertTrue(capabilities.supports(new TranslationLanguagePair("VI", "en")));
    assertFalse(capabilities.supports(new TranslationLanguagePair("zh-Hans", "vi")));
    assertEquals(100, capabilities.maxSegmentsPerRequest());
    assertEquals(10_000, capabilities.maxCodePointsPerSegment());
    assertEquals(200_000, capabilities.maxCodePointsPerRequest());
    assertEquals(600, capabilities.requestsPerMinute());
    assertEquals(Duration.ofSeconds(5), capabilities.requestTimeout());
    assertFalse(capabilities.glossaryInstructionsSupported());
  }

  @Test
  void estimatesOfflineCostAndMapsStableSegmentIdsDeterministically() throws Exception {
    UUID attemptId = UUID.randomUUID();
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    TranslationRequest request =
        request(
            attemptId,
            List.of(
                new TranslationRequest.Segment(firstId, "Xin chào."),
                new TranslationRequest.Segment(secondId, "Tạm biệt.")));

    TranslationCostEstimate estimate = provider.estimateCost(request);
    TranslationResponse response = provider.translate(request);

    assertEquals(BigDecimal.ZERO, estimate.amount());
    assertEquals("USD", estimate.currency());
    assertEquals(18, estimate.billableUnits());
    assertEquals(TranslationBillingUnit.CODE_POINT, estimate.billingUnit());
    assertEquals("fake-free-v1", estimate.pricingVersion());
    assertEquals(attemptId, response.attemptId());
    assertEquals(
        List.of(firstId, secondId),
        response.segments().stream().map(TranslationResponse.SegmentResult::id).toList());
    assertEquals("[fake vi->en] Xin chào.", response.segments().getFirst().translatedText());
    assertEquals(18, response.billedUnits());
    assertEquals(TranslationBillingUnit.CODE_POINT, response.billingUnit());
    response.validateAgainst(request);
  }

  @Test
  void rejectsUnsupportedPairsAndProviderSpecificLimitsWithSanitizedFailures() {
    TranslationRequest unsupported =
        new TranslationRequest(
            UUID.randomUUID(),
            new TranslationLanguagePair("zh-Hans", "vi"),
            List.of(new TranslationRequest.Segment(UUID.randomUUID(), "你好")),
            List.of());
    TranslationProviderException unsupportedFailure =
        assertThrows(TranslationProviderException.class, () -> provider.translate(unsupported));
    assertEquals(TranslationProviderFailure.UNSUPPORTED_LANGUAGE, unsupportedFailure.failure());
    assertEquals("Translation provider: UNSUPPORTED_LANGUAGE", unsupportedFailure.getMessage());

    String tooLong = "x".repeat(10_001);
    TranslationProviderException sizeFailure =
        assertThrows(
            TranslationProviderException.class,
            () -> provider.estimateCost(request(UUID.randomUUID(), List.of(segment(tooLong)))));
    assertEquals(TranslationProviderFailure.INPUT_REJECTED, sizeFailure.failure());
  }

  @Test
  void rejectsResponseWithMissingSegmentIds() {
    UUID attemptId = UUID.randomUUID();
    TranslationRequest.Segment first = segment("source");
    TranslationRequest.Segment second = segment("second");
    TranslationRequest request = request(attemptId, List.of(first, second));
    TranslationResponse response =
        new TranslationResponse(
            attemptId,
            "provider-request-1",
            "fake-local",
            List.of(new TranslationResponse.SegmentResult(first.id(), "translation")),
            12,
            TranslationBillingUnit.CODE_POINT);

    TranslationProviderException failure =
        assertThrows(TranslationProviderException.class, () -> response.validateAgainst(request));

    assertEquals(TranslationProviderFailure.INVALID_RESPONSE, failure.failure());
  }

  @Test
  void requestAndResponseDiagnosticStringsNeverIncludeManuscriptOrGlossaryText() throws Exception {
    String sourceText = "A private manuscript sentence";
    String secretTerm = "private-character-name";
    TranslationRequest request =
        new TranslationRequest(
            UUID.randomUUID(),
            new TranslationLanguagePair("vi", "en"),
            List.of(segment(sourceText)),
            List.of(new TranslationRequest.GlossaryInstruction(secretTerm, "redacted", false)));
    TranslationResponse response =
        new TranslationResponse(
            request.attemptId(),
            "test-request",
            "test-model",
            List.of(
                new TranslationResponse.SegmentResult(
                    request.segments().getFirst().id(), sourceText)),
            request.sourceCodePoints(),
            TranslationBillingUnit.CODE_POINT);

    assertFalse(request.toString().contains(sourceText));
    assertFalse(request.toString().contains(secretTerm));
    assertFalse(request.segments().getFirst().toString().contains(sourceText));
    assertFalse(request.glossaryInstructions().getFirst().toString().contains(secretTerm));
    assertFalse(response.toString().contains(sourceText));
    assertFalse(response.toString().contains(secretTerm));
    assertFalse(response.segments().getFirst().toString().contains(sourceText));
  }

  @Test
  void validatesLanguageTagsAndProviderMetadata() {
    assertThrows(IllegalArgumentException.class, () -> new TranslationLanguagePair("en_US", "vi"));
    UUID duplicateId = UUID.randomUUID();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            request(
                UUID.randomUUID(),
                List.of(
                    new TranslationRequest.Segment(duplicateId, "first"),
                    new TranslationRequest.Segment(duplicateId, "duplicate"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TranslationProviderCapabilities(
                provider.capabilities().supportedLanguagePairs(),
                1,
                100,
                99,
                1,
                Duration.ofSeconds(1),
                false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TranslationCostEstimate(
                new BigDecimal("-0.01"), "USD", 1, TranslationBillingUnit.CODE_POINT, "v1"));
  }

  @Test
  void fakeProviderRequiresBothExplicitProfileAndPropertyOptIns() {
    ApplicationContextRunner providerContext =
        new ApplicationContextRunner().withUserConfiguration(FakeTranslationProvider.class);

    providerContext.run(
        context -> assertEquals(0, context.getBeanNamesForType(TranslationProvider.class).length));
    providerContext
        .withPropertyValues("novel.translation.fake.enabled=true")
        .run(
            context ->
                assertEquals(0, context.getBeanNamesForType(TranslationProvider.class).length));
    providerContext
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("translation-fake"))
        .run(
            context ->
                assertEquals(0, context.getBeanNamesForType(TranslationProvider.class).length));
    providerContext
        .withInitializer(context -> context.getEnvironment().setActiveProfiles("translation-fake"))
        .withPropertyValues("novel.translation.fake.enabled=true")
        .run(
            context ->
                assertEquals(1, context.getBeanNamesForType(TranslationProvider.class).length));
  }

  private static TranslationRequest request(
      UUID attemptId, List<TranslationRequest.Segment> segments) {
    return new TranslationRequest(
        attemptId, new TranslationLanguagePair("vi", "en"), segments, List.of());
  }

  private static TranslationRequest.Segment segment(String text) {
    return new TranslationRequest.Segment(UUID.randomUUID(), text);
  }
}
