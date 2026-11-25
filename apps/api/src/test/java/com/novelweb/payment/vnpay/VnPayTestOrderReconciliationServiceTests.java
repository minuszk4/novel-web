package com.novelweb.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

import com.novelweb.payment.credit.CreditLedgerService;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class VnPayTestOrderReconciliationServiceTests {

  @Test
  void usesTheApprovedBoundedRetrySchedule() {
    assertThat(VnPayTestOrderReconciliationService.backoffSeconds(1)).isEqualTo(60);
    assertThat(VnPayTestOrderReconciliationService.backoffSeconds(2)).isEqualTo(300);
    assertThat(VnPayTestOrderReconciliationService.backoffSeconds(3)).isEqualTo(900);
    assertThat(VnPayTestOrderReconciliationService.backoffSeconds(4)).isEqualTo(3_600);
    assertThat(VnPayTestOrderReconciliationService.backoffSeconds(5)).isEqualTo(14_400);
    assertThat(VnPayTestOrderReconciliationService.backoffSeconds(6)).isEqualTo(43_200);
    assertThat(VnPayTestOrderReconciliationService.backoffSeconds(7)).isEqualTo(86_400);
    assertThat(VnPayTestOrderReconciliationService.backoffSeconds(8)).isEqualTo(86_400);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> VnPayTestOrderReconciliationService.backoffSeconds(0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> VnPayTestOrderReconciliationService.backoffSeconds(9));
  }

  @Test
  void defaultsWorkerSettingsOffAndValidatesPollingAndBatchBounds() {
    VnPayTestReconciliationProperties defaults =
        new VnPayTestReconciliationProperties(false, null, 0, "");
    assertThat(defaults.enabled()).isFalse();
    assertThat(defaults.pollDelay()).isEqualTo(Duration.ofSeconds(30));
    assertThat(defaults.batchSize()).isEqualTo(5);
    assertThat(defaults.serverIpAddress()).isEqualTo("127.0.0.1");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new VnPayTestReconciliationProperties(
                    true, Duration.ofSeconds(29), 5, "127.0.0.1"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new VnPayTestReconciliationProperties(
                    true, Duration.ofSeconds(30), 21, "127.0.0.1"));
  }

  @Test
  void rejectsInvalidMerchantAndBatchConfiguration() {
    DataSource dataSource = mock(DataSource.class);
    CreditLedgerService creditLedger = new CreditLedgerService();
    VnPayTestOrderReconciliationService.OrderQuery query = request -> null;
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new VnPayTestOrderReconciliationService(
                    dataSource, creditLedger, "short", "127.0.0.1", query, 5));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new VnPayTestOrderReconciliationService(
                    dataSource, creditLedger, "ABCD1234", "127.0.0.1", query, 0));
  }
}
