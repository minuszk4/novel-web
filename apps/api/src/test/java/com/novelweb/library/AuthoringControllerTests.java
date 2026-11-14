package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import java.time.Instant;
import java.util.List;
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

  @Mock private AuthoringCursorCodec cursorCodec;

  private MockMvc mockMvc;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    actorId = UUID.randomUUID();
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AuthoringController(libraryService, policyService, cursorCodec))
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
  void manuscriptListReturnsSafeOwnerMetadataAndSignedNextCursor() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID libraryId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-11-15T12:34:56Z");
    when(cursorCodec.decodeWorkList("current-cursor", actorId))
        .thenReturn(new AuthoringCursorCodec.WorkListPosition(createdAt, workId));
    when(libraryService.listPrivateAuthoredWorks(actorId, createdAt, workId, 1))
        .thenReturn(
            new LibraryRepository.AuthoredWorkPage(
                List.of(
                    new LibraryRepository.WorkRecord(
                        workId,
                        libraryId,
                        actorId,
                        "My Novel",
                        "Pen Name",
                        "vi",
                        "private",
                        "active",
                        new byte[32],
                        3,
                        createdAt)),
                true,
                createdAt,
                workId));
    when(cursorCodec.encodeWorkList(actorId, createdAt, workId)).thenReturn("next-cursor");

    var response =
        mockMvc
            .perform(
                get("/api/v1/authoring/manuscripts")
                    .principal(authentication())
                    .param("cursor", "current-cursor")
                    .param("limit", "1"))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    String body = response.getContentAsString();
    assertTrue(body.contains(workId.toString()));
    assertTrue(body.contains("\"publicationState\":\"draft\""));
    assertTrue(body.contains("\"chapterCount\":3"));
    assertTrue(body.contains("\"nextCursor\":\"next-cursor\""));
    assertTrue(!body.contains(actorId.toString()));
    assertTrue(!body.contains("sourceHash"));
    assertTrue(!body.contains("normalizedContent"));
    verify(libraryService).listPrivateAuthoredWorks(actorId, createdAt, workId, 1);
  }

  @Test
  void manuscriptListRequiresSessionAndBoundsPageSize() throws Exception {
    var anonymous = mockMvc.perform(get("/api/v1/authoring/manuscripts")).andReturn().getResponse();
    assertEquals(401, anonymous.getStatus());
    verifyNoInteractions(libraryService, policyService, cursorCodec);

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    var oversized =
        mockMvc
            .perform(
                get("/api/v1/authoring/manuscripts")
                    .principal(authentication())
                    .param("limit", "101"))
            .andReturn()
            .getResponse();
    assertEquals(400, oversized.getStatus());
    assertTrue(oversized.getContentAsString().contains("INVALID_AUTHORING_QUERY"));
    verifyNoInteractions(libraryService);
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

  @Test
  void chapterOutlineListReturnsBoundedSafeMetadataAndSignedNextCursor() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    when(cursorCodec.decode("current-cursor", actorId, workId)).thenReturn(1);
    when(libraryService.listPrivateAuthoredChapters(actorId, workId, 1, 1))
        .thenReturn(
            new LibraryRepository.ChapterOutlinePage(
                List.of(
                    new LibraryRepository.ChapterRecord(
                        chapterId,
                        workId,
                        null,
                        2,
                        "Second Chapter",
                        "vi",
                        null,
                        "draft",
                        Instant.EPOCH)),
                true,
                2));
    when(cursorCodec.encode(actorId, workId, 2)).thenReturn("next-cursor");

    var response =
        mockMvc
            .perform(
                get("/api/v1/authoring/manuscripts/{workId}/chapters", workId)
                    .principal(authentication())
                    .param("cursor", "current-cursor")
                    .param("limit", "1"))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    String body = response.getContentAsString();
    assertTrue(body.contains(chapterId.toString()));
    assertTrue(body.contains("\"ordinal\":2"));
    assertTrue(body.contains("\"status\":\"draft\""));
    assertTrue(body.contains("\"contentAvailable\":false"));
    assertTrue(body.contains("\"nextCursor\":\"next-cursor\""));
    assertTrue(!body.contains("sourceRevisionId"));
    assertTrue(!body.contains("ownerUserId"));
    verify(libraryService).listPrivateAuthoredChapters(actorId, workId, 1, 1);
  }

  @Test
  void chapterOutlineListRequiresAnAuthenticatedSession() throws Exception {
    UUID workId = UUID.randomUUID();

    var response =
        mockMvc
            .perform(get("/api/v1/authoring/manuscripts/{workId}/chapters", workId))
            .andReturn()
            .getResponse();

    assertEquals(401, response.getStatus());
    assertTrue(response.getContentAsString().contains("UNAUTHENTICATED"));
    verifyNoInteractions(libraryService, policyService, cursorCodec);
  }

  @Test
  void chapterOutlineRejectsCursorForAnotherScope() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workId = UUID.randomUUID();
    when(cursorCodec.decode("foreign-cursor", actorId, workId))
        .thenThrow(new AuthoringCursorCodec.InvalidCursorException());

    var response =
        mockMvc
            .perform(
                get("/api/v1/authoring/manuscripts/{workId}/chapters", workId)
                    .principal(authentication())
                    .param("cursor", "foreign-cursor"))
            .andReturn()
            .getResponse();

    assertEquals(400, response.getStatus());
    assertTrue(response.getContentAsString().contains("INVALID_AUTHORING_CURSOR"));
    verifyNoInteractions(libraryService);
  }

  @Test
  void privateDraftReadReturnsVersionAndContentWithoutCaching() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    when(libraryService.getPrivateAuthoredDraft(actorId, workId, chapterId))
        .thenReturn(
            new LibraryRepository.PrivateAuthoredDraftRecord(
                chapterId,
                workId,
                "Chapter One",
                3,
                "plain_text",
                "The private body.",
                Instant.EPOCH,
                List.of(
                    new LibraryRepository.DraftSegmentRecord(
                        1, "paragraph", "The private body.", "chapter-one-p1"))));

    var response =
        mockMvc
            .perform(
                get(
                        "/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}/draft",
                        workId,
                        chapterId)
                    .principal(authentication()))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    assertEquals("\"draft-3\"", response.getHeader("ETag"));
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    String body = response.getContentAsString();
    assertTrue(body.contains("The private body."));
    assertTrue(body.contains("chapter-one-p1"));
    assertTrue(body.contains("\"status\":\"draft\""));
    assertTrue(!body.contains("contentHash"));
    assertTrue(!body.contains("ownerUserId"));
    verify(libraryService).getPrivateAuthoredDraft(actorId, workId, chapterId);
  }

  @Test
  void privateDraftReadRequiresSessionAndCurrentPolicy() throws Exception {
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    String path = "/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}/draft";

    var anonymous = mockMvc.perform(get(path, workId, chapterId)).andReturn().getResponse();
    assertEquals(401, anonymous.getStatus());

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    var blocked =
        mockMvc
            .perform(get(path, workId, chapterId).principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(403, blocked.getStatus());
    assertTrue(blocked.getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    verifyNoInteractions(libraryService);
  }

  @Test
  void draftRevisionRequiresIfMatchAndStrictBodyBeforeServiceAccess() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    String path = "/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}/revisions";
    String validBody =
        "{\"sourceFormat\":\"plain_text\",\"normalizedContent\":\"Body\"," + "\"segments\":[]}";

    var missingMatch =
        mockMvc
            .perform(
                post(path, workId, chapterId)
                    .principal(authentication())
                    .header("Idempotency-Key", "draft-revision-save-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validBody))
            .andReturn()
            .getResponse();
    assertEquals(428, missingMatch.getStatus());
    assertTrue(missingMatch.getContentAsString().contains("DRAFT_REVISION_PRECONDITION_REQUIRED"));

    var invalidMatch =
        mockMvc
            .perform(
                post(path, workId, chapterId)
                    .principal(authentication())
                    .header("If-Match", "W/\"draft-0\"")
                    .header("Idempotency-Key", "draft-revision-save-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validBody))
            .andReturn()
            .getResponse();
    assertEquals(400, invalidMatch.getStatus());
    assertTrue(invalidMatch.getContentAsString().contains("INVALID_DRAFT_REVISION_ETAG"));

    var unknownBodyField =
        mockMvc
            .perform(
                post(path, workId, chapterId)
                    .principal(authentication())
                    .header("If-Match", "\"draft-0\"")
                    .header("Idempotency-Key", "draft-revision-save-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sourceFormat\":\"plain_text\",\"normalizedContent\":\"Body\","
                            + "\"segments\":[],\"status\":\"ready\"}"))
            .andReturn()
            .getResponse();
    assertEquals(400, unknownBodyField.getStatus());
    assertTrue(unknownBodyField.getContentAsString().contains("INVALID_DRAFT_REVISION"));
    verifyNoInteractions(libraryService);
  }

  @Test
  void draftRevisionWriteReturnsImmutableRevisionAndNextEtag() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workId = UUID.randomUUID();
    UUID chapterId = UUID.randomUUID();
    UUID revisionId = UUID.randomUUID();
    when(libraryService.savePrivateAuthoredDraftRevision(
            actorId,
            workId,
            chapterId,
            0,
            "draft-revision-save-0001",
            "plain_text",
            "First paragraph.",
            List.of(
                new LibraryService.NewSegment(
                    1, "paragraph", "First paragraph.", "chapter-one-p1"))))
        .thenReturn(
            new LibraryRepository.DraftRevisionWrite(
                new LibraryRepository.RevisionRecord(
                    revisionId, chapterId, 1, "plain_text", new byte[32], Instant.EPOCH),
                false));

    var response =
        mockMvc
            .perform(
                post(
                        "/api/v1/authoring/manuscripts/{workId}/chapters/{chapterId}/revisions",
                        workId,
                        chapterId)
                    .principal(authentication())
                    .header("If-Match", "\"draft-0\"")
                    .header("Idempotency-Key", "draft-revision-save-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sourceFormat\":\"plain_text\",\"normalizedContent\":\"First paragraph.\","
                            + "\"segments\":[{\"ordinal\":1,\"type\":\"paragraph\","
                            + "\"sourceText\":\"First paragraph.\","
                            + "\"stableAnchor\":\"chapter-one-p1\"}]}"))
            .andReturn()
            .getResponse();

    assertEquals(201, response.getStatus());
    assertEquals("\"draft-1\"", response.getHeader("ETag"));
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    String body = response.getContentAsString();
    assertTrue(body.contains(revisionId.toString()));
    assertTrue(body.contains("\"revisionNo\":1"));
    assertTrue(!body.contains("normalizedContent"));
    verify(libraryService)
        .savePrivateAuthoredDraftRevision(
            actorId,
            workId,
            chapterId,
            0,
            "draft-revision-save-0001",
            "plain_text",
            "First paragraph.",
            List.of(
                new LibraryService.NewSegment(
                    1, "paragraph", "First paragraph.", "chapter-one-p1")));
  }

  private TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new SessionService.SessionPrincipal(actorId, Instant.MAX), null);
  }
}
