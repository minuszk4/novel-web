package com.novelweb.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class VnPayTestGatewaySettingsTests {

  private static final String ORIGIN = "https://outshine-chair-decipher.ngrok-free.dev";

  @Test
  void derivesBothCallbacksFromTheExistingPublicAppOrigin() {
    VnPayTestGatewaySettings settings =
        VnPayTestGatewaySettings.fromProperties(
            new VnPayTestGatewayProperties(true, "ABCD1234", "synthetic-vnpay-secret"), ORIGIN);

    assertThat(settings.paymentUri()).isEqualTo(VnPayTestGatewaySettings.SANDBOX_PAYMENT_URI);
    assertThat(settings.ipnUri().toString()).isEqualTo(ORIGIN + "/api/v1/payments/vnpay/ipn");
    assertThat(settings.returnUri().toString()).isEqualTo(ORIGIN + "/app/payment/result");
    assertThat(settings.toString()).doesNotContain("synthetic-vnpay-secret");
  }

  @Test
  void requiresAConfiguredEightCharacterTmnCodeAndPublicHttpsOrigin() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                VnPayTestGatewaySettings.fromProperties(
                    new VnPayTestGatewayProperties(true, "short", "test-secret"), ORIGIN));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                VnPayTestGatewaySettings.fromProperties(
                    new VnPayTestGatewayProperties(true, "ABCD1234", "test-secret"),
                    "http://localhost:8088"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                VnPayTestGatewaySettings.fromProperties(
                    new VnPayTestGatewayProperties(true, "ABCD1234", "test-secret"),
                    ORIGIN + "/nested"));
  }
}
