package com.novelweb.library.reader;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelweb.identity.auth.PolicyService;
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

class TranslatedReaderControllerTests {

  private final UUID readerId = UUID.randomUUID();
  private final UUID chapterId = UUID.randomUUID();
  private TranslatedReaderService translatedReaderService;
  private PolicyService policyService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    translatedReaderService = mock(TranslatedReaderService.class);
    policyService = mock(PolicyService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new TranslatedReaderController(translatedReaderService, policyService))
            .build();
    when(policyService.requiredAcceptance(readerId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
  }

  @Test
  void languageAndContentResponsesAreNoStore() throws Exception {
    when(translatedReaderService.availableLanguages(readerId, chapterId)).thenReturn(List.of("en"));
    when(translatedReaderService.content(readerId, chapterId, "en", null, 250))
        .thenReturn(
            new TranslatedReaderService.TranslationContent(
                chapterId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                3,
                "vi",
                "en",
                List.of(
                    new TranslatedReaderService.TranslatedSegment(
                        "paragraph-1", "PARAGRAPH", "A published English translation.")),
                null));

    MvcResult languages =
        mockMvc
            .perform(
                get("/api/v1/chapters/{chapterId}/translations", chapterId)
                    .principal(authentication()))
            .andExpect(status().isOk())
            .andReturn();
    MvcResult content =
        mockMvc
            .perform(
                get("/api/v1/chapters/{chapterId}/translations/{language}/content", chapterId, "en")
                    .principal(authentication()))
            .andExpect(status().isOk())
            .andReturn();

    assertTrue(languages.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
    assertTrue(content.getResponse().getHeader(HttpHeaders.CACHE_CONTROL).contains("no-store"));
    assertTrue(
        content.getResponse().getContentAsString().contains("A published English translation."));
    verify(translatedReaderService).availableLanguages(readerId, chapterId);
    verify(translatedReaderService).content(readerId, chapterId, "en", null, 250);
  }

  @Test
  void policyAndAuthenticationAreRequiredBeforeReading() throws Exception {
    when(policyService.requiredAcceptance(readerId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    MvcResult policyBlocked =
        mockMvc
            .perform(
                get("/api/v1/chapters/{chapterId}/translations", chapterId)
                    .principal(authentication()))
            .andExpect(status().isForbidden())
            .andReturn();
    assertTrue(
        policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

    mockMvc
        .perform(get("/api/v1/chapters/{chapterId}/translations", chapterId))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(translatedReaderService);
  }

  private Authentication authentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        new SessionService.SessionPrincipal(readerId, Instant.now().plusSeconds(300)),
        null,
        List.of());
  }
}
