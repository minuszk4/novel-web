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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranslationPublicationControllerTests {

  private final UUID ownerId = UUID.randomUUID();
  private final UUID submissionId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();
  private final String digest = "a".repeat(64);
  private TranslationPublicationService publicationService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    publicationService = mock(TranslationPublicationService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new TranslationPublicationController(publicationService))
            .build();
  }

  @Test
  void releaseReturnsNoStoreReceiptAndStrongSnapshotEtag() throws Exception {
    String key = "translation-publication-controller-01";
    Instant releasedAt = Instant.parse("2026-11-17T00:00:00Z");
    when(publicationService.publish(ownerId, submissionId, etag(), key))
        .thenReturn(
            new TranslationPublicationService.ReleaseResult(
                TranslationPublicationService.ReleaseOutcome.CREATED,
                new TranslationPublicationService.ReleaseReceipt(
                    versionId, 1, digest, releasedAt, false),
                null));

    MvcResult result =
        mockMvc
            .perform(
                releaseRequest(key)
                    .principal(authentication())
                    .header(HttpHeaders.IF_MATCH, etag()))
            .andExpect(status().isCreated())
            .andReturn();

    assertEquals(etag(), result.getResponse().getHeader(HttpHeaders.ETAG));
    assertTrue(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
    assertTrue(result.getResponse().getContentAsString().contains("\"versionNo\":1"));
    assertTrue(result.getResponse().getContentAsString().contains(versionId.toString()));
    verify(publicationService).publish(ownerId, submissionId, etag(), key);
  }

  @Test
  void releaseRequiresStrongPreconditionAndRejectsClientSuppliedTranslationText() throws Exception {
    mockMvc
        .perform(
            releaseRequest("translation-publication-controller-02").principal(authentication()))
        .andExpect(status().is(428));
    mockMvc
        .perform(
            releaseRequest("translation-publication-controller-03")
                .principal(authentication())
                .header(HttpHeaders.IF_MATCH, "W/" + etag()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            releaseRequest("translation-publication-controller-04")
                .principal(authentication())
                .header(HttpHeaders.IF_MATCH, etag())
                .contentType("application/json")
                .content("{\"translatedText\":\"must not be accepted\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            releaseRequest("translation-publication-controller-05")
                .header(HttpHeaders.IF_MATCH, etag()))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(publicationService);
  }

  @Test
  void staleSnapshotReturnsCurrentEtagWithoutCaching() throws Exception {
    String key = "translation-publication-controller-06";
    String currentEtag = "\"translation-review-" + "b".repeat(64) + "\"";
    when(publicationService.publish(ownerId, submissionId, etag(), key))
        .thenReturn(
            new TranslationPublicationService.ReleaseResult(
                TranslationPublicationService.ReleaseOutcome.STALE_ETAG, null, currentEtag));

    MvcResult result =
        mockMvc
            .perform(
                releaseRequest(key)
                    .principal(authentication())
                    .header(HttpHeaders.IF_MATCH, etag()))
            .andExpect(status().isPreconditionFailed())
            .andReturn();

    assertEquals(currentEtag, result.getResponse().getHeader(HttpHeaders.ETAG));
    assertTrue(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
    assertTrue(
        result.getResponse().getContentAsString().contains("TRANSLATION_RELEASE_SNAPSHOT_STALE"));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder releaseRequest(
      String key) {
    return post(
            "/api/v1/authoring/translations/review-submissions/{submissionId}/release",
            submissionId)
        .header("Idempotency-Key", key);
  }

  private Authentication authentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        new SessionService.SessionPrincipal(ownerId, Instant.now().plusSeconds(300)),
        null,
        List.of());
  }

  private String etag() {
    return "\"translation-review-" + digest + "\"";
  }
}
