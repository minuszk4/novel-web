package com.novelweb.payment.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.payment.credit.CommerceHistoryRepository.ChapterPurchase;
import com.novelweb.payment.credit.CommerceHistoryRepository.PaymentOrder;
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
class CommerceHistoryControllerTests {

  @Mock private CommerceHistoryService historyService;

  @Mock private PolicyService policyService;

  private MockMvc mockMvc;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    actorId = UUID.randomUUID();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new CommerceHistoryController(historyService, policyService))
            .build();
  }

  @Test
  void bothListsRequireAnActiveSessionAndCurrentPolicy() throws Exception {
    var anonymous = mockMvc.perform(get("/api/v1/me/payment-orders")).andReturn().getResponse();
    assertEquals(401, anonymous.getStatus());
    verifyNoInteractions(policyService, historyService);

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    var blocked =
        mockMvc
            .perform(get("/api/v1/me/purchases").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(403, blocked.getStatus());
    assertTrue(blocked.getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    verifyNoInteractions(historyService);
  }

  @Test
  void paymentOrdersArePrivateNoStoreAndDoNotExposeCheckoutOrProviderInternals() throws Exception {
    UUID orderId = UUID.randomUUID();
    Instant created = Instant.parse("2026-11-15T12:00:00Z");
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    when(historyService.paymentOrders(actorId, "PENDING", null, 25))
        .thenReturn(
            Optional.of(
                new CommerceHistoryService.HistoryPage<>(
                    List.of(
                        new PaymentOrder(
                            orderId,
                            "momo-test-credits-100",
                            1,
                            "MOMO",
                            "TEST",
                            "VND",
                            BigInteger.valueOf(1_000),
                            BigInteger.valueOf(100),
                            BigInteger.ZERO,
                            null,
                            null,
                            BigInteger.ZERO,
                            BigInteger.ZERO,
                            "PENDING",
                            created,
                            created)),
                    "next-cursor")));

    var response =
        mockMvc
            .perform(
                get("/api/v1/me/payment-orders")
                    .param("state", "pending")
                    .principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertEquals("no-cache", response.getHeader("Pragma"));
    String body = response.getContentAsString();
    assertTrue(body.contains(orderId.toString()));
    assertTrue(body.contains("\"amountVnd\":1000"));
    assertTrue(body.contains("\"paidCreditsGranted\":0"));
    assertTrue(body.contains("\"status\":\"PENDING\""));
    assertTrue(body.contains("\"nextCursor\":\"next-cursor\""));
    assertTrue(!body.contains("checkoutUrl"));
    assertTrue(!body.contains("requestId"));
    assertTrue(!body.contains(actorId.toString()));
    verify(historyService).paymentOrders(actorId, "PENDING", null, 25);
  }

  @Test
  void chapterPurchasesAreDistinctFromOrdersAndExposeOnlySafeLabels() throws Exception {
    UUID entitlementId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    Instant grantedAt = Instant.parse("2026-11-15T12:00:00Z");
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    when(historyService.purchases(actorId, null, 25))
        .thenReturn(
            Optional.of(
                new CommerceHistoryService.HistoryPage<>(
                    List.of(
                        new ChapterPurchase(
                            entitlementId,
                            chapterId,
                            workId,
                            "Public Story",
                            "Chapter 1",
                            "GRANTED",
                            1,
                            BigInteger.TEN,
                            grantedAt,
                            "READABLE")),
                    null)));

    var response =
        mockMvc
            .perform(get("/api/v1/me/purchases").principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    String body = response.getContentAsString();
    assertTrue(body.contains(entitlementId.toString()));
    assertTrue(body.contains(chapterId.toString()));
    assertTrue(body.contains("\"creditsSpent\":10"));
    assertTrue(body.contains("\"currentReadAccess\":\"READABLE\""));
    assertTrue(!body.contains("creditBalance"));
    assertTrue(!body.contains("checkoutUrl"));
    verify(historyService).purchases(actorId, null, 25);
  }

  @Test
  void invalidStateLimitCursorAndUnavailableCursorHaveSafeErrors() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);

    var invalidState =
        mockMvc
            .perform(
                get("/api/v1/me/payment-orders")
                    .param("state", "SETTLED_BY_CLIENT")
                    .principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(400, invalidState.getStatus());
    assertTrue(invalidState.getContentAsString().contains("INVALID_PAYMENT_ORDER_STATE"));

    var invalidLimit =
        mockMvc
            .perform(get("/api/v1/me/purchases").param("limit", "51").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(400, invalidLimit.getStatus());
    assertTrue(invalidLimit.getContentAsString().contains("INVALID_PAGE_LIMIT"));

    when(historyService.purchases(actorId, "tampered", 25))
        .thenThrow(new CommerceHistoryCursorCodec.InvalidCursorException());
    var invalidCursor =
        mockMvc
            .perform(
                get("/api/v1/me/purchases").param("cursor", "tampered").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(400, invalidCursor.getStatus());
    assertTrue(invalidCursor.getContentAsString().contains("INVALID_CURSOR"));

    when(historyService.purchases(actorId, null, 25))
        .thenThrow(new CommerceHistoryCursorCodec.SigningUnavailableException());
    var unavailable =
        mockMvc
            .perform(get("/api/v1/me/purchases").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(503, unavailable.getStatus());
    assertTrue(unavailable.getContentAsString().contains("COMMERCE_HISTORY_CURSOR_UNAVAILABLE"));
  }

  private TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new SessionService.SessionPrincipal(actorId, Instant.MAX), null);
  }
}
