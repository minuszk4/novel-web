package com.novelweb.translation.provider;

/** Provider port. Implementations own network timeouts and must not log source text or secrets. */
public interface TranslationProvider {

  String id();

  TranslationProviderCapabilities capabilities();

  TranslationCostEstimate estimateCost(TranslationRequest request)
      throws TranslationProviderException;

  TranslationResponse translate(TranslationRequest request) throws TranslationProviderException;
}
