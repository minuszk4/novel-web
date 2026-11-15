package com.novelweb.payment.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novelweb.payment.credit.CreditPackCatalog.CreditPackQuote;
import com.novelweb.payment.credit.CreditPackCatalog.PaymentEnvironment;
import org.junit.jupiter.api.Test;

class CreditPackCatalogTests {

  @Test
  void exposesOnlyTheApprovedImmutableMomoTestFixture() {
    CreditPackCatalog catalog = CreditPackCatalog.momoTestFixture();

    assertThat(catalog.offers())
        .containsExactly(
            new CreditPackQuote(
                "momo-test-credits-100",
                1,
                PaymentEnvironment.TEST,
                "VND",
                1_000,
                100,
                0,
                null,
                null));
    assertThat(catalog.eligiblePublishedChapterUnlockCost()).isEqualTo(10);
    assertThat(catalog.quote("momo-test-credits-100")).contains(catalog.offers().getFirst());
    assertThat(catalog.quote("client-selected-amount")).isEmpty();
    assertThatThrownBy(() -> catalog.offers().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsNonVndOrInvalidQuoteValues() {
    assertThatThrownBy(
            () ->
                new CreditPackQuote(
                    "momo-test-credits-100",
                    1,
                    PaymentEnvironment.TEST,
                    "USD",
                    1_000,
                    100,
                    0,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CreditPackQuote(
                    "momo-test-credits-100",
                    0,
                    PaymentEnvironment.TEST,
                    "VND",
                    1_000,
                    100,
                    0,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
