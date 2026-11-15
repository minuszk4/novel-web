package com.novelweb.payment.credit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.payment.credit.ChapterUnlockService.Entitlement;
import com.novelweb.payment.credit.ChapterUnlockService.Outcome;
import com.novelweb.payment.credit.ChapterUnlockService.UnlockQuote;
import com.novelweb.payment.credit.ChapterUnlockService.UnlockResult;
import java.time.Instant;
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
class ChapterUnlockControllerTests {

  private static final UUID CHAPTER_ID = UUID.fromString("aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
  private static final UUID WORK_ID = UUID.fromString("bbbbbbb2-bbbb-4bbb-8bbb-bbbbbbbbbbb2");
  private static final UUID ACTOR_ID = UUID.fromString("ccccccc3-cccc-4ccc-8ccc-ccccccccccc3");
  private static final String IDEMPOTENCY_KEY = "chapter-unlock-test-001";

  @Mock private ChapterUnlockService unlockService;

  @Mock private PolicyService policyService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ChapterUnlockController(unlockService, policyService, true))
            .build();
  }

  @Test
  void authenticationAndCurrentPolicyAreRequiredBeforeQuoteOrUnlock() throws Exception {
    var anonymousQuote =
        mockMvc
            .perform(get("/api/v1/chapters/{chapterId}/unlock-quote", CHAPTER_ID))
            .andReturn()
            .getResponse();
    var anonymousUnlock =
        mockMvc
            .perform(
                post("/api/v1/chapters/{chapterId}/unlock", CHAPTER_ID)
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceVersion\":1}"))
            .andReturn()
            .getResponse();
    assertEquals(401, anonymousQuote.getStatus());
    assertEquals(401, anonymousUnlock.getStatus());
    verifyNoInteractions(policyService, unlockService);

    when(policyService.requiredAcceptance(ACTOR_ID))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    var blocked =
        mockMvc
            .perform(
                get("/api/v1/chapters/{chapterId}/unlock-quote", CHAPTER_ID)
                    .principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(403, blocked.getStatus());
    assertTrue(blocked.getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    verifyNoInteractions(unlockService);
  }

  @Test
  void quoteReturnsOnlyServerOwnedPriceAndNoStore() throws Exception {
    when(policyService.requiredAcceptance(ACTOR_ID))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    when(unlockService.quote(ACTOR_ID, CHAPTER_ID))
        .thenReturn(
            Optional.of(
                new UnlockQuote(CHAPTER_ID, WORK_ID, "Chapter 1", "Public Story", 1, 10, false)));

    var response =
        mockMvc
            .perform(
                get("/api/v1/chapters/{chapterId}/unlock-quote", CHAPTER_ID)
                    .principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertTrue(response.getContentAsString().contains("\"priceCredits\":10"));
    assertTrue(response.getContentAsString().contains("\"priceVersion\":1"));
    assertTrue(response.getContentAsString().contains("\"accessAlreadyGranted\":false"));
  }

  @Test
  void successfulUnlockReturnsSafeEntitlementAndUsesOnlyTheReviewedPriceVersion() throws Exception {
    when(policyService.requiredAcceptance(ACTOR_ID))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    Entitlement entitlement =
        new Entitlement(
            UUID.randomUUID(), CHAPTER_ID, 1, 10, Instant.parse("2026-11-15T09:00:00Z"));
    when(unlockService.unlock(ACTOR_ID, CHAPTER_ID, 1, IDEMPOTENCY_KEY))
        .thenReturn(new UnlockResult(Outcome.CREATED, entitlement));

    var response =
        mockMvc
            .perform(
                post("/api/v1/chapters/{chapterId}/unlock", CHAPTER_ID)
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceVersion\":1,\"creditsSpent\":1}"))
            .andReturn()
            .getResponse();

    assertEquals(201, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertTrue(response.getContentAsString().contains("\"outcome\":\"CREATED\""));
    assertTrue(response.getContentAsString().contains("\"creditsSpent\":10"));
    assertTrue(!response.getContentAsString().contains("userId"));
    verify(unlockService).unlock(ACTOR_ID, CHAPTER_ID, 1, IDEMPOTENCY_KEY);
  }

  @Test
  void retryAndAlreadyOwnedResponsesDoNotClaimAnotherDebit() throws Exception {
    when(policyService.requiredAcceptance(ACTOR_ID))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    Entitlement entitlement =
        new Entitlement(
            UUID.randomUUID(), CHAPTER_ID, 1, 10, Instant.parse("2026-11-15T09:00:00Z"));
    when(unlockService.unlock(ACTOR_ID, CHAPTER_ID, 1, IDEMPOTENCY_KEY))
        .thenReturn(new UnlockResult(Outcome.REPLAYED, entitlement));
    var replay =
        mockMvc
            .perform(
                post("/api/v1/chapters/{chapterId}/unlock", CHAPTER_ID)
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceVersion\":1}"))
            .andReturn()
            .getResponse();
    assertEquals(200, replay.getStatus());
    assertEquals("true", replay.getHeader("Idempotency-Replayed"));

    when(unlockService.unlock(eq(ACTOR_ID), eq(CHAPTER_ID), eq(1), eq("chapter-unlock-test-002")))
        .thenReturn(new UnlockResult(Outcome.ALREADY_OWNED, entitlement));
    var owned =
        mockMvc
            .perform(
                post("/api/v1/chapters/{chapterId}/unlock", CHAPTER_ID)
                    .principal(authentication())
                    .header("Idempotency-Key", "chapter-unlock-test-002")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceVersion\":1}"))
            .andReturn()
            .getResponse();
    assertEquals(200, owned.getStatus());
    assertTrue(owned.getContentAsString().contains("\"outcome\":\"ALREADY_OWNED\""));
  }

  @Test
  void stalePriceInsufficientCreditsAndPrivateChapterHaveSafeProblems() throws Exception {
    when(policyService.requiredAcceptance(ACTOR_ID))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    when(unlockService.unlock(ACTOR_ID, CHAPTER_ID, 2, IDEMPOTENCY_KEY))
        .thenReturn(new UnlockResult(Outcome.PRICE_VERSION_STALE, null));
    var stale =
        mockMvc
            .perform(
                post("/api/v1/chapters/{chapterId}/unlock", CHAPTER_ID)
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceVersion\":2}"))
            .andReturn()
            .getResponse();
    assertEquals(409, stale.getStatus());
    assertTrue(stale.getContentAsString().contains("CHAPTER_PRICE_VERSION_STALE"));

    when(unlockService.unlock(ACTOR_ID, CHAPTER_ID, 1, IDEMPOTENCY_KEY))
        .thenReturn(new UnlockResult(Outcome.INSUFFICIENT_PAID_CREDITS, null));
    var insufficient =
        mockMvc
            .perform(
                post("/api/v1/chapters/{chapterId}/unlock", CHAPTER_ID)
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceVersion\":1}"))
            .andReturn()
            .getResponse();
    assertEquals(409, insufficient.getStatus());
    assertTrue(insufficient.getContentAsString().contains("INSUFFICIENT_CREDITS"));

    when(unlockService.quote(ACTOR_ID, CHAPTER_ID)).thenReturn(Optional.empty());
    var privateChapter =
        mockMvc
            .perform(
                get("/api/v1/chapters/{chapterId}/unlock-quote", CHAPTER_ID)
                    .principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(404, privateChapter.getStatus());
  }

  @Test
  void invalidUnlockRequestDoesNotCallTheService() throws Exception {
    when(policyService.requiredAcceptance(ACTOR_ID))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    var invalid =
        mockMvc
            .perform(
                post("/api/v1/chapters/{chapterId}/unlock", CHAPTER_ID)
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceVersion\":0}"))
            .andReturn()
            .getResponse();
    assertEquals(400, invalid.getStatus());
    assertTrue(invalid.getContentAsString().contains("INVALID_CHAPTER_UNLOCK_REQUEST"));
    verifyNoInteractions(unlockService);
  }

  @Test
  void disabledCommerceBlocksQuotesAndUnlocksBeforePolicyOrLedgerWork() throws Exception {
    MockMvc disabledCommerce =
        MockMvcBuilders.standaloneSetup(
                new ChapterUnlockController(unlockService, policyService, false))
            .build();
    var quote =
        disabledCommerce
            .perform(
                get("/api/v1/chapters/{chapterId}/unlock-quote", CHAPTER_ID)
                    .principal(authentication()))
            .andReturn()
            .getResponse();
    var unlock =
        disabledCommerce
            .perform(
                post("/api/v1/chapters/{chapterId}/unlock", CHAPTER_ID)
                    .principal(authentication())
                    .header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"priceVersion\":1}"))
            .andReturn()
            .getResponse();

    assertEquals(503, quote.getStatus());
    assertTrue(quote.getContentAsString().contains("COMMERCE_SANDBOX_DISABLED"));
    assertEquals(503, unlock.getStatus());
    assertTrue(unlock.getContentAsString().contains("COMMERCE_SANDBOX_DISABLED"));
    verifyNoInteractions(policyService, unlockService);
  }

  private TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new SessionService.SessionPrincipal(ACTOR_ID, Instant.MAX), null);
  }
}
