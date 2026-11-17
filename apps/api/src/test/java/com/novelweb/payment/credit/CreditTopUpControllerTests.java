package com.novelweb.payment.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.payment.credit.CreditPackCatalog.CreditPackQuote;
import com.novelweb.payment.credit.CreditTopUpCheckoutService.CheckoutResult;
import com.novelweb.payment.credit.CreditTopUpOrderService.CheckoutOutcome;
import com.novelweb.payment.credit.CreditTopUpOrderService.OrderStatus;
import com.novelweb.payment.credit.CreditTopUpOrderService.Outcome;
import com.novelweb.payment.credit.CreditTopUpOrderService.Provider;
import com.novelweb.payment.credit.CreditTopUpOrderService.TopUpOrder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CreditTopUpControllerTests {

  private static final String PACK_ID = "momo-test-credits-100";
  private static final int PACK_VERSION = 1;
  private static final String IDEMPOTENCY_KEY = "credit-top-up-route-key-01";

  @Mock private CreditTopUpCheckoutService checkoutService;

  @Mock private CreditTopUpOrderService orderService;

  @Mock private PolicyService policyService;

  private final CreditPackCatalog catalog = CreditPackCatalog.momoTestFixture();
  private MockMvc mockMvc;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    actorId = UUID.randomUUID();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new CreditTopUpController(
                    catalog, checkoutService, orderService, policyService, true))
            .build();
  }

  @Test
  void anonymousAndPolicyBlockedAccountsCannotReadTheCatalogOrCreateAnOrder() throws Exception {
    var anonymousCatalog = mockMvc.perform(get("/api/v1/credit-packs")).andReturn().getResponse();
    var anonymousCreate =
        mockMvc
            .perform(
                post("/api/v1/momo/payment-orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .content(requestBody()))
            .andReturn()
            .getResponse();

    assertEquals(401, anonymousCatalog.getStatus());
    assertEquals(401, anonymousCreate.getStatus());
    verifyNoInteractions(policyService, checkoutService, orderService);

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    var blocked =
        mockMvc
            .perform(get("/api/v1/credit-packs").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(403, blocked.getStatus());
    assertTrue(blocked.getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    verifyNoInteractions(checkoutService, orderService);
  }

  @Test
  void catalogReturnsOnlyTheServerOwnedTestQuoteAndNoStoreHeaders() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);

    var response =
        mockMvc
            .perform(get("/api/v1/credit-packs").principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertEquals("no-cache", response.getHeader("Pragma"));
    assertTrue(response.getContentAsString().contains("\"environment\":\"TEST\""));
    assertTrue(response.getContentAsString().contains("\"amountVnd\":1000"));
    assertTrue(response.getContentAsString().contains("\"paidCredits\":100"));
    assertTrue(response.getContentAsString().contains("\"bonusCredits\":0"));
  }

  @Test
  void createResponseIncludesLocationAndOnlyTheValidatedCheckoutUrl() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    TopUpOrder order = sampleOrder(actorId);
    String checkoutUrl = "https://test-payment.momo.vn/v2/gateway/pay?token=synthetic-token";
    when(checkoutService.createOrReplay(
            eq(actorId),
            eq(IDEMPOTENCY_KEY),
            any(CreditPackCatalog.class),
            eq(PACK_ID),
            eq(PACK_VERSION)))
        .thenReturn(new CheckoutResult(Outcome.CREATED, CheckoutOutcome.READY, order, checkoutUrl));

    var response =
        mockMvc
            .perform(
                post("/api/v1/momo/payment-orders")
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody()))
            .andReturn()
            .getResponse();

    assertEquals(201, response.getStatus());
    assertEquals("/api/v1/payment-orders/" + order.id(), response.getHeader("Location"));
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertTrue(response.getContentAsString().contains(checkoutUrl));
    assertTrue(response.getContentAsString().contains("\"amountVnd\":1000"));
    assertTrue(!response.getContentAsString().contains("\"userId\""));
    assertTrue(!response.getContentAsString().contains(order.userId().toString()));
    assertTrue(!response.getContentAsString().contains(order.requestId().toString()));
    verify(checkoutService)
        .createOrReplay(actorId, IDEMPOTENCY_KEY, catalog, PACK_ID, PACK_VERSION);
  }

  @Test
  void replayAndStalePackVersionHaveExplicitSafeResponses() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    TopUpOrder order = sampleOrder(actorId);
    when(checkoutService.createOrReplay(
            eq(actorId),
            eq(IDEMPOTENCY_KEY),
            any(CreditPackCatalog.class),
            eq(PACK_ID),
            eq(PACK_VERSION)))
        .thenReturn(
            new CheckoutResult(
                Outcome.REPLAYED, CheckoutOutcome.PROVIDER_RESULT_UNKNOWN, order, null));
    var replay =
        mockMvc
            .perform(
                post("/api/v1/momo/payment-orders")
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody()))
            .andReturn()
            .getResponse();
    assertEquals(200, replay.getStatus());
    assertEquals("true", replay.getHeader("Idempotency-Replayed"));
    assertTrue(replay.getContentAsString().contains("PROVIDER_RESULT_UNKNOWN"));
    assertTrue(!replay.getContentAsString().contains("checkoutUrl\":\"https://"));

    when(checkoutService.createOrReplay(
            eq(actorId), eq(IDEMPOTENCY_KEY), any(CreditPackCatalog.class), eq(PACK_ID), eq(2)))
        .thenReturn(new CheckoutResult(Outcome.PACK_VERSION_STALE, null, null, null));
    var stale =
        mockMvc
            .perform(
                post("/api/v1/momo/payment-orders")
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"packId\":\"" + PACK_ID + "\",\"packVersion\":2}"))
            .andReturn()
            .getResponse();
    assertEquals(409, stale.getStatus());
    assertTrue(stale.getContentAsString().contains("CREDIT_PACK_VERSION_STALE"));
  }

  @Test
  void statusResponseIsActorScopedAndDoesNotContainCheckoutOrProviderIdentifiers()
      throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    TopUpOrder order = sampleOrder(actorId);
    when(orderService.findForActor(actorId, order.id())).thenReturn(Optional.of(order));

    var response =
        mockMvc
            .perform(get("/api/v1/payment-orders/" + order.id()).principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertTrue(response.getContentAsString().contains("\"status\":\"PENDING\""));
    assertTrue(!response.getContentAsString().contains("checkoutUrl"));
    assertTrue(!response.getContentAsString().contains("requestId"));
    assertTrue(!response.getContentAsString().contains("userId"));
    verify(orderService).findForActor(actorId, order.id());

    when(orderService.findForActor(actorId, order.id())).thenReturn(Optional.empty());
    var foreignOrMissing =
        mockMvc
            .perform(get("/api/v1/payment-orders/" + order.id()).principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(404, foreignOrMissing.getStatus());
    assertTrue(foreignOrMissing.getContentAsString().contains("PAYMENT_ORDER_NOT_FOUND"));
  }

  @Test
  void invalidRequestDoesNotReachTheCheckoutService() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    var missingVersion =
        mockMvc
            .perform(
                post("/api/v1/momo/payment-orders")
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"packId\":\"" + PACK_ID + "\"}"))
            .andReturn()
            .getResponse();
    assertEquals(400, missingVersion.getStatus());
    assertTrue(missingVersion.getContentAsString().contains("INVALID_CREDIT_PACK_REQUEST"));
    verifyNoInteractions(checkoutService);
  }

  @Test
  void everyFailedCreationOutcomeMapsToItsSafeProblemResponse() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    record ExpectedProblem(Outcome outcome, int status, String code) {}
    List<ExpectedProblem> expectedProblems =
        List.of(
            new ExpectedProblem(Outcome.INVALID_IDEMPOTENCY_KEY, 400, "INVALID_IDEMPOTENCY_KEY"),
            new ExpectedProblem(Outcome.PACK_NOT_FOUND, 404, "CREDIT_PACK_NOT_FOUND"),
            new ExpectedProblem(Outcome.PACK_VERSION_STALE, 409, "CREDIT_PACK_VERSION_STALE"),
            new ExpectedProblem(Outcome.IDEMPOTENCY_KEY_REUSED, 409, "IDEMPOTENCY_KEY_REUSED"),
            new ExpectedProblem(Outcome.ACCOUNT_NOT_FOUND, 401, "UNAUTHENTICATED"),
            new ExpectedProblem(Outcome.ACCOUNT_INACTIVE, 401, "UNAUTHENTICATED"),
            new ExpectedProblem(Outcome.CREATED, 503, "PAYMENT_ORDER_UNAVAILABLE"),
            new ExpectedProblem(Outcome.REPLAYED, 503, "PAYMENT_ORDER_UNAVAILABLE"));

    for (ExpectedProblem expected : expectedProblems) {
      String requestKey = "failure-outcome-" + expected.outcome().name();
      when(checkoutService.createOrReplay(
              eq(actorId),
              eq(requestKey),
              any(CreditPackCatalog.class),
              eq(PACK_ID),
              eq(PACK_VERSION)))
          .thenReturn(new CheckoutResult(expected.outcome(), null, null, "sensitive-checkout-url"));

      var response =
          mockMvc
              .perform(
                  post("/api/v1/momo/payment-orders")
                      .principal(authentication())
                      .header("Idempotency-Key", requestKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(requestBody()))
              .andReturn()
              .getResponse();

      assertEquals(expected.status(), response.getStatus(), expected.outcome().name());
      assertTrue(
          response.getContentAsString().contains("\"code\":\"" + expected.code() + "\""),
          expected.outcome().name());
      assertTrue(!response.getContentAsString().contains("sensitive-checkout-url"));
      assertTrue(response.getHeader("Location") == null);
      assertTrue(response.getHeader("Idempotency-Replayed") == null);
    }
  }

  @Test
  void disabledCommerceBlocksCatalogAndNewOrdersButKeepsSavedOrderStatusReadable()
      throws Exception {
    MockMvc disabledShop =
        MockMvcBuilders.standaloneSetup(
                new CreditTopUpController(
                    catalog, checkoutService, orderService, policyService, false))
            .build();
    TopUpOrder order = sampleOrder(actorId);
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    when(orderService.findForActor(actorId, order.id())).thenReturn(Optional.of(order));

    var catalogResponse =
        disabledShop
            .perform(get("/api/v1/credit-packs").principal(authentication()))
            .andReturn()
            .getResponse();
    var createResponse =
        disabledShop
            .perform(
                post("/api/v1/momo/payment-orders")
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody()))
            .andReturn()
            .getResponse();
    var savedStatusResponse =
        disabledShop
            .perform(get("/api/v1/payment-orders/" + order.id()).principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(503, catalogResponse.getStatus());
    assertTrue(catalogResponse.getContentAsString().contains("COMMERCE_SANDBOX_DISABLED"));
    assertEquals(503, createResponse.getStatus());
    assertTrue(createResponse.getContentAsString().contains("COMMERCE_SANDBOX_DISABLED"));
    assertEquals(200, savedStatusResponse.getStatus());
    assertTrue(savedStatusResponse.getContentAsString().contains("\"status\":\"PENDING\""));
    verify(orderService).findForActor(actorId, order.id());
    verifyNoInteractions(checkoutService);
    verify(policyService).requiredAcceptance(actorId);
  }

  private TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new SessionService.SessionPrincipal(actorId, Instant.MAX), null);
  }

  private static String requestBody() {
    return "{\"packId\":\"" + PACK_ID + "\",\"packVersion\":" + PACK_VERSION + "}";
  }

  private static TopUpOrder sampleOrder(UUID userId) {
    CreditPackQuote quote = catalogQuote();
    return new TopUpOrder(
        UUID.randomUUID(),
        userId,
        UUID.randomUUID(),
        Provider.MOMO,
        quote,
        OrderStatus.PENDING,
        Instant.parse("2026-11-15T10:00:00Z"));
  }

  private static CreditPackQuote catalogQuote() {
    return CreditPackCatalog.momoTestFixture().quote(PACK_ID).orElseThrow();
  }
}
