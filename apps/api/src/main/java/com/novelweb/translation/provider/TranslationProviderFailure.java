package com.novelweb.translation.provider;

/** Safe failure classes; raw provider bodies and credentials must never be attached. */
public enum TranslationProviderFailure {
  TEMPORARY,
  RATE_LIMITED,
  QUOTA_EXCEEDED,
  BILLING_DISABLED,
  UNSUPPORTED_LANGUAGE,
  INPUT_REJECTED,
  INVALID_RESPONSE
}
