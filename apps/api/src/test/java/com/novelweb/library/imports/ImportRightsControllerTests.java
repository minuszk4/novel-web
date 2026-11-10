package com.novelweb.library.imports;

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

import com.novelweb.identity.session.SessionService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@ExtendWith(MockitoExtension.class)
class ImportRightsControllerTests {

    @Mock
    private ImportRightsService importRightsService;

    private MockMvc mockMvc;
    private UUID actorId;
    private UUID idempotencyKey;

    @BeforeEach
    void setUp() {
        actorId = UUID.randomUUID();
        idempotencyKey = UUID.randomUUID();
        mockMvc = MockMvcBuilders.standaloneSetup(new ImportRightsController(importRightsService)).build();
    }

    @Test
    void currentStatementReturnsNoStoreAndExactApprovedText() throws Exception {
        String text = "Exact approved copy\nSecond line";
        when(importRightsService.currentStatement()).thenReturn(Optional.of(
                new ImportRightsService.ImportRightsStatement("private-v1", Instant.parse("2026-01-01T00:00:00Z"), text)));

        var response = mockMvc.perform(get("/api/v1/imports/rights-statement/current"))
                .andReturn().getResponse();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertTrue(response.getContentAsString().contains("Exact approved copy\\nSecond line"));
    }

    @Test
    void currentStatementWithoutApprovedCopyFailsClosed() throws Exception {
        when(importRightsService.currentStatement()).thenReturn(Optional.empty());

        var response = mockMvc.perform(get("/api/v1/imports/rights-statement/current"))
                .andReturn().getResponse();

        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("IMPORT_RIGHTS_STATEMENT_NOT_CONFIGURED"));
    }

    @Test
    void missingOrFalseConfirmationDoesNotWriteAnAcceptance() throws Exception {
        var response = mockMvc.perform(post("/api/v1/imports/rights-statement/acceptance")
                        .principal(authentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statementVersion\":\"private-v1\",\"confirmed\":false}"))
                .andReturn().getResponse();

        assertEquals(422, response.getStatus());
        assertTrue(response.getContentAsString().contains("IMPORT_RIGHTS_CONFIRMATION_REQUIRED"));
        verifyNoInteractions(importRightsService);
    }

    @Test
    void confirmationRequiresAnIdempotencyKey() throws Exception {
        var response = mockMvc.perform(post("/api/v1/imports/rights-statement/acceptance")
                        .principal(authentication())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statementVersion\":\"private-v1\",\"confirmed\":true}"))
                .andReturn().getResponse();

        assertEquals(422, response.getStatus());
        assertTrue(response.getContentAsString().contains("IDEMPOTENCY_KEY_REQUIRED"));
        verifyNoInteractions(importRightsService);
    }

    @Test
    void staleVersionReturnsCurrentVersionWithoutWriting() throws Exception {
        var current = new ImportRightsService.ImportRightsStatement(
                "private-v2", Instant.parse("2026-02-01T00:00:00Z"), "Current approved copy");
        when(importRightsService.accept(actorId, "private-v1", idempotencyKey))
                .thenReturn(new ImportRightsService.AcceptanceResult(
                        ImportRightsService.Outcome.STALE, null, current));

        var response = mockMvc.perform(post("/api/v1/imports/rights-statement/acceptance")
                        .principal(authentication())
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statementVersion\":\"private-v1\",\"confirmed\":true}"))
                .andReturn().getResponse();

        assertEquals(409, response.getStatus());
        assertTrue(response.getContentAsString().contains("IMPORT_RIGHTS_STATEMENT_STALE"));
        assertTrue(response.getContentAsString().contains("private-v2"));
        verify(importRightsService).accept(actorId, "private-v1", idempotencyKey);
    }

    @Test
    void acceptedConfirmationReturnsOnlyTheVersionedAuditReceipt() throws Exception {
        UUID acceptanceId = UUID.randomUUID();
        when(importRightsService.accept(actorId, "private-v1", idempotencyKey)).thenReturn(
                new ImportRightsService.AcceptanceResult(ImportRightsService.Outcome.ACCEPTED,
                        new ImportRightsService.Acceptance(acceptanceId, "private-v1",
                                Instant.parse("2026-03-01T00:00:00Z"), "a".repeat(64)), null));

        var response = mockMvc.perform(post("/api/v1/imports/rights-statement/acceptance")
                        .principal(authentication())
                        .header("Idempotency-Key", idempotencyKey.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statementVersion\":\"private-v1\",\"confirmed\":true}"))
                .andReturn().getResponse();

        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertTrue(response.getContentAsString().contains(acceptanceId.toString()));
        assertTrue(response.getContentAsString().contains("statementSha256"));
    }

    private TestingAuthenticationToken authentication() {
        return new TestingAuthenticationToken(
                new SessionService.SessionPrincipal(actorId, Instant.MAX), null);
    }
}
