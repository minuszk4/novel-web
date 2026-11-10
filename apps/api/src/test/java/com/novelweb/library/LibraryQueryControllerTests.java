package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
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
class LibraryQueryControllerTests {

    @Mock
    private LibraryService libraryService;

    @Mock
    private PolicyService policyService;

    private MockMvc mockMvc;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        mockMvc = MockMvcBuilders.standaloneSetup(new LibraryQueryController(libraryService, policyService)).build();
    }

    @Test
    void anonymousAndPolicyBlockedRequestsNeverCallLibraryService() throws Exception {
        var anonymous = mockMvc.perform(get("/api/v1/libraries")).andReturn();
        assertEquals(401, anonymous.getResponse().getStatus());
        assertTrue(anonymous.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

        when(policyService.requiredAcceptance(actorId)).thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
        var blocked = mockMvc.perform(get("/api/v1/libraries").principal(authentication()))
                .andReturn();
        assertEquals(403, blocked.getResponse().getStatus());
        assertTrue(blocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
        verifyNoInteractions(libraryService);
    }

    @Test
    void libraryResponseMapsOnlyPublicFieldsAndDisablesCaching() throws Exception {
        when(policyService.requiredAcceptance(actorId)).thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
        UUID libraryId = UUID.randomUUID();
        when(libraryService.listLibraries(actorId, null, 25)).thenReturn(new LibraryService.Page<>(List.of(
                new LibraryRepository.LibraryRecord(
                        libraryId, actorId, "My Shelf", "private", true, Instant.EPOCH, null)), "next-page"));

        var response = mockMvc.perform(get("/api/v1/libraries").principal(authentication()))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertEquals(MediaType.APPLICATION_JSON_VALUE, response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains(libraryId.toString()));
        assertTrue(body.contains("next-page"));
        assertFalse(body.contains("ownerUserId"));
        assertFalse(body.contains(actorId.toString()));
        verify(libraryService).listLibraries(actorId, null, 25);
    }

    @Test
    void workResponseOmitsOwnershipAndImportHashesAndBindsQueryToActor() throws Exception {
        when(policyService.requiredAcceptance(actorId)).thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
        UUID libraryId = UUID.randomUUID();
        UUID workId = UUID.randomUUID();
        when(libraryService.listWorks(actorId, libraryId, "wind", "fantasy", "active", null, 10))
                .thenReturn(new LibraryService.Page<>(List.of(new LibraryRepository.WorkRecord(
                        workId, libraryId, actorId, "Wind Novel", "Writer", "vi", "private", "active",
                        new byte[32], 4, Instant.EPOCH)), null));

        var response = mockMvc.perform(get("/api/v1/works")
                        .param("libraryId", libraryId.toString()).param("q", "wind")
                        .param("tag", "fantasy").param("status", "active").param("limit", "10")
                        .principal(authentication()))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains(workId.toString()));
        assertTrue(body.contains("chapterCount"));
        assertFalse(body.contains("ownerUserId"));
        assertFalse(body.contains("sourceHash"));
        assertFalse(body.contains(actorId.toString()));
        verify(libraryService).listWorks(actorId, libraryId, "wind", "fantasy", "active", null, 10);
    }

    @Test
    void invalidLibraryIdAndCursorBecomeSafeBadRequestProblems() throws Exception {
        when(policyService.requiredAcceptance(actorId)).thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
        var invalidId = mockMvc.perform(get("/api/v1/works")
                        .param("libraryId", "not-a-uuid").principal(authentication()))
                .andReturn().getResponse();
        assertEquals(400, invalidId.getStatus());
        assertTrue(invalidId.getContentAsString().contains("INVALID_LIBRARY_QUERY"));

        when(libraryService.listLibraries(actorId, "tampered", 25))
                .thenThrow(new LibraryCursorCodec.InvalidCursorException());
        var invalidCursor = mockMvc.perform(get("/api/v1/libraries")
                        .param("cursor", "tampered").principal(authentication()))
                .andReturn().getResponse();
        assertEquals(400, invalidCursor.getStatus());
        assertTrue(invalidCursor.getContentAsString().contains("INVALID_CURSOR"));
    }

    private TestingAuthenticationToken authentication() {
        return new TestingAuthenticationToken(
                new SessionService.SessionPrincipal(actorId, Instant.MAX), null);
    }
}
