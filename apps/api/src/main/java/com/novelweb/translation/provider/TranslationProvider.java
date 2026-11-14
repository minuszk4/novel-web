package com.novelweb.translation.provider;

/**
 * Provider port. Implementations own network deadlines and must not log source text or secrets.
 * Cost estimation must use a local pricing snapshot; it must not send manuscript text or make a
 * billable provider request.
 */
public interface TranslationProvider {

  String id();

  TranslationProviderCapabilities capabilities();

  TranslationCostEstimate estimateCost(TranslationRequest request)
      throws TranslationProviderException;

  TranslationResponse translate(TranslationRequest request) throws TranslationProviderException;
}
