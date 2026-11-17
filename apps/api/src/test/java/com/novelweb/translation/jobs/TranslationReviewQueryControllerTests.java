package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelweb.identity.session.SessionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranslationReviewQueryControllerTests {

  private final UUID reviewerId = UUID.randomUUID();
  private TranslationReviewQueryService queryService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    queryService = mock(TranslationReviewQueryService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new TranslationReviewQueryController(queryService)).build();
  }

  @Test
  void queueReturnsNoStoreMetadataWithoutTranslationText() throws Exception {
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID submitterId = UUID.randomUUID();
    TranslationReviewQueryService.QueueItem item =
        new TranslationReviewQueryService.QueueItem(
            UUID.randomUUID(),
            workId,
            chapterId,
            "Synthetic private work",
            "Chapter 1",
            "vi",
            "en",
            12,
            submitterId,
            "Synthetic submitter",
            Instant.parse("2026-11-17T00:00:00Z"),
            true);
    when(queryService.queue(reviewerId, null, 20))
        .thenReturn(
            new TranslationReviewQueryService.QueueResult(
                TranslationReviewQueryService.QueueOutcome.AVAILABLE,
                new TranslationReviewQueryService.QueuePage(List.of(item), null, false)));

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/review/translations").principal(authentication()))
            .andExpect(status().isOk())
            .andReturn();

    assertTrue(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
    assertEquals("no-cache", result.getResponse().getHeader(HttpHeaders.PRAGMA));
    assertTrue(result.getResponse().getContentAsString().contains("Synthetic private work"));
    assertFalse(result.getResponse().getContentAsString().contains("submittedText"));
    assertFalse(result.getResponse().getContentAsString().contains("fakeResultText"));
    assertFalse(result.getResponse().getContentAsString().contains(workId.toString()));
    assertFalse(result.getResponse().getContentAsString().contains(chapterId.toString()));
    assertFalse(result.getResponse().getContentAsString().contains(submitterId.toString()));
  }

  @Test
  void queueMasksUnassignedReviewerAndRequiresAuthentication() throws Exception {
    when(queryService.queue(reviewerId, null, 20))
        .thenReturn(
            new TranslationReviewQueryService.QueueResult(
                TranslationReviewQueryService.QueueOutcome.REVIEWER_REQUIRED, null));

    MvcResult missingRole =
        mockMvc
            .perform(get("/api/v1/review/translations").principal(authentication()))
            .andExpect(status().isNotFound())
            .andReturn();
    assertTrue(
        missingRole.getResponse().getContentAsString().contains("TRANSLATION_REVIEW_UNAVAILABLE"));

    mockMvc.perform(get("/api/v1/review/translations")).andExpect(status().isUnauthorized());
  }

  @Test
  void detailReturnsTheFrozenSnapshotAndStrongEtagWithoutCaching() throws Exception {
    UUID submissionId = UUID.randomUUID();
    UUID submitterId = UUID.randomUUID();
    UUID sourceRevisionId = UUID.randomUUID();
    UUID sourceSegmentId = UUID.randomUUID();
    String digest = "a".repeat(64);
    TranslationReviewQueryService.TranslationReviewDetail detail =
        new TranslationReviewQueryService.TranslationReviewDetail(
            submissionId,
            "pending_review",
            "Private work",
            "Chapter 1",
            submitterId,
            "Writer",
            sourceRevisionId,
            "vi",
            "en",
            digest,
            1,
            Instant.parse("2026-11-17T00:00:00Z"),
            true,
            List.of(
                new TranslationReviewQueryService.ReviewSegment(
                    sourceSegmentId,
                    1,
                    2,
                    "00".repeat(32),
                    "Source segment.",
                    "Manually edited segment.")));
    String etag = "\"translation-review-" + digest + "\"";
    when(queryService.detail(reviewerId, submissionId))
        .thenReturn(
            new TranslationReviewQueryService.DetailResult(
                TranslationReviewQueryService.DetailOutcome.AVAILABLE, detail, etag));

    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/review/translations/{submissionId}", submissionId)
                    .principal(authentication()))
            .andExpect(status().isOk())
            .andReturn();

    assertEquals(etag, result.getResponse().getHeader(HttpHeaders.ETAG));
    assertTrue(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
    assertTrue(result.getResponse().getContentAsString().contains("Manually edited segment."));
    assertFalse(result.getResponse().getContentAsString().contains("fakeResultText"));
    assertFalse(result.getResponse().getContentAsString().contains(submitterId.toString()));
    assertFalse(result.getResponse().getContentAsString().contains(sourceRevisionId.toString()));
    assertFalse(result.getResponse().getContentAsString().contains(sourceSegmentId.toString()));
  }

  @Test
  void staleSourceReturnsNeutralConflictWithoutTheSnapshotBody() throws Exception {
    UUID submissionId = UUID.randomUUID();
    when(queryService.detail(reviewerId, submissionId))
        .thenReturn(
            new TranslationReviewQueryService.DetailResult(
                TranslationReviewQueryService.DetailOutcome.STALE_SOURCE, null, null));

    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/review/translations/{submissionId}", submissionId)
                    .principal(authentication()))
            .andExpect(status().isConflict())
            .andReturn();
    assertTrue(
        result.getResponse().getContentAsString().contains("TRANSLATION_REVIEW_SOURCE_STALE"));
    assertFalse(result.getResponse().getContentAsString().contains("translatedText"));
  }

  private Authentication authentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        new SessionService.SessionPrincipal(reviewerId, Instant.now().plusSeconds(300)),
        null,
        List.of());
  }
}
