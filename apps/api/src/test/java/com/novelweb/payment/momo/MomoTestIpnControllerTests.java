package com.novelweb.payment.momo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.novelweb.payment.momo.MomoTestIpnTestSupport.SignedNotification;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MomoTestIpnControllerTests {

  @Mock private MomoTestIpnService ipnService;

  private final ObjectMapper objectMapper = JsonMapper.builder().build();
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new MomoTestIpnController(ipnService, objectMapper))
            .build();
  }

  @Test
  void validNotificationReturnsTheProviderRequiredEmptyNoStore204() throws Exception {
    SignedNotification signed = sampleNotification();
    when(ipnService.process(any(), anyString())).thenReturn(MomoTestIpnService.Result.SUCCEEDED);

    var response =
        mockMvc
            .perform(
                post(MomoTestIpnController.PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(MomoTestIpnTestSupport.payload(signed))))
            .andReturn()
            .getResponse();

    assertEquals(204, response.getStatus());
    assertEquals("", response.getContentAsString());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertEquals("no-cache", response.getHeader("Pragma"));
    verify(ipnService).process(signed.notification(), signed.signature());
  }

  @Test
  void invalidSignatureIsAcknowledgedButDatabaseFailureRemainsRetryable() throws Exception {
    SignedNotification signed = sampleNotification();
    when(ipnService.process(any(), anyString()))
        .thenReturn(MomoTestIpnService.Result.INVALID_SIGNATURE)
        .thenThrow(new IllegalStateException("database failure details are private"));

    var invalidSignature =
        mockMvc
            .perform(
                post(MomoTestIpnController.PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(MomoTestIpnTestSupport.payload(signed))))
            .andReturn()
            .getResponse();
    assertEquals(204, invalidSignature.getStatus());
    assertEquals("", invalidSignature.getContentAsString());
    assertTrue(invalidSignature.getHeader("Cache-Control").contains("no-store"));

    var unavailable =
        mockMvc
            .perform(
                post(MomoTestIpnController.PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(MomoTestIpnTestSupport.payload(signed))))
            .andReturn()
            .getResponse();
    assertEquals(503, unavailable.getStatus());
    assertEquals("", unavailable.getContentAsString());
    assertTrue(!unavailable.getContentAsString().contains("database failure details"));
  }

  @Test
  void signedNotificationWithInvalidBusinessFieldsIsAcknowledgedWithoutSettlement()
      throws Exception {
    SignedNotification signed = sampleNotification();
    when(ipnService.process(any(), anyString()))
        .thenReturn(MomoTestIpnService.Result.INVALID_NOTIFICATION);

    var response =
        mockMvc
            .perform(
                post(MomoTestIpnController.PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(MomoTestIpnTestSupport.payload(signed))))
            .andReturn()
            .getResponse();

    assertEquals(204, response.getStatus());
    assertEquals("", response.getContentAsString());
    verify(ipnService).process(signed.notification(), signed.signature());
  }

  @Test
  void rejectsUnexpectedMediaMalformedJsonMissingFieldsAndOversizedBodiesBeforeProcessing()
      throws Exception {
    var wrongMediaType =
        mockMvc
            .perform(
                post(MomoTestIpnController.PATH).contentType(MediaType.TEXT_PLAIN).content("{}"))
            .andReturn()
            .getResponse();
    assertEquals(415, wrongMediaType.getStatus());

    var malformed =
        mockMvc
            .perform(
                post(MomoTestIpnController.PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{"))
            .andReturn()
            .getResponse();
    assertEquals(400, malformed.getStatus());

    var missingFields =
        mockMvc
            .perform(
                post(MomoTestIpnController.PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andReturn()
            .getResponse();
    assertEquals(400, missingFields.getStatus());

    var oversized =
        mockMvc
            .perform(
                post(MomoTestIpnController.PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new byte[16_385]))
            .andReturn()
            .getResponse();
    assertEquals(413, oversized.getStatus());
    verifyNoInteractions(ipnService);
  }

  private static SignedNotification sampleNotification() {
    return MomoTestIpnTestSupport.sign(
        UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1_000, 0, 42);
  }
}
