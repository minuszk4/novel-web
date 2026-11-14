package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import java.time.Instant;
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
class AuthoringControllerTests {

  @Mock private LibraryService libraryService;

  @Mock private PolicyService policyService;

  private MockMvc mockMvc;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    actorId = UUID.randomUUID();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new AuthoringController(libraryService, policyService))
            .build();
  }

  @Test
  void anonymousAndPolicyBlockedRequestsDoNotCreateDrafts() throws Exception {
    var anonymous =
        mockMvc
            .perform(
                post("/api/v1/authoring/manuscripts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Untitled\",\"language\":\"vi\"}"))
            .andReturn();
    assertEquals(401, anonymous.getResponse().getStatus());
    assertTrue(anonymous.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    var blocked =
        mockMvc
            .perform(
                post("/api/v1/authoring/manuscripts")
                    .principal(authentication())
                    .header("Idempotency-Key", "create-manuscript-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Untitled\",\"language\":\"vi\"}"))
            .andReturn();
    assertEquals(403, blocked.getResponse().getStatus());
    assertTrue(blocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    verifyNoInteractions(libraryService);
  }

  @Test
  void createResponseIsPrivateDraftAndDoesNotExposeOwnerOrSourceHash() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    when(libraryService.createPrivateAuthoredWork(
            actorId, "My Novel", "Pen Name", "vi", "create-manuscript-0001"))
        .thenReturn(
            new LibraryRepository.WorkRecord(
                workId,
                libraryId,
                actorId,
                "My Novel",
                "Pen Name",
                "vi",
                "private",
                "active",
                null,
                0,
                Instant.EPOCH));

    var response =
        mockMvc
            .perform(
                post("/api/v1/authoring/manuscripts")
                    .principal(authentication())
                    .header("Idempotency-Key", "create-manuscript-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"title\":\"My Novel\",\"author\":\"Pen Name\",\"language\":\"vi\"}"))
            .andReturn()
            .getResponse();

    assertEquals(201, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    String body = response.getContentAsString();
    assertTrue(body.contains(workId.toString()));
    assertTrue(body.contains("\"publicationState\":\"draft\""));
    assertTrue(body.contains("\"visibility\":\"private\""));
    assertTrue(body.contains("My Novel"));
    assertTrue(body.contains("Pen Name"));
    assertTrue(!body.contains("ownerUserId"));
    assertTrue(!body.contains("sourceHash"));
    assertTrue(!body.contains(actorId.toString()));
    verify(libraryService)
        .createPrivateAuthoredWork(actorId, "My Novel", "Pen Name", "vi", "create-manuscript-0001");
  }

  @Test
  void missingIdempotencyKeyIsRejectedByTheServiceContract() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    var response =
        mockMvc
            .perform(
                post("/api/v1/authoring/manuscripts")
                    .principal(authentication())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"My Novel\",\"language\":\"vi\"}"))
            .andReturn()
            .getResponse();

    assertEquals(400, response.getStatus());
    assertTrue(response.getContentAsString().contains("INVALID_IDEMPOTENCY_KEY"));
    verifyNoInteractions(libraryService);
  }

  @Test
  void unknownRequestPropertiesAreRejectedBeforeServiceAccess() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    var response =
        mockMvc
            .perform(
                post("/api/v1/authoring/manuscripts")
                    .principal(authentication())
                    .header("Idempotency-Key", "create-manuscript-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"title\":\"My Novel\",\"language\":\"vi\",\"visibility\":\"public\"}"))
            .andReturn()
            .getResponse();

    assertEquals(400, response.getStatus());
    assertTrue(response.getContentAsString().contains("INVALID_MANUSCRIPT"));
    verifyNoInteractions(libraryService);
  }

  @Test
  void createChapterResponseContainsOnlyPrivateDraftMetadata() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    when(libraryService.createPrivateAuthoredChapter(
            actorId, workId, " Chapter One ", "authoring-chapter-create-0001"))
        .thenReturn(
            new LibraryRepository.ChapterRecord(
                chapterId,
                workId,
                null,
                1,
                "Chapter One",
                "zh-Hans",
                null,
                "draft",
                Instant.EPOCH));

    var response =
        mockMvc
            .perform(
                post("/api/v1/authoring/manuscripts/{workId}/chapters", workId)
                    .principal(authentication())
                    .header("Idempotency-Key", "authoring-chapter-create-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\" Chapter One \"}"))
            .andReturn()
            .getResponse();

    assertEquals(201, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    String body = response.getContentAsString();
    assertTrue(body.contains(chapterId.toString()));
    assertTrue(body.contains(workId.toString()));
    assertTrue(body.contains("\"ordinal\":1"));
    assertTrue(body.contains("\"language\":\"zh-Hans\""));
    assertTrue(body.contains("\"status\":\"draft\""));
    assertTrue(!body.contains("sourceRevisionId"));
    assertTrue(!body.contains("ownerUserId"));
    verify(libraryService)
        .createPrivateAuthoredChapter(
            actorId, workId, " Chapter One ", "authoring-chapter-create-0001");
  }

  @Test
  void createChapterRequiresSessionAndCurrentPolicy() throws Exception {
    UUID workId = UUID.randomUUID();
    var anonymous =
        mockMvc
            .perform(
                post("/api/v1/authoring/manuscripts/{workId}/chapters", workId)
                    .header("Idempotency-Key", "authoring-chapter-create-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Chapter One\"}"))
            .andReturn()
            .getResponse();
    assertEquals(401, anonymous.getStatus());
    assertTrue(anonymous.getContentAsString().contains("UNAUTHENTICATED"));

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    var policyBlocked =
        mockMvc
            .perform(
                post("/api/v1/authoring/manuscripts/{workId}/chapters", workId)
                    .principal(authentication())
                    .header("Idempotency-Key", "authoring-chapter-create-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Chapter One\"}"))
            .andReturn()
            .getResponse();
    assertEquals(403, policyBlocked.getStatus());
    assertTrue(policyBlocked.getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    verifyNoInteractions(libraryService);
  }

  @Test
  void invalidChapterBodyIsRejectedBeforeServiceAccess() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workId = UUID.randomUUID();
    var response =
        mockMvc
            .perform(
                post("/api/v1/authoring/manuscripts/{workId}/chapters", workId)
                    .principal(authentication())
                    .header("Idempotency-Key", "authoring-chapter-create-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Chapter One\",\"language\":\"vi\"}"))
            .andReturn()
            .getResponse();

    assertEquals(400, response.getStatus());
    assertTrue(response.getContentAsString().contains("INVALID_CHAPTER"));
    verifyNoInteractions(libraryService);
  }

  private TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new SessionService.SessionPrincipal(actorId, Instant.MAX), null);
  }
}
