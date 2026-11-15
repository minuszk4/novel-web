package com.novelweb.payment.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CreditHistoryControllerTests {

  @Mock private CreditHistoryService historyService;

  @Mock private PolicyService policyService;

  private MockMvc mockMvc;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    actorId = UUID.randomUUID();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new CreditHistoryController(historyService, policyService))
            .build();
  }

  @Test
  void anonymousRequestCannotReadCreditHistory() throws Exception {
    var response =
        mockMvc.perform(get("/api/v1/me/credits/transactions")).andReturn().getResponse();

    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("UNAUTHENTICATED"));
    verifyNoInteractions(policyService, historyService);
  }

  @Test
  void currentPolicyAcceptanceIsRequiredBeforeReadingCreditHistory() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);

    var response =
        mockMvc
            .perform(get("/api/v1/me/credits/transactions").principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(403, response.getStatus());
    assertTrue(response.getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    verifyNoInteractions(historyService);
  }

  @Test
  void historyPageIsPrivateAndContainsOnlySafeLedgerMetadata() throws Exception {
    UUID entryId = UUID.randomUUID();
    Instant occurredAt = Instant.parse("2026-11-15T12:00:00Z");
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    when(historyService.list(actorId, null, 25))
        .thenReturn(
            Optional.of(
                new CreditHistoryService.HistoryPage(
                    List.of(
                        new CreditHistoryRepository.CreditEntry(
                            entryId,
                            "TOP_UP",
                            "PAID",
                            BigInteger.valueOf(100),
                            null,
                            "PAYMENT_ORDER",
                            occurredAt)),
                    "next-cursor")));

    var response =
        mockMvc
            .perform(get("/api/v1/me/credits/transactions").principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertEquals("no-cache", response.getHeader("Pragma"));
    String body = response.getContentAsString();
    assertTrue(body.contains(entryId.toString()));
    assertTrue(body.contains("\"entryType\":\"TOP_UP\""));
    assertTrue(body.contains("\"bucket\":\"PAID\""));
    assertTrue(body.contains("\"amountDelta\":100"));
    assertTrue(body.contains("\"sourceType\":\"PAYMENT_ORDER\""));
    assertTrue(body.contains("\"nextCursor\":\"next-cursor\""));
    assertTrue(!body.contains(actorId.toString()));
    verify(historyService).list(actorId, null, 25);
  }

  @Test
  void invalidLimitCursorAndUnavailableHistoryHaveSafeErrors() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);

    var invalidLimit =
        mockMvc
            .perform(
                get("/api/v1/me/credits/transactions")
                    .param("limit", "51")
                    .principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(400, invalidLimit.getStatus());
    assertTrue(invalidLimit.getContentAsString().contains("INVALID_PAGE_LIMIT"));

    when(historyService.list(actorId, "tampered", 25))
        .thenThrow(new CreditHistoryCursorCodec.InvalidCursorException());
    var invalidCursor =
        mockMvc
            .perform(
                get("/api/v1/me/credits/transactions")
                    .param("cursor", "tampered")
                    .principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(400, invalidCursor.getStatus());
    assertTrue(invalidCursor.getContentAsString().contains("INVALID_CURSOR"));

    when(historyService.list(actorId, null, 25))
        .thenThrow(new CreditHistoryCursorCodec.SigningUnavailableException())
        .thenThrow(new IllegalStateException("database details must not be exposed"));
    var signingUnavailable =
        mockMvc
            .perform(get("/api/v1/me/credits/transactions").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(503, signingUnavailable.getStatus());
    assertTrue(
        signingUnavailable.getContentAsString().contains("CREDIT_HISTORY_CURSOR_UNAVAILABLE"));

    var unavailable =
        mockMvc
            .perform(get("/api/v1/me/credits/transactions").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(503, unavailable.getStatus());
    assertTrue(unavailable.getContentAsString().contains("CREDIT_HISTORY_UNAVAILABLE"));
    assertTrue(!unavailable.getContentAsString().contains("database details"));
  }

  private TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new SessionService.SessionPrincipal(actorId, Instant.MAX), null);
  }
}
