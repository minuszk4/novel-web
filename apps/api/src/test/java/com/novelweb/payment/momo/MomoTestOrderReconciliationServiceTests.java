package com.novelweb.payment.momo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.novelweb.payment.credit.CreditLedgerService;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class MomoTestOrderReconciliationServiceTests {

  @Test
  void retryBackoffIsBoundedAndTheFinalAttemptMovesToManualReview() {
    assertEquals(60, MomoTestOrderReconciliationService.backoffSeconds(1));
    assertEquals(300, MomoTestOrderReconciliationService.backoffSeconds(2));
    assertEquals(900, MomoTestOrderReconciliationService.backoffSeconds(3));
    assertEquals(3_600, MomoTestOrderReconciliationService.backoffSeconds(4));
    assertEquals(14_400, MomoTestOrderReconciliationService.backoffSeconds(5));
    assertEquals(43_200, MomoTestOrderReconciliationService.backoffSeconds(6));
    assertEquals(86_400, MomoTestOrderReconciliationService.backoffSeconds(7));
    assertEquals(86_400, MomoTestOrderReconciliationService.backoffSeconds(8));
    assertThrows(
        IllegalArgumentException.class, () -> MomoTestOrderReconciliationService.backoffSeconds(0));
    assertThrows(
        IllegalArgumentException.class, () -> MomoTestOrderReconciliationService.backoffSeconds(9));
  }

  @Test
  void reconciliationPropertiesDefaultSafelyAndRejectUnboundedSettings() {
    MomoTestReconciliationProperties defaults =
        new MomoTestReconciliationProperties(false, null, 0);
    assertEquals(Duration.ofSeconds(30), defaults.pollDelay());
    assertEquals(5, defaults.batchSize());
    assertThrows(
        IllegalArgumentException.class,
        () -> new MomoTestReconciliationProperties(true, Duration.ofSeconds(29), 5));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MomoTestReconciliationProperties(true, Duration.ofSeconds(30), 21));
  }

  @Test
  void constructorRejectsMissingPartnerAndBatchOutsideTheBound() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MomoTestOrderReconciliationService(
                mock(DataSource.class),
                new CreditLedgerService(),
                " ",
                (request, amount) -> null,
                1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MomoTestOrderReconciliationService(
                mock(DataSource.class),
                new CreditLedgerService(),
                "MOMOTEST123",
                (request, amount) -> null,
                0));
  }
}
