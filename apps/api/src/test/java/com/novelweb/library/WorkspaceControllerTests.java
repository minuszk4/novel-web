package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
class WorkspaceControllerTests {

  @Mock private WorkspaceService workspaceService;
  @Mock private PolicyService policyService;

  private MockMvc mockMvc;
  private UUID actorId;

  @BeforeEach
  void setUp() {
    actorId = UUID.randomUUID();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new WorkspaceController(workspaceService, policyService))
            .build();
  }

  @Test
  void anonymousAndPolicyBlockedRequestsNeverReachWorkspaceService() throws Exception {
    var anonymous = mockMvc.perform(get("/api/v1/me/workspaces")).andReturn();
    assertEquals(401, anonymous.getResponse().getStatus());
    assertTrue(anonymous.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    var blocked =
        mockMvc.perform(get("/api/v1/me/workspaces").principal(authentication())).andReturn();
    assertEquals(403, blocked.getResponse().getStatus());
    assertTrue(blocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
    verifyNoInteractions(workspaceService);
  }

  @Test
  void workspaceListIsActorScopedSafeAndNoStore() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workspaceId = UUID.randomUUID();
    UUID creatorId = UUID.randomUUID();
    WorkspaceRepository.WorkspaceRecord workspace =
        new WorkspaceRepository.WorkspaceRecord(
            workspaceId, "Shared Room", creatorId, 4, Instant.EPOCH);
    when(workspaceService.listWorkspaces(actorId))
        .thenReturn(
            List.of(
                new WorkspaceRepository.WorkspaceMembershipRecord(
                    workspace, WorkspaceRepository.Role.EDITOR, Instant.EPOCH)));

    var response =
        mockMvc
            .perform(get("/api/v1/me/workspaces").principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertEquals("no-cache", response.getHeader("Pragma"));
    String body = response.getContentAsString();
    assertTrue(body.contains(workspaceId.toString()));
    assertTrue(body.contains("editor"));
    assertFalse(body.contains("createdByUserId"));
    assertFalse(body.contains(creatorId.toString()));
    verify(workspaceService).listWorkspaces(actorId);
  }

  @Test
  void createUsesCallerChosenIdAndRejectsUnknownFields() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workspaceId = UUID.randomUUID();
    var invalid =
        mockMvc
            .perform(
                put("/api/v1/me/workspaces/{workspaceId}", workspaceId)
                    .principal(authentication())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Reading\",\"createdByUserId\":\"forbidden\"}"))
            .andReturn()
            .getResponse();
    assertEquals(400, invalid.getStatus());

    WorkspaceRepository.WorkspaceRecord workspace =
        new WorkspaceRepository.WorkspaceRecord(workspaceId, "Reading", actorId, 1, Instant.EPOCH);
    when(workspaceService.createWorkspace(actorId, workspaceId, "Reading")).thenReturn(workspace);
    var created =
        mockMvc
            .perform(
                put("/api/v1/me/workspaces/{workspaceId}", workspaceId)
                    .principal(authentication())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Reading\"}"))
            .andReturn()
            .getResponse();
    assertEquals(200, created.getStatus());
    assertEquals("\"workspace-" + workspaceId + "-1\"", created.getHeader("ETag"));
    assertTrue(created.getContentAsString().contains("Reading"));
    assertFalse(created.getContentAsString().contains("createdByUserId"));
    verify(workspaceService).createWorkspace(actorId, workspaceId, "Reading");
  }

  @Test
  void memberMutationUsesSessionActorAndCannotAssignOwnerRole() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workspaceId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    WorkspaceRepository.MemberRecord member =
        new WorkspaceRepository.MemberRecord(
            workspaceId,
            memberId,
            WorkspaceRepository.Role.READER,
            WorkspaceRepository.MemberStatus.ACTIVE,
            Instant.EPOCH,
            null);
    when(workspaceService.putMember(
            actorId, workspaceId, memberId, WorkspaceRepository.Role.READER))
        .thenReturn(member);

    var saved =
        mockMvc
            .perform(
                put(
                        "/api/v1/workspaces/{workspaceId}/members/{memberUserId}",
                        workspaceId,
                        memberId)
                    .principal(authentication())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"role\":\"reader\"}"))
            .andReturn()
            .getResponse();
    assertEquals(200, saved.getStatus());
    assertTrue(saved.getContentAsString().contains(memberId.toString()));
    assertTrue(saved.getContentAsString().contains("reader"));

    var ownerRole =
        mockMvc
            .perform(
                put(
                        "/api/v1/workspaces/{workspaceId}/members/{memberUserId}",
                        workspaceId,
                        memberId)
                    .principal(authentication())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"role\":\"owner\"}"))
            .andReturn()
            .getResponse();
    assertEquals(400, ownerRole.getStatus());
    verify(workspaceService)
        .putMember(actorId, workspaceId, memberId, WorkspaceRepository.Role.READER);
  }

  @Test
  void accessFailuresAreMaskedAndOwnerTransferIsExplicit() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workspaceId = UUID.randomUUID();
    when(workspaceService.findWorkspace(actorId, workspaceId))
        .thenThrow(new WorkspaceRepository.WorkspaceAccessDeniedException());

    var hidden =
        mockMvc
            .perform(
                get("/api/v1/workspaces/{workspaceId}", workspaceId).principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(404, hidden.getStatus());
    assertTrue(hidden.getContentAsString().contains("WORKSPACE_NOT_FOUND"));

    UUID newOwner = UUID.randomUUID();
    WorkspaceRepository.WorkspaceRecord transferred =
        new WorkspaceRepository.WorkspaceRecord(
            workspaceId, "Shared Room", newOwner, 5, Instant.EPOCH);
    when(workspaceService.transferOwnership(actorId, workspaceId, newOwner))
        .thenReturn(transferred);
    var response =
        mockMvc
            .perform(
                put("/api/v1/workspaces/{workspaceId}/owner", workspaceId)
                    .principal(authentication())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"newOwnerUserId\":\"" + newOwner + "\"}"))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    assertEquals("\"workspace-" + workspaceId + "-5\"", response.getHeader("ETag"));
    verify(workspaceService).transferOwnership(actorId, workspaceId, newOwner);
  }

  @Test
  void deleteMemberReturnsSafeNoStoreResult() throws Exception {
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    UUID workspaceId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    when(workspaceService.revokeMember(actorId, workspaceId, memberId))
        .thenReturn(
            new WorkspaceRepository.MemberRecord(
                workspaceId,
                memberId,
                WorkspaceRepository.Role.READER,
                WorkspaceRepository.MemberStatus.REVOKED,
                Instant.EPOCH,
                Instant.EPOCH));
    var response =
        mockMvc
            .perform(
                delete(
                        "/api/v1/workspaces/{workspaceId}/members/{memberUserId}",
                        workspaceId,
                        memberId)
                    .principal(authentication()))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Cache-Control").contains("no-store"));
    assertTrue(response.getContentAsString().contains("revoked"));
    verify(workspaceService).revokeMember(actorId, workspaceId, memberId);
  }

  private TestingAuthenticationToken authentication() {
    return new TestingAuthenticationToken(
        new SessionService.SessionPrincipal(actorId, Instant.MAX), "authenticated");
  }
}
