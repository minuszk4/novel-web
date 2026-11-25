package com.novelweb.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.novelweb.payment.credit.CreditLedgerService;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class VnPayTestIpnServiceTests {

  private static final String HASH_SECRET = "synthetic-vnpay-secret-only";

  @Test
  void rejectsInvalidSignatureBeforeOpeningDatabaseConnection() {
    DataSource dataSource = mock(DataSource.class);
    VnPayTestIpnService service =
        new VnPayTestIpnService(dataSource, new CreditLedgerService(), settings());
    Map<String, String> parameters = validNotification();
    parameters.put("vnp_SecureHash", "0".repeat(128));

    assertThat(service.process(parameters))
        .isEqualTo(new VnPayTestIpnService.Acknowledgement("97", "Invalid signature"));
    verifyNoInteractions(dataSource);
  }

  @Test
  void rejectsWrongMerchantAndMalformedAmountBeforeDatabaseConnection() {
    DataSource dataSource = mock(DataSource.class);
    VnPayTestIpnService service =
        new VnPayTestIpnService(dataSource, new CreditLedgerService(), settings());
    Map<String, String> parameters = validNotification();
    parameters.put("vnp_TmnCode", "ZZZZ9999");
    parameters.put(
        "vnp_SecureHash",
        VnPayTestSignatureCodec.sign(
            VnPayTestSignatureCodec.signedFields(parameters), HASH_SECRET));

    assertThat(service.process(parameters).rspCode()).isEqualTo("95");
    verifyNoInteractions(dataSource);
  }

  private static Map<String, String> validNotification() {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("vnp_TmnCode", "ABCD1234");
    parameters.put("vnp_CurrCode", "VND");
    parameters.put("vnp_TxnRef", "11111111111141118111111111111111");
    parameters.put("vnp_OrderInfo", "Nap credit 11111111111141118111111111111111");
    parameters.put("vnp_Amount", "100000");
    parameters.put("vnp_TransactionNo", "123456789");
    parameters.put("vnp_ResponseCode", "00");
    parameters.put("vnp_TransactionStatus", "00");
    parameters.put(
        "vnp_SecureHash",
        VnPayTestSignatureCodec.sign(
            VnPayTestSignatureCodec.signedFields(parameters), HASH_SECRET));
    return parameters;
  }

  private static VnPayTestGatewaySettings settings() {
    return new VnPayTestGatewaySettings(
        "ABCD1234",
        HASH_SECRET,
        VnPayTestGatewaySettings.SANDBOX_PAYMENT_URI,
        URI.create("https://novel.example" + VnPayTestGatewaySettings.IPN_PATH),
        URI.create("https://novel.example/app/payment/result"));
  }
}
