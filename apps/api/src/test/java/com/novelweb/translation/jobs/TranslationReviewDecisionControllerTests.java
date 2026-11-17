package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelweb.identity.session.SessionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class TranslationReviewDecisionControllerTests {

  private final UUID reviewerId = UUID.randomUUID();
  private final UUID submissionId = UUID.randomUUID();
  private final String digest = "a".repeat(64);
  private TranslationReviewDecisionService decisionService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    decisionService = mock(TranslationReviewDecisionService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new TranslationReviewDecisionController(decisionService, new ObjectMapper()))
            .build();
  }

  @Test
  void createdAndReplayedDecisionsReturnNoStoreReceiptsAndSnapshotEtag() throws Exception {
    TranslationReviewDecisionService.DecisionCommand command =
        new TranslationReviewDecisionService.DecisionCommand("ACCEPT", "ACCURATE");
    TranslationReviewDecisionService.PublicDecisionReceipt createdReceipt =
        new TranslationReviewDecisionService.PublicDecisionReceipt(
            UUID.randomUUID(), submissionId, "ACCEPT", "ACCURATE", digest, Instant.now(), false);
    when(decisionService.decide(
            reviewerId, submissionId, etag(), "translation-review-decision-controller-01", command))
        .thenReturn(
            new TranslationReviewDecisionService.DecisionResult(
                TranslationReviewDecisionService.DecisionOutcome.CREATED,
                createdReceipt,
                null,
                null));
    TranslationReviewDecisionService.PublicDecisionReceipt replayReceipt =
        new TranslationReviewDecisionService.PublicDecisionReceipt(
            createdReceipt.decisionId(),
            submissionId,
            "ACCEPT",
            "ACCURATE",
            digest,
            createdReceipt.decidedAt(),
            true);
    when(decisionService.decide(
            reviewerId, submissionId, etag(), "translation-review-decision-controller-02", command))
        .thenReturn(
            new TranslationReviewDecisionService.DecisionResult(
                TranslationReviewDecisionService.DecisionOutcome.REPLAYED,
                replayReceipt,
                null,
                null));

    MvcResult created =
        mockMvc
            .perform(
                request(
                    "{\"actionCode\":\"ACCEPT\",\"reasonCode\":\"ACCURATE\"}",
                    "translation-review-decision-controller-01"))
            .andExpect(status().isCreated())
            .andReturn();
    MvcResult replay =
        mockMvc
            .perform(
                request(
                    "{\"actionCode\":\"ACCEPT\",\"reasonCode\":\"ACCURATE\"}",
                    "translation-review-decision-controller-02"))
            .andExpect(status().isOk())
            .andReturn();

    assertEquals(etag(), created.getResponse().getHeader(HttpHeaders.ETAG));
    assertEquals(etag(), replay.getResponse().getHeader(HttpHeaders.ETAG));
    assertTrue(created.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
    assertTrue(replay.getResponse().getContentAsString().contains("\"replayed\":true"));
    verify(decisionService)
        .decide(
            reviewerId, submissionId, etag(), "translation-review-decision-controller-01", command);
    verify(decisionService)
        .decide(
            reviewerId, submissionId, etag(), "translation-review-decision-controller-02", command);
  }

  @Test
  void rejectsUnknownFieldsAndUnapprovedReasonsBeforeCallingTheService() throws Exception {
    mockMvc
        .perform(
            request(
                "{\"actionCode\":\"REJECT\",\"reasonCode\":\"OTHER\",\"comment\":\"private\"}",
                "translation-review-decision-controller-03"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            request(
                "{\"actionCode\":\"REJECT\",\"reasonCode\":\"free-text\"}",
                "translation-review-decision-controller-04"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            request(
                "{\"actionCode\":\"ACCEPT\",\"reasonCode\":\"MEANING_ERROR\"}",
                "translation-review-decision-controller-05"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(decisionService);
  }

  @Test
  void rejectNeedsAReasonAndGuestCannotRecordADecision() throws Exception {
    mockMvc
        .perform(
            request("{\"actionCode\":\"REJECT\"}", "translation-review-decision-controller-06"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/review/translations/{submissionId}/decisions", submissionId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.IF_MATCH, etag())
                .header("Idempotency-Key", "translation-review-decision-controller-07")
                .content("{\"actionCode\":\"REJECT\",\"reasonCode\":\"OTHER\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(decisionService);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
      String body, String idempotencyKey) {
    return post("/api/v1/review/translations/{submissionId}/decisions", submissionId)
        .principal(authentication())
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.IF_MATCH, etag())
        .header("Idempotency-Key", idempotencyKey)
        .content(body);
  }

  private Authentication authentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        new SessionService.SessionPrincipal(reviewerId, Instant.now().plusSeconds(300)),
        null,
        List.of());
  }

  private String etag() {
    return "\"translation-review-" + digest + "\"";
  }
}
