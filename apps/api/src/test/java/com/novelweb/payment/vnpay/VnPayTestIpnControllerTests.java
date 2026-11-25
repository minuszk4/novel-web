package com.novelweb.payment.vnpay;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VnPayTestIpnControllerTests {

  @Mock private VnPayTestIpnService ipnService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    mockMvc = MockMvcBuilders.standaloneSetup(new VnPayTestIpnController(ipnService)).build();
  }

  @Test
  void acknowledgesGetIpnUsingTheVnPayJsonContract() throws Exception {
    when(ipnService.process(anyMap()))
        .thenReturn(new VnPayTestIpnService.Acknowledgement("00", "Confirm Success"));

    mockMvc
        .perform(
            get(VnPayTestIpnController.PATH)
                .param("vnp_TmnCode", "ABCD1234")
                .param("vnp_SecureHash", "a".repeat(128)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.RspCode").value("00"))
        .andExpect(jsonPath("$.Message").value("Confirm Success"));

    verify(ipnService).process(anyMap());
  }

  @Test
  void rejectsAmbiguousDuplicateProviderParametersWithoutCallingSettlement() throws Exception {
    mockMvc
        .perform(
            get(VnPayTestIpnController.PATH)
                .param("vnp_TxnRef", "order-one", "order-two")
                .param("vnp_SecureHash", "a".repeat(128)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.RspCode").value("95"));

    verify(ipnService, never()).process(anyMap());
  }

  @Test
  void acceptsQueryCallbacksAndReturnsNoStoreHeaders() throws Exception {
    when(ipnService.process(anyMap()))
        .thenReturn(new VnPayTestIpnService.Acknowledgement("02", "Order already confirmed"));

    mockMvc
        .perform(
            get(VnPayTestIpnController.PATH)
                .param("vnp_TxnRef", "11111111111141118111111111111111")
                .param("vnp_SecureHash", "b".repeat(128)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.RspCode").value("02"))
        .andExpect(
            header ->
                org.assertj.core.api.Assertions.assertThat(
                        header.getResponse().getHeader("Cache-Control"))
                    .contains("no-store"));

    verify(ipnService).process(anyMap());
  }
}
