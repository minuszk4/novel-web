package com.novelweb.translation.provider;

/** Safe failure classes; raw provider bodies and credentials must never be attached. */
public enum TranslationProviderFailure {
  TEMPORARY,
  TIMEOUT,
  // Adapters may use this only when the provider guarantees the request was not accepted/billed.
  RATE_LIMITED,
  QUOTA_EXCEEDED,
  COST_LIMIT_EXCEEDED,
  BILLING_LIMIT_EXCEEDED,
  BILLING_DISABLED,
  UNSUPPORTED_LANGUAGE,
  INPUT_REJECTED,
  INVALID_RESPONSE
}
