package com.novelweb.payment.momo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MomoTestQueryUrlPolicyTests {

  private static final String ENDPOINT = "https://test-payment.momo.vn/v2/gateway/api/query";

  @Test
  void allowsOnlyTheDocumentedHttpsTestQueryEndpoint() {
    assertThat(MomoTestQueryUrlPolicy.allows(ENDPOINT)).isTrue();
    assertThat(
            MomoTestQueryUrlPolicy.allows("HTTPS://TEST-PAYMENT.MOMO.VN:443/v2/gateway/api/query"))
        .isTrue();
  }

  @Test
  void rejectsNonTestHostsAndLookalikes() {
    assertThat(MomoTestQueryUrlPolicy.allows("https://payment.momo.vn/v2/gateway/api/query"))
        .isFalse();
    assertThat(
            MomoTestQueryUrlPolicy.allows(
                "https://test-payment.momo.vn.attacker.example/v2/gateway/api/query"))
        .isFalse();
    assertThat(MomoTestQueryUrlPolicy.allows("https://test-payment.momo.vn./v2/gateway/api/query"))
        .isFalse();
  }

  @Test
  void rejectsTransportAuthorityAndPathOverrides() {
    assertThat(MomoTestQueryUrlPolicy.allows("http://test-payment.momo.vn/v2/gateway/api/query"))
        .isFalse();
    assertThat(
            MomoTestQueryUrlPolicy.allows("https://test-payment.momo.vn:444/v2/gateway/api/query"))
        .isFalse();
    assertThat(
            MomoTestQueryUrlPolicy.allows(
                "https://test-payment.momo.vn@attacker.example/v2/gateway/api/query"))
        .isFalse();
    assertThat(MomoTestQueryUrlPolicy.allows("https://test-payment.momo.vn/v2/gateway/api/query/"))
        .isFalse();
    assertThat(MomoTestQueryUrlPolicy.allows("https://test-payment.momo.vn/v2/gateway/api/%71uery"))
        .isFalse();
  }

  @Test
  void rejectsQueryFragmentsInvalidAndOverlongUrls() {
    assertThat(MomoTestQueryUrlPolicy.allows(ENDPOINT + "?redirect=https://evil.example"))
        .isFalse();
    assertThat(MomoTestQueryUrlPolicy.allows(ENDPOINT + "#fragment")).isFalse();
    assertThat(MomoTestQueryUrlPolicy.allows(null)).isFalse();
    assertThat(MomoTestQueryUrlPolicy.allows(" ")).isFalse();
    assertThat(MomoTestQueryUrlPolicy.allows("not-a-url")).isFalse();
    assertThat(MomoTestQueryUrlPolicy.allows(ENDPOINT + "x".repeat(2_048))).isFalse();
  }
}
