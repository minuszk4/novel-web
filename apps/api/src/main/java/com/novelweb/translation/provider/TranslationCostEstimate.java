package com.novelweb.translation.provider;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** A preflight estimate tied to a provider pricing version; never a final ledger charge. */
public record TranslationCostEstimate(
    BigDecimal amount,
    String currency,
    long billableUnits,
    TranslationBillingUnit billingUnit,
    String pricingVersion) {

  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern PRICING_VERSION = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  public TranslationCostEstimate {
    if (amount == null || amount.signum() < 0 || amount.scale() > 8) {
      throw new IllegalArgumentException(
          "Estimated amount must be non-negative with at most 8 decimals");
    }
    if (currency == null || !CURRENCY.matcher(currency).matches()) {
      throw new IllegalArgumentException("Currency must be an uppercase three-letter code");
    }
    if (billableUnits < 0 || billingUnit == null) {
      throw new IllegalArgumentException("Billable units and their meter must be valid");
    }
    if (pricingVersion == null || !PRICING_VERSION.matcher(pricingVersion).matches()) {
      throw new IllegalArgumentException("A safe pricing version is required");
    }
  }
}
