package com.novelweb.payment.credit;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable server-owned quote catalog for the explicitly enabled MoMo TEST environment. */
public final class CreditPackCatalog {

  private static final String TEST_PACK_ID = "momo-test-credits-100";
  private static final int TEST_PACK_VERSION = 1;
  private static final long TEST_PACK_PRICE_VND = 1_000;
  private static final long TEST_PAID_CREDITS = 100;
  private static final long TEST_BONUS_CREDITS = 0;
  private static final long TEST_CHAPTER_UNLOCK_CREDITS = 10;
  private static final int TEST_CHAPTER_UNLOCK_PRICE_VERSION = 1;

  private final List<CreditPackQuote> quotes;

  private CreditPackCatalog(List<CreditPackQuote> quotes) {
    Objects.requireNonNull(quotes, "credit pack quotes are required");
    Set<String> packVersions = new HashSet<>();
    for (CreditPackQuote quote : quotes) {
      Objects.requireNonNull(quote, "credit pack quote is required");
      if (quote.environment() != PaymentEnvironment.TEST) {
        throw new IllegalArgumentException("this catalog accepts MoMo TEST quotes only");
      }
      if (!packVersions.add(quote.packId() + ":" + quote.version())) {
        throw new IllegalArgumentException("duplicate credit pack version");
      }
    }
    this.quotes = List.copyOf(quotes);
  }

  /** Builds the single owner-approved sandbox fixture; it contains no production offer. */
  public static CreditPackCatalog momoTestFixture() {
    return new CreditPackCatalog(
        List.of(
            new CreditPackQuote(
                TEST_PACK_ID,
                TEST_PACK_VERSION,
                PaymentEnvironment.TEST,
                "VND",
                TEST_PACK_PRICE_VND,
                TEST_PAID_CREDITS,
                TEST_BONUS_CREDITS,
                null,
                null)));
  }

  /** Returns immutable server-owned quotes. Clients never supply price, amount or provider URL. */
  public List<CreditPackQuote> offers() {
    return quotes;
  }

  public Optional<CreditPackQuote> quote(String packId) {
    if (packId == null) return Optional.empty();
    return quotes.stream().filter(item -> item.packId().equals(packId)).findFirst();
  }

  /** Price for an eligible published chapter in the same approved TEST fixture. */
  public long eligiblePublishedChapterUnlockCost() {
    return TEST_CHAPTER_UNLOCK_CREDITS;
  }

  /** Version of the fixed test-only published-chapter credit price. */
  public int eligiblePublishedChapterPriceVersion() {
    return TEST_CHAPTER_UNLOCK_PRICE_VERSION;
  }

  public enum PaymentEnvironment {
    TEST
  }

  /** Immutable order/quote fields that a later payment-order row must snapshot. */
  public record CreditPackQuote(
      String packId,
      int version,
      PaymentEnvironment environment,
      String currency,
      long amountVnd,
      long paidCredits,
      long bonusCredits,
      Instant paidExpiresAt,
      Instant bonusExpiresAt) {
    public CreditPackQuote {
      Objects.requireNonNull(packId, "pack id is required");
      Objects.requireNonNull(environment, "payment environment is required");
      Objects.requireNonNull(currency, "currency is required");
      if (packId.isBlank()
          || version < 1
          || amountVnd <= 0
          || paidCredits <= 0
          || bonusCredits < 0) {
        throw new IllegalArgumentException("credit pack quote values are invalid");
      }
      if (!currency.equals("VND")) {
        throw new IllegalArgumentException("MoMo credit pack amount must be VND");
      }
      if (Math.addExact(paidCredits, bonusCredits) <= 0) {
        throw new IllegalArgumentException("credit pack must grant positive credits");
      }
      if (bonusCredits == 0 && bonusExpiresAt != null) {
        throw new IllegalArgumentException(
            "a pack without bonus credit cannot define bonus expiry");
      }
    }
  }
}
