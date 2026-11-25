package com.novelweb.payment.vnpay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VnPayTestSignatureCodecTests {

  private static final String HASH_SECRET = "synthetic-vnpay-secret-only";

  @Test
  void canonicalizesSortedVnpFieldsUsingUtf8FormEncoding() {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("vnp_OrderInfo", "Nap credit: 100 + VND");
    fields.put("vnp_Amount", "100000");
    fields.put("vnp_SecureHash", "excluded-signature");
    fields.put("other", "excluded-field");

    assertThat(VnPayTestSignatureCodec.canonical(VnPayTestSignatureCodec.signedFields(fields)))
        .isEqualTo("vnp_Amount=100000&vnp_OrderInfo=Nap+credit%3A+100+%2B+VND");
  }

  @Test
  void verifiesOnlyTheSignedCanonicalFieldsAndRejectsMutation() {
    Map<String, String> fields =
        Map.of(
            "vnp_TmnCode", "ABCD1234",
            "vnp_TxnRef", "11111111111141118111111111111111",
            "vnp_Amount", "100000");
    String signature = VnPayTestSignatureCodec.sign(fields, HASH_SECRET);

    assertThat(signature).hasSize(128);
    assertThat(VnPayTestSignatureCodec.verify(fields, HASH_SECRET, signature)).isTrue();
    assertThat(VnPayTestSignatureCodec.verify(fields, HASH_SECRET, signature.toUpperCase()))
        .isTrue();
    assertThat(
            VnPayTestSignatureCodec.verify(
                Map.of("vnp_TmnCode", "ABCD1234", "vnp_TxnRef", "tampered", "vnp_Amount", "100000"),
                HASH_SECRET,
                signature))
        .isFalse();
    assertThat(VnPayTestSignatureCodec.verify(fields, HASH_SECRET, "bad-signature")).isFalse();
  }
}
