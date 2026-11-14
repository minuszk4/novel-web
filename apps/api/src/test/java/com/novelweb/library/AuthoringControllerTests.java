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

  private TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new SessionService.SessionPrincipal(actorId, Instant.MAX), null);
  }
}
