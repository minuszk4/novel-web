package com.novelweb.platform.postgres;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.novelweb.identity.auth.GoogleAuthBootstrapRateLimiter;
import com.novelweb.identity.auth.GoogleAuthAttemptService;
import com.novelweb.identity.auth.GoogleAccountLinkRequiredException;
import com.novelweb.identity.auth.GoogleIdentityService;
import com.novelweb.identity.auth.GoogleIdentityTestFixtures;
import com.novelweb.identity.auth.UnavailableGoogleIdentityException;
import com.novelweb.identity.auth.GoogleIdTokenVerifier;
import org.junit.jupiter.api.BeforeAll;
import com.novelweb.identity.session.SessionService;
import com.novelweb.identity.session.SessionCsrfTokenService;
import com.novelweb.identity.session.LogoutAllConfirmationService;
import com.novelweb.library.LibraryCursorCodec;
import com.novelweb.library.LibraryRepository;
import com.novelweb.library.LibraryService;
import com.novelweb.library.LibraryUserStateRepository;
import com.novelweb.library.LibraryUserStateService;
import com.novelweb.library.WorkspaceRepository;
import com.novelweb.library.WorkspaceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
@org.springframework.test.context.TestPropertySource(properties = {
        "novel.auth.google.rate-limit-refill-interval=24h",
        "novel.auth.google.rate-limit-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "novel.auth.google.web-client-id=novel-web-integration-test.apps.googleusercontent.com",
        "novel.auth.google.credential-callback-uri=https://api.example.test/api/v1/auth/google/credential",
        "novel.security.csrf.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "novel.library.cursor.hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "novel.web.cors.allowed-origins=https://app.example.test"
})
class PostgresSchemaIntegrationTests {

    private static final String BOOTSTRAP_RATE_LIMIT_HMAC_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final Set<String> EXPECTED_APPLICATION_TABLES = Set.of(
            "users",
            "external_identities",
            "sessions",
            "google_auth_attempts",
            "google_auth_bootstrap_rate_limits",
            "policy_acceptances",
            "policy_documents",
            "auth_events",
            "session_revoke_confirmations",
            "libraries",
            "works",
            "volumes",
            "chapters",
            "chapter_revisions",
            "segments",
            "assets",
            "tags",
            "work_tags",
            "workspaces",
            "workspace_members",
            "library_reading_states",
            "library_collections",
            "library_collection_works");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GoogleAuthAttemptService googleAuthAttemptService;

    @Autowired
    private GoogleAuthBootstrapRateLimiter googleAuthBootstrapRateLimiter;

    @Autowired
    private GoogleIdentityService googleIdentityService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionCsrfTokenService csrfTokenService;

    @Autowired
    private LogoutAllConfirmationService logoutAllConfirmationService;

    @Autowired
    private LibraryService libraryService;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private LibraryUserStateService libraryUserStateService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeAll
    void insertPolicyDocumentFixtures() throws SQLException {
        insertPolicyTestDocuments();
    }

    private void insertPolicyTestDocuments() throws SQLException {
        insertPolicyDocument("TERMS", "terms-old", -3,
                "# Test terms v1\n\nSynthetic test document; not legal copy.");
        insertPolicyDocument("TERMS", "terms-current-1", -2,
                "# Test terms v2\n\nSynthetic test document; not legal copy.");
        insertPolicyDocument("TERMS", "terms-future-2", 1,
                "# Future test terms\n\nSynthetic test document; not legal copy.");
        insertPolicyDocument("PRIVACY_NOTICE", "privacy-old", -2,
                "# Test privacy v1\n\nSynthetic test document; not legal copy.");
        insertPolicyDocument("PRIVACY_NOTICE", "privacy-current-1", -1,
                "# Test privacy v2\n\nSynthetic test document; not legal copy.");
    }

    @Test
    void liquibaseAppliesTheBaselineAndCreatesExpectedTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement changesetCount = connection.prepareStatement(
                     "SELECT COUNT(*) FROM databasechangelog");
             ResultSet changesets = changesetCount.executeQuery()) {
            changesets.next();
            assertEquals(43, changesets.getInt(1), "unexpected number of applied changesets");
        }

        Set<String> actualTables = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement tableQuery = connection.prepareStatement("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_type = 'BASE TABLE'
                       AND table_name NOT IN ('databasechangelog', 'databasechangeloglock')
                     """);
             ResultSet tables = tableQuery.executeQuery()) {
            while (tables.next()) {
                actualTables.add(tables.getString(1));
            }
        }
        assertEquals(EXPECTED_APPLICATION_TABLES, actualTables);
    }

    @Test
    void personalLibraryStateIsActorScopedVersionedAndIndependentOfWorkLifecycle() throws Exception {
        UUID ownerId = createSessionTestUser("Library State Owner");
        UUID otherUserId = createSessionTestUser("Other Library State Owner");
        UUID workId = null;
        try {
            LibraryRepository.LibraryRecord library = libraryService.createLibrary(ownerId, "State Shelf");
            LibraryRepository.WorkRecord work = libraryService.createPrivateImportedWork(
                    ownerId, library.id(), "State Test Novel", "Author", "vi", sha256("library-state-work"));
            workId = work.id();
            setWorkStatus(workId, "archived");

            MvcResult unauthenticated = mockMvc.perform(get("/api/v1/me/works/{workId}/library-state", workId))
                    .andReturn();
            assertEquals(401, unauthenticated.getResponse().getStatus());
            assertTrue(unauthenticated.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

            SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
            SessionService.IssuedSession otherSession = sessionService.create(otherUserId).orElseThrow();
            jakarta.servlet.http.Cookie ownerCookie = new jakarta.servlet.http.Cookie(
                    "__Host-novel_session", ownerSession.cookieValue());
            String ownerCsrf = csrfTokenService.tokenFor(ownerSession.cookieValue());

            MvcResult policyBlocked = mockMvc.perform(get("/api/v1/me/library/collections")
                            .cookie(ownerCookie))
                    .andReturn();
            assertEquals(403, policyBlocked.getResponse().getStatus());
            assertTrue(policyBlocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));

            addCurrentPolicyAcceptances(ownerId);
            addCurrentPolicyAcceptances(otherUserId);

            MvcResult missingCsrf = mockMvc.perform(post("/api/v1/me/library/collections")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Must Not Be Stored\"}")
                            .cookie(ownerCookie))
                    .andReturn();
            assertEquals(403, missingCsrf.getResponse().getStatus());
            assertTrue(missingCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));
            assertTrue(libraryUserStateService.listCollections(ownerId).isEmpty());

            MvcResult created = mockMvc.perform(post("/api/v1/me/library/collections")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"  Weekend Reads  \"}")
                            .header("X-CSRF-Token", ownerCsrf)
                            .cookie(ownerCookie))
                    .andReturn();
            assertEquals(201, created.getResponse().getStatus());
            assertTrue(created.getResponse().getHeader("Cache-Control").contains("no-store"));
            JsonNode collectionBody = objectMapper.readTree(created.getResponse().getContentAsString());
            UUID collectionId = UUID.fromString(collectionBody.path("id").asText());
            assertEquals("Weekend Reads", collectionBody.path("name").asText());
            assertEquals(0, collectionBody.path("workCount").asInt());

            MvcResult initialState = mockMvc.perform(get("/api/v1/me/works/{workId}/library-state", workId)
                            .cookie(ownerCookie))
                    .andReturn();
            assertEquals(200, initialState.getResponse().getStatus());
            assertEquals("\"v0\"", initialState.getResponse().getHeader("ETag"));
            JsonNode initialBody = objectMapper.readTree(initialState.getResponse().getContentAsString());
            assertEquals("want-to-read", initialBody.path("readingStatus").asText());
            assertEquals(0, initialBody.path("version").asLong());
            assertTrue(initialBody.path("collectionIds").isEmpty());
            assertTrue(initialBody.path("updatedAt").isNull());

            MvcResult saved = mockMvc.perform(put("/api/v1/me/works/{workId}/library-state", workId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"readingStatus\":\"reading\",\"collectionIds\":[\""
                                    + collectionId + "\"]}")
                            .header("If-Match", "\"v0\"")
                            .header("X-CSRF-Token", ownerCsrf)
                            .cookie(ownerCookie))
                    .andReturn();
            assertEquals(200, saved.getResponse().getStatus());
            assertEquals("\"v1\"", saved.getResponse().getHeader("ETag"));
            JsonNode savedBody = objectMapper.readTree(saved.getResponse().getContentAsString());
            assertEquals("reading", savedBody.path("readingStatus").asText());
            assertEquals(1, savedBody.path("version").asLong());
            assertEquals(collectionId.toString(), savedBody.path("collectionIds").get(0).asText());
            assertEquals("archived", libraryService.findOwnedWork(ownerId, workId).orElseThrow().status(),
                    "personal reading state must not change work lifecycle status");

            MvcResult retryAfterLostResponse = mockMvc.perform(put(
                            "/api/v1/me/works/{workId}/library-state", workId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"readingStatus\":\"reading\",\"collectionIds\":[\""
                                    + collectionId + "\"]}")
                            .header("If-Match", "\"v0\"")
                            .header("X-CSRF-Token", ownerCsrf)
                            .cookie(ownerCookie))
                    .andReturn();
            assertEquals(200, retryAfterLostResponse.getResponse().getStatus());
            assertEquals("\"v1\"", retryAfterLostResponse.getResponse().getHeader("ETag"));

            MvcResult stale = mockMvc.perform(put("/api/v1/me/works/{workId}/library-state", workId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"readingStatus\":\"completed\",\"collectionIds\":[]}")
                            .header("If-Match", "\"v0\"")
                            .header("X-CSRF-Token", ownerCsrf)
                            .cookie(ownerCookie))
                    .andReturn();
            assertEquals(409, stale.getResponse().getStatus());
            assertTrue(stale.getResponse().getContentAsString().contains("LIBRARY_STATE_VERSION_STALE"));
            assertEquals(1, objectMapper.readTree(stale.getResponse().getContentAsString())
                    .path("latest").path("version").asLong());

            MvcResult otherUserRead = mockMvc.perform(get("/api/v1/me/works/{workId}/library-state", workId)
                            .cookie(new jakarta.servlet.http.Cookie(
                                    "__Host-novel_session", otherSession.cookieValue())))
                    .andReturn();
            assertEquals(404, otherUserRead.getResponse().getStatus());

            LibraryUserStateRepository.CollectionRecord sameNameForOtherActor =
                    libraryUserStateService.createCollection(otherUserId, "Weekend Reads");
            assertEquals("Weekend Reads", sameNameForOtherActor.name());
            assertEquals(1, libraryUserStateService.listCollections(otherUserId).size());
            assertThrows(LibraryUserStateRepository.CollectionNameConflictException.class,
                    () -> libraryUserStateService.createCollection(ownerId, "weekend reads"));

            for (int index = 1; index < LibraryUserStateService.MAX_COLLECTIONS; index++) {
                libraryUserStateService.createCollection(ownerId, "Reading List " + index);
            }
            assertEquals(12, libraryUserStateService.listCollections(ownerId).size());
            assertThrows(LibraryUserStateRepository.CollectionLimitReachedException.class,
                    () -> libraryUserStateService.createCollection(ownerId, "Thirteenth List"));

            MvcResult deleted = mockMvc.perform(delete("/api/v1/me/library/collections/{collectionId}", collectionId)
                            .header("X-CSRF-Token", ownerCsrf)
                            .cookie(ownerCookie))
                    .andReturn();
            assertEquals(204, deleted.getResponse().getStatus());
            MvcResult stateAfterCollectionDelete = mockMvc.perform(
                            get("/api/v1/me/works/{workId}/library-state", workId).cookie(ownerCookie))
                    .andReturn();
            assertEquals(200, stateAfterCollectionDelete.getResponse().getStatus());
            assertEquals("\"v2\"", stateAfterCollectionDelete.getResponse().getHeader("ETag"));
            JsonNode afterDelete = objectMapper.readTree(stateAfterCollectionDelete.getResponse().getContentAsString());
            assertEquals("reading", afterDelete.path("readingStatus").asText());
            assertTrue(afterDelete.path("collectionIds").isEmpty());
        } finally {
            if (workId != null) deleteLibraryUserData(ownerId, workId);
            deleteLibraryUserData(otherUserId, null);
            deleteSessionTestUser(otherUserId);
            deleteSessionTestUser(ownerId);
        }
    }

    @Test
    void libraryListRoutesEnforceActorScopePolicyAndSignedPagination() throws Exception {
        UUID ownerId = createSessionTestUser("Library Query Owner");
        UUID readerId = createSessionTestUser("Library Query Reader");
        UUID outsiderId = createSessionTestUser("Library Query Outsider");
        UUID workspaceId = null;
        try {
            libraryService.createLibrary(ownerId, "Personal Shelf");
            WorkspaceRepository.WorkspaceRecord workspace =
                    workspaceService.createWorkspace(ownerId, "Library Query Workspace");
            workspaceId = workspace.id();
            workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);
            LibraryRepository.LibraryRecord sharedLibrary =
                    libraryService.createWorkspaceLibrary(ownerId, workspaceId, "Shared Shelf");
            for (String title : List.of("Cursor Novel One", "Cursor Novel Two")) {
                libraryService.createPrivateImportedWork(
                        ownerId, sharedLibrary.id(), title, "Query Author", "vi", sha256(title));
            }
            addCurrentPolicyAcceptances(ownerId);
            addCurrentPolicyAcceptances(readerId);
            addCurrentPolicyAcceptances(outsiderId);

            SessionService.IssuedSession ownerSession = sessionService.create(ownerId).orElseThrow();
            SessionService.IssuedSession readerSession = sessionService.create(readerId).orElseThrow();
            SessionService.IssuedSession outsiderSession = sessionService.create(outsiderId).orElseThrow();
            jakarta.servlet.http.Cookie ownerCookie = new jakarta.servlet.http.Cookie(
                    "__Host-novel_session", ownerSession.cookieValue());
            jakarta.servlet.http.Cookie readerCookie = new jakarta.servlet.http.Cookie(
                    "__Host-novel_session", readerSession.cookieValue());
            jakarta.servlet.http.Cookie outsiderCookie = new jakarta.servlet.http.Cookie(
                    "__Host-novel_session", outsiderSession.cookieValue());

            assertEquals(401, mockMvc.perform(get("/api/v1/libraries")).andReturn().getResponse().getStatus());
            UUID policyBlockedUser = createSessionTestUser("Library Query Policy Gate");
            try {
                SessionService.IssuedSession blockedSession = sessionService.create(policyBlockedUser).orElseThrow();
                MvcResult blocked = mockMvc.perform(get("/api/v1/libraries")
                                .cookie(new jakarta.servlet.http.Cookie(
                                        "__Host-novel_session", blockedSession.cookieValue())))
                        .andReturn();
                assertEquals(403, blocked.getResponse().getStatus());
                assertTrue(blocked.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
            } finally {
                deleteSessionTestUser(policyBlockedUser);
            }

            MvcResult firstLibraries = mockMvc.perform(get("/api/v1/libraries")
                            .param("limit", "1").cookie(ownerCookie))
                    .andReturn();
            assertEquals(200, firstLibraries.getResponse().getStatus());
            assertTrue(firstLibraries.getResponse().getHeader("Cache-Control").contains("no-store"));
            assertEquals("no-cache", firstLibraries.getResponse().getHeader("Pragma"));
            JsonNode firstLibraryPage = objectMapper.readTree(firstLibraries.getResponse().getContentAsString());
            assertEquals(1, firstLibraryPage.path("items").size());
            assertTrue(firstLibraryPage.path("items").get(0).has("id"));
            assertFalse(firstLibraryPage.path("items").get(0).has("ownerUserId"));
            String libraryCursor = firstLibraryPage.path("nextCursor").asText();
            assertFalse(libraryCursor.isBlank());

            MvcResult secondLibraries = mockMvc.perform(get("/api/v1/libraries")
                            .param("limit", "1").param("cursor", libraryCursor).cookie(ownerCookie))
                    .andReturn();
            assertEquals(200, secondLibraries.getResponse().getStatus());
            assertEquals(1, objectMapper.readTree(secondLibraries.getResponse().getContentAsString())
                    .path("items").size());

            MvcResult memberLibraries = mockMvc.perform(get("/api/v1/libraries").cookie(readerCookie)).andReturn();
            assertEquals(200, memberLibraries.getResponse().getStatus());
            JsonNode memberLibraryPage = objectMapper.readTree(memberLibraries.getResponse().getContentAsString());
            assertEquals(1, memberLibraryPage.path("items").size());
            assertEquals(sharedLibrary.id().toString(), memberLibraryPage.path("items").get(0).path("id").asText());
            MvcResult foreignCursor = mockMvc.perform(get("/api/v1/libraries")
                            .param("cursor", libraryCursor).cookie(readerCookie))
                    .andReturn();
            assertEquals(400, foreignCursor.getResponse().getStatus());
            assertTrue(foreignCursor.getResponse().getContentAsString().contains("INVALID_CURSOR"));

            MvcResult firstWorks = mockMvc.perform(get("/api/v1/works")
                            .param("libraryId", sharedLibrary.id().toString())
                            .param("limit", "1").cookie(readerCookie))
                    .andReturn();
            assertEquals(200, firstWorks.getResponse().getStatus());
            JsonNode firstWorkPage = objectMapper.readTree(firstWorks.getResponse().getContentAsString());
            assertEquals(1, firstWorkPage.path("items").size());
            assertFalse(firstWorkPage.path("items").get(0).has("ownerUserId"));
            assertFalse(firstWorkPage.path("items").get(0).has("sourceHash"));
            String workCursor = firstWorkPage.path("nextCursor").asText();
            assertFalse(workCursor.isBlank());

            MvcResult secondWorks = mockMvc.perform(get("/api/v1/works")
                            .param("libraryId", sharedLibrary.id().toString())
                            .param("limit", "1").param("cursor", workCursor).cookie(readerCookie))
                    .andReturn();
            assertEquals(200, secondWorks.getResponse().getStatus());
            assertEquals(1, objectMapper.readTree(secondWorks.getResponse().getContentAsString())
                    .path("items").size());

            MvcResult tamperedCursor = mockMvc.perform(get("/api/v1/works")
                            .param("libraryId", sharedLibrary.id().toString())
                            .param("cursor", workCursor + "x").cookie(readerCookie))
                    .andReturn();
            assertEquals(400, tamperedCursor.getResponse().getStatus());
            assertTrue(tamperedCursor.getResponse().getContentAsString().contains("INVALID_CURSOR"));

            MvcResult invalidLibraryId = mockMvc.perform(get("/api/v1/works")
                            .param("libraryId", "not-a-uuid").cookie(readerCookie))
                    .andReturn();
            assertEquals(400, invalidLibraryId.getResponse().getStatus());

            MvcResult outsiderWorks = mockMvc.perform(get("/api/v1/works")
                            .param("libraryId", sharedLibrary.id().toString()).cookie(outsiderCookie))
                    .andReturn();
            assertEquals(404, outsiderWorks.getResponse().getStatus());
            MvcResult emptyOutsiderLibraries = mockMvc.perform(get("/api/v1/libraries").cookie(outsiderCookie))
                    .andReturn();
            assertEquals(200, emptyOutsiderLibraries.getResponse().getStatus());
            assertEquals(0, objectMapper.readTree(emptyOutsiderLibraries.getResponse().getContentAsString())
                    .path("items").size());

            workspaceService.revokeMember(ownerId, workspaceId, readerId);
            MvcResult revokedReader = mockMvc.perform(get("/api/v1/works")
                            .param("libraryId", sharedLibrary.id().toString()).cookie(readerCookie))
                    .andReturn();
            assertEquals(404, revokedReader.getResponse().getStatus());
        } finally {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement deleteWorks = connection.prepareStatement(
                         "DELETE FROM works WHERE owner_user_id = ?")) {
                deleteWorks.setObject(1, ownerId);
                deleteWorks.executeUpdate();
            }
            deleteLibraryUserData(ownerId, null);
            deleteLibraryUserData(readerId, null);
            deleteLibraryUserData(outsiderId, null);
            if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
            deleteSessionTestUser(outsiderId);
            deleteSessionTestUser(readerId);
            deleteSessionTestUser(ownerId);
        }
    }

    @Test
    void workspaceReaderCanChangeOnlyTheirOwnReadingStateWhileMembershipIsActive() throws Exception {
        UUID ownerId = createSessionTestUser("Workspace State Owner");
        UUID readerId = createSessionTestUser("Workspace State Reader");
        UUID workspaceId = null;
        UUID workId = null;
        try {
            WorkspaceRepository.WorkspaceRecord workspace =
                    workspaceService.createWorkspace(ownerId, "Reading State Workspace");
            workspaceId = workspace.id();
            workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);
            LibraryRepository.LibraryRecord library =
                    libraryService.createWorkspaceLibrary(ownerId, workspaceId, "Shared Works");
            LibraryRepository.WorkRecord work = libraryService.createPrivateImportedWork(
                    ownerId, library.id(), "Shared State Novel", "Author", "vi", sha256("shared-state-work"));
            workId = work.id();
            addCurrentPolicyAcceptances(ownerId);
            addCurrentPolicyAcceptances(readerId);

            LibraryUserStateRepository.WorkStateRecord ownerState = libraryUserStateService.replaceWorkState(
                    ownerId, workId, 0, "reading", List.of());
            assertEquals(1, ownerState.version());

            SessionService.IssuedSession readerSession = sessionService.create(readerId).orElseThrow();
            jakarta.servlet.http.Cookie readerCookie = new jakarta.servlet.http.Cookie(
                    "__Host-novel_session", readerSession.cookieValue());
            String readerCsrf = csrfTokenService.tokenFor(readerSession.cookieValue());
            MvcResult readerInitial = mockMvc.perform(get("/api/v1/me/works/{workId}/library-state", workId)
                            .cookie(readerCookie))
                    .andReturn();
            assertEquals(200, readerInitial.getResponse().getStatus());
            assertEquals("\"v0\"", readerInitial.getResponse().getHeader("ETag"));
            assertEquals("want-to-read", objectMapper.readTree(readerInitial.getResponse().getContentAsString())
                    .path("readingStatus").asText());

            MvcResult readerSaved = mockMvc.perform(put("/api/v1/me/works/{workId}/library-state", workId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"readingStatus\":\"completed\",\"collectionIds\":[]}")
                            .header("If-Match", "\"v0\"")
                            .header("X-CSRF-Token", readerCsrf)
                            .cookie(readerCookie))
                    .andReturn();
            assertEquals(200, readerSaved.getResponse().getStatus());
            assertEquals("completed", objectMapper.readTree(readerSaved.getResponse().getContentAsString())
                    .path("readingStatus").asText());
            assertEquals("reading", libraryUserStateService.getWorkState(ownerId, workId).readingStatus());
            assertEquals("completed", libraryUserStateService.getWorkState(readerId, workId).readingStatus());

            workspaceService.revokeMember(ownerId, workspaceId, readerId);
            MvcResult revokedReader = mockMvc.perform(get("/api/v1/me/works/{workId}/library-state", workId)
                            .cookie(readerCookie))
                    .andReturn();
            assertEquals(404, revokedReader.getResponse().getStatus());
        } finally {
            deleteLibraryUserData(readerId, null);
            deleteLibraryUserData(ownerId, workId);
            if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
            deleteSessionTestUser(readerId);
            deleteSessionTestUser(ownerId);
        }
    }

    @Test
    void privateLibraryCoreScopesOwnersDefaultsPrivateAndRejectsDuplicateHashesAndOrder() throws Exception {
        UUID ownerId = createSessionTestUser("Library Owner");
        UUID otherOwnerId = createSessionTestUser("Other Library Owner");
        try {
            LibraryRepository.LibraryRecord defaultLibrary = libraryService.createLibrary(ownerId, "My Library");
            LibraryRepository.LibraryRecord secondLibrary = libraryService.createLibrary(ownerId, "Research Shelf");
            LibraryRepository.LibraryRecord otherLibrary = libraryService.createLibrary(otherOwnerId, "My Library");

            assertTrue(defaultLibrary.isDefault());
            assertFalse(secondLibrary.isDefault());
            assertTrue(otherLibrary.isDefault());
            assertEquals("private", defaultLibrary.visibility());

            byte[] digest = sha256("same-import-content");
            LibraryRepository.WorkRecord work = libraryService.createPrivateImportedWork(
                    ownerId, defaultLibrary.id(), "A Private Novel", "Author", "vi", digest);
            assertEquals("private", work.visibility());
            assertEquals("active", work.status());
            assertEquals(0, work.chapterCount());
            assertEquals(1, libraryService.listWorks(ownerId, defaultLibrary.id(), "", 25).size());
            assertTrue(libraryService.findOwnedWork(otherOwnerId, work.id()).isEmpty());
            assertThrows(LibraryRepository.LibraryNotFoundException.class,
                    () -> libraryService.listWorks(otherOwnerId, defaultLibrary.id(), "", 25));
            assertThrows(LibraryRepository.DuplicateImportedWorkException.class,
                    () -> libraryService.createPrivateImportedWork(
                            ownerId, secondLibrary.id(), "Duplicate Copy", "Author", "vi", digest));

            LibraryRepository.WorkRecord sameContentForOtherOwner = libraryService.createPrivateImportedWork(
                    otherOwnerId, otherLibrary.id(), "Own Copy", "Author", "vi", digest);
            assertEquals("private", sameContentForOtherOwner.visibility());

            LibraryRepository.VolumeRecord volume = libraryService.createVolume(ownerId, work.id(), 1, "Volume 1");
            SQLException duplicateVolumeOrder = assertThrows(SQLException.class,
                    () -> insertTestVolume(UUID.randomUUID(), work.id(), 1, "Duplicate volume"));
            assertEquals("23505", duplicateVolumeOrder.getSQLState());

            LibraryRepository.ChapterRecord chapter = libraryService.createChapter(
                    ownerId, work.id(), volume.id(), 1, "Chapter 1", "zh-Hans");
            assertEquals("draft", chapter.status());
            assertTrue(libraryService.findOwnedChapter(otherOwnerId, chapter.id()).isEmpty());
            assertThrows(LibraryRepository.WorkNotFoundException.class,
                    () -> libraryService.createChapter(otherOwnerId, work.id(), volume.id(), 2, "Foreign", "zh"));
            SQLException duplicateChapterOrder = assertThrows(SQLException.class,
                    () -> insertTestChapter(UUID.randomUUID(), work.id(), volume.id(), 1, "Duplicate chapter"));
            assertEquals("23505", duplicateChapterOrder.getSQLState());

            assertThrows(IllegalArgumentException.class,
                    () -> libraryService.appendChapterRevision(
                            ownerId,
                            chapter.id(),
                            "plain_text",
                            "Duplicate anchors must fail before persistence.",
                            List.of(
                                    new LibraryService.NewSegment(1, "paragraph", "First", "same-anchor"),
                                    new LibraryService.NewSegment(2, "paragraph", "Second", "same-anchor"))));
            assertEquals(0, revisionCount(chapter.id()));

            LibraryRepository.RevisionRecord revision = libraryService.appendChapterRevision(
                    ownerId,
                    chapter.id(),
                    "plain_text",
                    "First paragraph.\nSecond paragraph.",
                    List.of(
                            new LibraryService.NewSegment(1, "paragraph", "First paragraph.", "p1"),
                            new LibraryService.NewSegment(2, "paragraph", "Second paragraph.", "p2")));
            assertEquals(1, revision.revisionNo());
            LibraryRepository.ChapterRecord readyChapter = libraryService.findOwnedChapter(ownerId, chapter.id())
                    .orElseThrow();
            assertEquals("ready", readyChapter.status());
            assertEquals(revision.id(), readyChapter.sourceRevisionId());
            assertEquals(2, segmentCount(revision.id()));
            assertThrows(LibraryRepository.DuplicateRevisionException.class,
                    () -> libraryService.appendChapterRevision(
                            ownerId, chapter.id(), "plain_text", "First paragraph.\nSecond paragraph.", List.of()));

            LibraryRepository.AssetRecord asset = libraryService.registerAsset(
                    ownerId, defaultLibrary.id(), "private/" + UUID.randomUUID(), sha256("cover-image"),
                    "image/webp", 2048, "cover.webp");
            assertEquals("staged", asset.status());
            assertThrows(LibraryRepository.DuplicateAssetException.class,
                    () -> libraryService.registerAsset(
                            ownerId, secondLibrary.id(), "private/" + UUID.randomUUID(), sha256("cover-image"),
                            "image/webp", 2048, "duplicate-cover.webp"));
            LibraryRepository.AssetRecord sameAssetForOtherOwner = libraryService.registerAsset(
                    otherOwnerId, otherLibrary.id(), "private/" + UUID.randomUUID(), sha256("cover-image"),
                    "image/webp", 2048, "cover.webp");
            assertNotEquals(asset.ownerUserId(), sameAssetForOtherOwner.ownerUserId());
        } finally {
            deleteLibraryTestData(ownerId);
            deleteLibraryTestData(otherOwnerId);
            deleteSessionTestUser(ownerId);
            deleteSessionTestUser(otherOwnerId);
        }
    }

    @Test
    void privateLibraryCursorsAreStableOwnerAndFilterBoundAndSearchTagsAndStatus() throws Exception {
        UUID ownerId = createSessionTestUser("Cursor Library Owner");
        UUID otherOwnerId = createSessionTestUser("Other Cursor Owner");
        try {
            LibraryRepository.LibraryRecord library = libraryService.createLibrary(ownerId, "Cursor Shelf");
            LibraryRepository.LibraryRecord secondLibrary = libraryService.createLibrary(ownerId, "Second Shelf");
            LibraryRepository.LibraryRecord otherLibrary = libraryService.createLibrary(otherOwnerId, "Other Shelf");
            LibraryRepository.WorkRecord dragonRiver = libraryService.createPrivateImportedWork(
                    ownerId, library.id(), "Dragon River", "Writer A", "zh", sha256("cursor-work-a"));
            LibraryRepository.WorkRecord dragonGate = libraryService.createPrivateImportedWork(
                    ownerId, library.id(), "Dragon Gate", "Writer B", "zh", sha256("cursor-work-b"));
            LibraryRepository.WorkRecord moonCity = libraryService.createPrivateImportedWork(
                    ownerId, library.id(), "Moon City", "Dragon Pen", "zh", sha256("cursor-work-c"));
            setWorkCreatedAt(dragonRiver.id(), Instant.parse("2026-01-04T00:00:00Z"));
            setWorkCreatedAt(dragonGate.id(), Instant.parse("2026-01-03T00:00:00Z"));
            setWorkCreatedAt(moonCity.id(), Instant.parse("2026-01-02T00:00:00Z"));
            setWorkStatus(dragonGate.id(), "archived");

            libraryService.addTagToWork(ownerId, dragonRiver.id(), "Tiên Hiệp");
            libraryService.addTagToWork(ownerId, dragonGate.id(), "TIÊN-hiệp");
            libraryService.addTagToWork(ownerId, moonCity.id(), "tiên-hiệp");
            assertThrows(LibraryRepository.WorkNotFoundException.class,
                    () -> libraryService.addTagToWork(otherOwnerId, dragonRiver.id(), "tiên-hiệp"));

            LibraryService.Page<LibraryRepository.LibraryRecord> firstLibraryPage =
                    libraryService.listLibraries(ownerId, null, 1);
            assertEquals(1, firstLibraryPage.items().size());
            assertTrue(firstLibraryPage.items().getFirst().isDefault());
            assertEquals(1, libraryService.listLibraries(ownerId, firstLibraryPage.nextCursor(), 1).items().size());
            assertThrows(LibraryCursorCodec.InvalidCursorException.class,
                    () -> libraryService.listLibraries(otherOwnerId, firstLibraryPage.nextCursor(), 1));

            LibraryService.Page<LibraryRepository.WorkRecord> firstWorkPage = libraryService.listWorks(
                    ownerId, library.id(), "dragon", "tiên hiệp", "active", null, 1);
            assertEquals(1, firstWorkPage.items().size());
            assertTrue(firstWorkPage.nextCursor() != null && !firstWorkPage.nextCursor().isBlank());
            assertThrows(LibraryCursorCodec.InvalidCursorException.class,
                    () -> libraryService.listWorks(
                            ownerId, library.id(), "moon", "tiên-hiệp", "active", firstWorkPage.nextCursor(), 1));
            assertThrows(LibraryCursorCodec.InvalidCursorException.class,
                    () -> libraryService.listWorks(
                            ownerId, secondLibrary.id(), "dragon", "tiên-hiệp", "active",
                            firstWorkPage.nextCursor(), 1));

            LibraryRepository.WorkRecord insertedAfterFirstPage = libraryService.createPrivateImportedWork(
                    ownerId, library.id(), "Dragon Update", "Writer C", "zh", sha256("cursor-work-d"));
            libraryService.addTagToWork(ownerId, insertedAfterFirstPage.id(), "tiên-hiệp");
            setWorkCreatedAt(insertedAfterFirstPage.id(), Instant.parse("2026-01-05T00:00:00Z"));

            LibraryService.Page<LibraryRepository.WorkRecord> secondWorkPage = libraryService.listWorks(
                    ownerId, library.id(), "dragon", "tiên-hiệp", "active", firstWorkPage.nextCursor(), 1);
            assertEquals(1, secondWorkPage.items().size());
            assertNotEquals(firstWorkPage.items().getFirst().id(), secondWorkPage.items().getFirst().id());
            assertNotEquals(insertedAfterFirstPage.id(), secondWorkPage.items().getFirst().id());
            assertNull(secondWorkPage.nextCursor());

            LibraryService.Page<LibraryRepository.WorkRecord> archived = libraryService.listWorks(
                    ownerId, library.id(), "dragon", "tiên-hiệp", "archived", null, 10);
            assertEquals(List.of(dragonGate.id()), archived.items().stream().map(LibraryRepository.WorkRecord::id).toList());
            assertThrows(LibraryRepository.LibraryNotFoundException.class,
                    () -> libraryService.listWorks(
                            ownerId, otherLibrary.id(), "dragon", "tiên-hiệp", "active", null, 10));
        } finally {
            deleteLibraryTestData(ownerId);
            deleteLibraryTestData(otherOwnerId);
            deleteSessionTestUser(ownerId);
            deleteSessionTestUser(otherOwnerId);
        }
    }

    @Test
    void workspaceLifecycleKeepsOneOwnerAndVersionsMembershipChanges() throws Exception {
        UUID ownerId = createSessionTestUser("Workspace Owner");
        UUID adminId = createSessionTestUser("Workspace Admin");
        UUID editorId = createSessionTestUser("Workspace Editor");
        UUID readerId = createSessionTestUser("Workspace Reader");
        UUID outsiderId = createSessionTestUser("Workspace Outsider");
        UUID workspaceId = null;
        try {
            WorkspaceRepository.WorkspaceRecord workspace =
                    workspaceService.createWorkspace(ownerId, "  Shared Reading Room  ");
            UUID createdWorkspaceId = workspace.id();
            workspaceId = createdWorkspaceId;
            assertEquals("Shared Reading Room", workspace.name());
            assertEquals(1, workspace.scopeVersion());
            assertEquals(WorkspaceRepository.Role.OWNER,
                    workspaceService.listMembers(ownerId, workspaceId).getFirst().role());
            SQLException missingActiveOwner = assertThrows(SQLException.class, () -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement deleteOwner = connection.prepareStatement("""
                            DELETE FROM workspace_members
                            WHERE workspace_id = ? AND user_id = ? AND role = 'owner'
                            """)) {
                        deleteOwner.setObject(1, createdWorkspaceId);
                        deleteOwner.setObject(2, ownerId);
                        assertEquals(1, deleteOwner.executeUpdate());
                    }
                    connection.commit();
                }
            });
            assertEquals("23514", missingActiveOwner.getSQLState());

            WorkspaceRepository.MemberRecord admin = workspaceService.addMember(
                    ownerId, workspaceId, adminId, WorkspaceRepository.Role.ADMIN);
            assertEquals(WorkspaceRepository.MemberStatus.ACTIVE, admin.status());
            workspaceService.addMember(ownerId, workspaceId, editorId, WorkspaceRepository.Role.EDITOR);
            workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);
            assertEquals(4, workspaceService.findWorkspace(ownerId, workspaceId).orElseThrow().scopeVersion());

            assertThrows(WorkspaceRepository.WorkspaceAccessDeniedException.class,
                    () -> workspaceService.addMember(outsiderId, createdWorkspaceId, outsiderId,
                            WorkspaceRepository.Role.READER));
            assertThrows(WorkspaceRepository.WorkspaceAccessDeniedException.class,
                    () -> workspaceService.addMember(readerId, createdWorkspaceId, outsiderId,
                            WorkspaceRepository.Role.READER));
            assertThrows(WorkspaceRepository.OwnershipTransferRequiredException.class,
                    () -> workspaceService.addMember(ownerId, createdWorkspaceId, outsiderId,
                            WorkspaceRepository.Role.OWNER));
            assertThrows(WorkspaceRepository.WorkspaceAccessDeniedException.class,
                    () -> workspaceService.transferOwnership(adminId, createdWorkspaceId, editorId));
            assertThrows(WorkspaceRepository.WorkspaceMemberConflictException.class,
                    () -> workspaceService.addMember(ownerId, createdWorkspaceId, adminId,
                            WorkspaceRepository.Role.EDITOR));

            WorkspaceRepository.MemberRecord changed = workspaceService.changeMemberRole(
                    adminId, workspaceId, readerId, WorkspaceRepository.Role.EDITOR);
            assertEquals(WorkspaceRepository.Role.EDITOR, changed.role());
            assertEquals(5, workspaceService.findWorkspace(ownerId, workspaceId).orElseThrow().scopeVersion());

            WorkspaceRepository.WorkspaceRecord transferred =
                    workspaceService.transferOwnership(ownerId, workspaceId, adminId);
            assertEquals(6, transferred.scopeVersion());
            assertEquals(WorkspaceRepository.Role.ADMIN, workspaceService.listMembers(ownerId, workspaceId)
                    .stream().filter(member -> member.userId().equals(ownerId)).findFirst().orElseThrow().role());
            assertEquals(WorkspaceRepository.Role.OWNER, workspaceService.listMembers(adminId, workspaceId)
                    .stream().filter(member -> member.userId().equals(adminId)).findFirst().orElseThrow().role());
            assertThrows(WorkspaceRepository.WorkspaceOwnerCannotBeRevokedException.class,
                    () -> workspaceService.revokeMember(adminId, createdWorkspaceId, adminId));

            WorkspaceRepository.MemberRecord revoked = workspaceService.revokeMember(
                    adminId, workspaceId, editorId);
            assertEquals(WorkspaceRepository.MemberStatus.REVOKED, revoked.status());
            assertTrue(revoked.revokedAt() != null);
            assertEquals(7, workspaceService.findWorkspace(adminId, workspaceId).orElseThrow().scopeVersion());
            assertTrue(workspaceService.findWorkspace(editorId, workspaceId).isEmpty());
            assertThrows(WorkspaceRepository.WorkspaceAccessDeniedException.class,
                    () -> workspaceService.transferOwnership(ownerId, createdWorkspaceId, readerId));
        } finally {
            if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
            deleteSessionTestUser(ownerId);
            deleteSessionTestUser(adminId);
            deleteSessionTestUser(editorId);
            deleteSessionTestUser(readerId);
            deleteSessionTestUser(outsiderId);
        }
    }

    @Test
    void workspaceRolesScopeLibraryAndContentReadsWritesAndSearch() throws Exception {
        UUID ownerId = createSessionTestUser("Workspace Library Owner");
        UUID adminId = createSessionTestUser("Workspace Library Admin");
        UUID editorId = createSessionTestUser("Workspace Library Editor");
        UUID readerId = createSessionTestUser("Workspace Library Reader");
        UUID outsiderId = createSessionTestUser("Other Workspace Owner");
        UUID workspaceId = null;
        UUID otherWorkspaceId = null;
        try {
            WorkspaceRepository.WorkspaceRecord workspace =
                    workspaceService.createWorkspace(ownerId, "Shared Shelf Team");
            UUID createdWorkspaceId = workspace.id();
            workspaceId = createdWorkspaceId;
            workspaceService.addMember(ownerId, workspaceId, adminId, WorkspaceRepository.Role.ADMIN);
            workspaceService.addMember(ownerId, workspaceId, editorId, WorkspaceRepository.Role.EDITOR);
            workspaceService.addMember(ownerId, workspaceId, readerId, WorkspaceRepository.Role.READER);

            LibraryRepository.LibraryRecord defaultLibrary = libraryService.createLibrary(ownerId, "My Personal Shelf");
            LibraryRepository.LibraryRecord movableLibrary = libraryService.createLibrary(ownerId, "Research Shelf");
            LibraryRepository.LibraryRecord workspaceLibrary =
                    libraryService.createWorkspaceLibrary(ownerId, createdWorkspaceId, "Collaborative Shelf");
            LibraryRepository.LibraryRecord adminLibrary =
                    libraryService.createWorkspaceLibrary(adminId, createdWorkspaceId, "Admin Shelf");
            assertEquals(createdWorkspaceId, workspaceLibrary.workspaceId());
            assertEquals(createdWorkspaceId, adminLibrary.workspaceId());
            assertThrows(LibraryRepository.DefaultLibraryCannotMoveException.class,
                    () -> libraryService.movePersonalLibraryToWorkspace(
                            ownerId, defaultLibrary.id(), createdWorkspaceId));
            LibraryRepository.LibraryRecord movedLibrary = libraryService.movePersonalLibraryToWorkspace(
                    ownerId, movableLibrary.id(), createdWorkspaceId);
            assertEquals(createdWorkspaceId, movedLibrary.workspaceId());

            LibraryRepository.WorkRecord ownerWork = libraryService.createPrivateImportedWork(
                    ownerId, workspaceLibrary.id(), "Shared Story", "Author A", "vi", sha256("shared-owner-work"));
            LibraryRepository.WorkRecord editorWork = libraryService.createPrivateImportedWork(
                    editorId, workspaceLibrary.id(), "Editor Story", "Author B", "vi", sha256("shared-editor-work"));
            assertEquals(ownerId, editorWork.ownerUserId(), "shared work keeps library creator as data owner");
            libraryService.addTagToWork(editorId, ownerWork.id(), "Shared Tag");

            List<UUID> editorLibraryIds = libraryService.listLibraries(editorId).stream()
                    .map(LibraryRepository.LibraryRecord::id).toList();
            assertTrue(editorLibraryIds.contains(workspaceLibrary.id()));
            assertTrue(editorLibraryIds.contains(movedLibrary.id()));
            assertFalse(editorLibraryIds.contains(defaultLibrary.id()));
            assertTrue(libraryService.findOwnedWork(readerId, ownerWork.id()).isPresent());
            LibraryService.Page<LibraryRepository.WorkRecord> tagSearch = libraryService.listWorks(
                    readerId, workspaceLibrary.id(), "Shared", "shared-tag", "active", null, 10);
            assertEquals(List.of(ownerWork.id()), tagSearch.items().stream()
                    .map(LibraryRepository.WorkRecord::id).toList());

            LibraryRepository.VolumeRecord volume = libraryService.createVolume(
                    editorId, ownerWork.id(), 1, "Shared Volume");
            LibraryRepository.ChapterRecord chapter = libraryService.createChapter(
                    editorId, ownerWork.id(), volume.id(), 1, "Shared Chapter", "vi");
            LibraryRepository.RevisionRecord revision = libraryService.appendChapterRevision(
                    editorId, chapter.id(), "plain_text", "Shared chapter content.",
                    List.of(new LibraryService.NewSegment(1, "paragraph", "Shared chapter content.", "p1")));
            assertEquals(1, revision.revisionNo());
            assertTrue(libraryService.findOwnedChapter(readerId, chapter.id()).isPresent());
            LibraryRepository.AssetRecord asset = libraryService.registerAsset(
                    editorId, workspaceLibrary.id(), "workspace/" + UUID.randomUUID(),
                    sha256("workspace-cover"), "image/webp", 4096, "shared-cover.webp");
            assertEquals(ownerId, asset.ownerUserId());

            assertThrows(LibraryRepository.LibraryNotFoundException.class,
                    () -> libraryService.createWorkspaceLibrary(
                            editorId, createdWorkspaceId, "Editor Created Shelf"));
            assertThrows(LibraryRepository.LibraryNotFoundException.class,
                    () -> libraryService.createPrivateImportedWork(
                            readerId, workspaceLibrary.id(), "Reader Write", "Author", "vi",
                            sha256("reader-write")));
            assertThrows(LibraryRepository.WorkNotFoundException.class,
                    () -> libraryService.addTagToWork(readerId, ownerWork.id(), "forbidden"));
            assertThrows(LibraryRepository.WorkNotFoundException.class,
                    () -> libraryService.createVolume(readerId, ownerWork.id(), 2, "Reader Volume"));
            assertThrows(LibraryRepository.WorkNotFoundException.class,
                    () -> libraryService.createChapter(
                            readerId, ownerWork.id(), null, 2, "Reader Chapter", "vi"));
            assertThrows(LibraryRepository.ChapterNotFoundException.class,
                    () -> libraryService.appendChapterRevision(
                            readerId, chapter.id(), "plain_text", "Unauthorized edit", List.of()));
            assertThrows(LibraryRepository.LibraryNotFoundException.class,
                    () -> libraryService.registerAsset(readerId, workspaceLibrary.id(),
                            "workspace/reader-forbidden", sha256("reader-asset"),
                            "image/webp", 100, "reader.webp"));

            WorkspaceRepository.WorkspaceRecord otherWorkspace =
                    workspaceService.createWorkspace(outsiderId, "Separate Team");
            UUID createdOtherWorkspaceId = otherWorkspace.id();
            otherWorkspaceId = createdOtherWorkspaceId;
            LibraryRepository.LibraryRecord otherLibrary =
                    libraryService.createWorkspaceLibrary(outsiderId, createdOtherWorkspaceId, "Separate Shelf");
            LibraryRepository.WorkRecord otherWork = libraryService.createPrivateImportedWork(
                    outsiderId, otherLibrary.id(), "Separate Story", "Author", "vi",
                    sha256("separate-work"));
            assertTrue(libraryService.findOwnedWork(outsiderId, ownerWork.id()).isEmpty());
            assertTrue(libraryService.findOwnedWork(readerId, otherWork.id()).isEmpty());
            assertThrows(LibraryRepository.LibraryNotFoundException.class,
                    () -> libraryService.listWorks(
                            outsiderId, workspaceLibrary.id(), "", null, null, null, 10));
            assertThrows(LibraryRepository.LibraryNotFoundException.class,
                    () -> libraryService.movePersonalLibraryToWorkspace(
                            outsiderId, defaultLibrary.id(), createdOtherWorkspaceId));

            WorkspaceRepository.MemberRecord revoked = workspaceService.revokeMember(
                    ownerId, workspaceId, readerId);
            assertEquals(WorkspaceRepository.MemberStatus.REVOKED, revoked.status());
            assertTrue(libraryService.findOwnedWork(readerId, ownerWork.id()).isEmpty());
            assertFalse(libraryService.listLibraries(readerId).stream()
                    .anyMatch(library -> library.id().equals(workspaceLibrary.id())));
        } finally {
            deleteLibraryTestData(ownerId);
            deleteLibraryTestData(adminId);
            deleteLibraryTestData(editorId);
            deleteLibraryTestData(readerId);
            deleteLibraryTestData(outsiderId);
            if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
            if (otherWorkspaceId != null) deleteWorkspaceTestData(otherWorkspaceId);
            deleteSessionTestUser(ownerId);
            deleteSessionTestUser(adminId);
            deleteSessionTestUser(editorId);
            deleteSessionTestUser(readerId);
            deleteSessionTestUser(outsiderId);
        }
    }

    @Test
    void workspaceChangesInvalidateLibraryAndWorkCursors() throws Exception {
        UUID ownerId = createSessionTestUser("Cursor Scope Owner");
        UUID memberId = createSessionTestUser("Cursor Scope Member");
        UUID newcomerId = createSessionTestUser("Cursor Scope New Member");
        UUID workspaceId = null;
        try {
            WorkspaceRepository.WorkspaceRecord workspace =
                    workspaceService.createWorkspace(ownerId, "Cursor Scope Team");
            workspaceId = workspace.id();
            workspaceService.addMember(ownerId, workspaceId, memberId, WorkspaceRepository.Role.READER);

            LibraryRepository.LibraryRecord sharedLibrary = libraryService.createWorkspaceLibrary(
                    ownerId, workspaceId, "Cursor Shared Shelf");
            libraryService.createWorkspaceLibrary(ownerId, workspaceId, "Cursor Second Shelf");
            LibraryRepository.WorkRecord firstWork = libraryService.createPrivateImportedWork(
                    ownerId, sharedLibrary.id(), "First Shared Story", "Author", "vi", sha256("scope-first-work"));
            LibraryRepository.WorkRecord secondWork = libraryService.createPrivateImportedWork(
                    ownerId, sharedLibrary.id(), "Second Shared Story", "Author", "vi", sha256("scope-second-work"));
            setWorkCreatedAt(firstWork.id(), Instant.parse("2026-02-02T00:00:00Z"));
            setWorkCreatedAt(secondWork.id(), Instant.parse("2026-02-01T00:00:00Z"));

            LibraryService.Page<LibraryRepository.WorkRecord> beforeRoleChange = libraryService.listWorks(
                    memberId, sharedLibrary.id(), "", null, null, null, 1);
            assertTrue(beforeRoleChange.nextCursor() != null);
            workspaceService.changeMemberRole(
                    ownerId, workspaceId, memberId, WorkspaceRepository.Role.EDITOR);
            assertThrows(LibraryCursorCodec.InvalidCursorException.class,
                    () -> libraryService.listWorks(
                            memberId, sharedLibrary.id(), "", null, null, beforeRoleChange.nextCursor(), 1));

            LibraryService.Page<LibraryRepository.WorkRecord> beforeTransfer = libraryService.listWorks(
                    memberId, sharedLibrary.id(), "", null, null, null, 1);
            workspaceService.transferOwnership(ownerId, workspaceId, memberId);
            assertThrows(LibraryCursorCodec.InvalidCursorException.class,
                    () -> libraryService.listWorks(
                            memberId, sharedLibrary.id(), "", null, null, beforeTransfer.nextCursor(), 1));

            LibraryService.Page<LibraryRepository.WorkRecord> beforeRevocation = libraryService.listWorks(
                    ownerId, sharedLibrary.id(), "", null, null, null, 1);
            workspaceService.revokeMember(memberId, workspaceId, ownerId);
            assertThrows(LibraryRepository.LibraryNotFoundException.class,
                    () -> libraryService.listWorks(
                            ownerId, sharedLibrary.id(), "", null, null, beforeRevocation.nextCursor(), 1));
            workspaceService.addMember(memberId, workspaceId, ownerId, WorkspaceRepository.Role.READER);
            assertThrows(LibraryCursorCodec.InvalidCursorException.class,
                    () -> libraryService.listWorks(
                            ownerId, sharedLibrary.id(), "", null, null, beforeRevocation.nextCursor(), 1));

            LibraryService.Page<LibraryRepository.LibraryRecord> beforeJoin =
                    libraryService.listLibraries(memberId, null, 1);
            assertTrue(beforeJoin.nextCursor() != null);
            workspaceService.addMember(memberId, workspaceId, newcomerId, WorkspaceRepository.Role.READER);
            assertThrows(LibraryCursorCodec.InvalidCursorException.class,
                    () -> libraryService.listLibraries(memberId, beforeJoin.nextCursor(), 1));

            LibraryRepository.LibraryRecord memberDefault = libraryService.createLibrary(memberId, "Cursor Personal");
            LibraryRepository.LibraryRecord movableLibrary = libraryService.createLibrary(memberId, "Cursor Movable");
            LibraryRepository.WorkRecord personalFirst = libraryService.createPrivateImportedWork(
                    memberId, movableLibrary.id(), "Personal First", "Author", "vi", sha256("scope-personal-first"));
            LibraryRepository.WorkRecord personalSecond = libraryService.createPrivateImportedWork(
                    memberId, movableLibrary.id(), "Personal Second", "Author", "vi", sha256("scope-personal-second"));
            setWorkCreatedAt(personalFirst.id(), Instant.parse("2026-03-02T00:00:00Z"));
            setWorkCreatedAt(personalSecond.id(), Instant.parse("2026-03-01T00:00:00Z"));
            LibraryService.Page<LibraryRepository.WorkRecord> beforeLibraryMove = libraryService.listWorks(
                    memberId, movableLibrary.id(), "", null, null, null, 1);
            assertTrue(beforeLibraryMove.nextCursor() != null);
            assertTrue(memberDefault.isDefault());
            libraryService.movePersonalLibraryToWorkspace(memberId, movableLibrary.id(), workspaceId);
            assertThrows(LibraryCursorCodec.InvalidCursorException.class,
                    () -> libraryService.listWorks(
                            memberId, movableLibrary.id(), "", null, null, beforeLibraryMove.nextCursor(), 1));
        } finally {
            deleteLibraryTestData(ownerId);
            deleteLibraryTestData(memberId);
            deleteLibraryTestData(newcomerId);
            if (workspaceId != null) deleteWorkspaceTestData(workspaceId);
            deleteSessionTestUser(ownerId);
            deleteSessionTestUser(memberId);
            deleteSessionTestUser(newcomerId);
        }
    }

    @Test
    void currentPolicyRouteReturnsTheLatestEffectiveVersionAndSameOriginDocuments() throws Exception {
        MvcResult current = mockMvc.perform(get("/api/v1/auth/policies/current")).andReturn();
        assertEquals(200, current.getResponse().getStatus());
        assertTrue(current.getResponse().getHeader("Cache-Control").contains("no-store"));
        assertEquals("no-cache", current.getResponse().getHeader("Pragma"));
        JsonNode policies = objectMapper.readTree(current.getResponse().getContentAsString());
        assertEquals("terms-current-1", policies.path("terms").path("version").asText());
        assertEquals("privacy-current-1", policies.path("privacyNotice").path("version").asText());
        assertTrue(Instant.parse(policies.path("terms").path("effectiveAt").asText()).isBefore(Instant.now()));
        assertEquals("/api/v1/auth/policies/terms/terms-current-1",
                policies.path("terms").path("documentUrl").asText());
        assertEquals("/api/v1/auth/policies/privacy-notice/privacy-current-1",
                policies.path("privacyNotice").path("documentUrl").asText());

        MvcResult document = mockMvc.perform(get("/api/v1/auth/policies/terms/terms-current-1")).andReturn();
        assertEquals(200, document.getResponse().getStatus());
        assertTrue(document.getResponse().getContentType().startsWith("text/markdown"));
        assertTrue(document.getResponse().getHeader("Cache-Control").contains("max-age=3600"));
        assertEquals("nosniff", document.getResponse().getHeader("X-Content-Type-Options"));
        assertTrue(document.getResponse().getContentAsString().contains("Synthetic test document"));

        MvcResult unknownVersion = mockMvc.perform(get("/api/v1/auth/policies/terms/missing-version")).andReturn();
        assertEquals(404, unknownVersion.getResponse().getStatus());
        assertTrue(unknownVersion.getResponse().getContentAsString().contains("POLICY_DOCUMENT_NOT_FOUND"));

        MvcResult futureVersion = mockMvc.perform(get("/api/v1/auth/policies/terms/terms-future-2")).andReturn();
        assertEquals(404, futureVersion.getResponse().getStatus());
        assertTrue(futureVersion.getResponse().getContentAsString().contains("POLICY_DOCUMENT_NOT_FOUND"));
    }

    @Test
    void policyAcceptanceRequiresBothCurrentExplicitChoicesIsAtomicAndIdempotent() throws Exception {
        UUID userId = createSessionTestUser("Policy Acceptance Reader");
        try {
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
            String csrfToken = csrfTokenService.tokenFor(session.cookieValue());
            jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie(
                    "__Host-novel_session", session.cookieValue());

            MvcResult missingChoice = mockMvc.perform(post("/api/v1/auth/policies/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "termsVersion", "terms-current-1",
                                    "privacyNoticeVersion", "privacy-current-1",
                                    "termsAccepted", true)))
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(cookie))
                    .andReturn();
            assertEquals(422, missingChoice.getResponse().getStatus());
            assertTrue(missingChoice.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
            assertEquals(0, policyAcceptanceCount(userId));

            MvcResult stale = mockMvc.perform(post("/api/v1/auth/policies/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "termsVersion", "terms-old",
                                    "privacyNoticeVersion", "privacy-current-1",
                                    "termsAccepted", true,
                                    "privacyNoticeAcknowledged", true)))
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(cookie))
                    .andReturn();
            assertEquals(409, stale.getResponse().getStatus());
            JsonNode staleProblem = objectMapper.readTree(stale.getResponse().getContentAsString());
            assertEquals("POLICY_VERSION_STALE", staleProblem.path("code").asText());
            assertEquals("terms-current-1", staleProblem.path("currentTermsVersion").asText());
            assertEquals(0, policyAcceptanceCount(userId));

            Map<String, Object> acceptedBody = Map.of(
                    "termsVersion", "terms-current-1",
                    "privacyNoticeVersion", "privacy-current-1",
                    "termsAccepted", true,
                    "privacyNoticeAcknowledged", true);
            MvcResult accepted = mockMvc.perform(post("/api/v1/auth/policies/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(acceptedBody))
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(cookie))
                    .andReturn();
            assertEquals(200, accepted.getResponse().getStatus());
            assertTrue(accepted.getResponse().getHeader("Cache-Control").contains("no-store"));
            JsonNode acceptance = objectMapper.readTree(accepted.getResponse().getContentAsString());
            Instant termsAcceptedAt = Instant.parse(acceptance.path("termsAcceptedAt").asText());
            Instant privacyAcknowledgedAt = Instant.parse(
                    acceptance.path("privacyNoticeAcknowledgedAt").asText());
            assertTrue(termsAcceptedAt.isBefore(Instant.now().plusSeconds(1)));
            assertTrue(privacyAcknowledgedAt.isBefore(Instant.now().plusSeconds(1)));
            assertEquals(2, policyAcceptanceCount(userId));
            assertEquals(2, policyAcceptanceCountBySource(userId, "web-onboarding"));

            MvcResult retry = mockMvc.perform(post("/api/v1/auth/policies/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(acceptedBody))
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(cookie))
                    .andReturn();
            assertEquals(200, retry.getResponse().getStatus());
            JsonNode retriedAcceptance = objectMapper.readTree(retry.getResponse().getContentAsString());
            assertEquals(acceptance.path("termsAcceptedAt").asText(),
                    retriedAcceptance.path("termsAcceptedAt").asText());
            assertEquals(acceptance.path("privacyNoticeAcknowledgedAt").asText(),
                    retriedAcceptance.path("privacyNoticeAcknowledgedAt").asText());
            assertEquals(2, policyAcceptanceCount(userId));

            MvcResult currentUser = mockMvc.perform(get("/api/v1/auth/me").cookie(cookie)).andReturn();
            assertTrue(objectMapper.readTree(currentUser.getResponse().getContentAsString())
                    .path("policy").path("requiredAcceptanceComplete").asBoolean());
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void policyRoutesFailClosedUntilBothApprovedDocumentsAreProvisioned() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement deleteDocuments = connection.prepareStatement("DELETE FROM policy_documents")) {
            assertEquals(5, deleteDocuments.executeUpdate());
        }

        UUID userId = createSessionTestUser("Unconfigured Policy Reader");
        try {
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
            String csrfToken = csrfTokenService.tokenFor(session.cookieValue());
            MvcResult current = mockMvc.perform(get("/api/v1/auth/policies/current")).andReturn();
            assertEquals(503, current.getResponse().getStatus());
            assertTrue(current.getResponse().getContentAsString().contains("POLICY_NOT_CONFIGURED"));

            MvcResult acceptance = mockMvc.perform(post("/api/v1/auth/policies/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "termsVersion", "terms-current-1",
                                    "privacyNoticeVersion", "privacy-current-1",
                                    "termsAccepted", true,
                                    "privacyNoticeAcknowledged", true)))
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(new jakarta.servlet.http.Cookie(
                                    "__Host-novel_session", session.cookieValue())))
                    .andReturn();
            assertEquals(503, acceptance.getResponse().getStatus());
            assertTrue(acceptance.getResponse().getContentAsString().contains("POLICY_NOT_CONFIGURED"));
            assertEquals(0, policyAcceptanceCount(userId));
        } finally {
            deleteSessionTestUser(userId);
            insertPolicyTestDocuments();
        }
    }

    @AfterAll
    void integrationFixturesAreRemoved() throws SQLException {
        for (String table : List.of(
                "users",
                "external_identities",
                "sessions",
                "google_auth_attempts",
                "google_auth_bootstrap_rate_limits",
                "policy_acceptances",
                "auth_events",
                "session_revoke_confirmations",
                "workspace_members",
                "workspaces",
                "library_collection_works",
                "library_reading_states",
                "library_collections",
                "libraries",
                "works",
                "volumes",
                "chapters",
                "chapter_revisions",
                "segments",
                "assets",
                "tags",
                "work_tags")) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + table);
                 ResultSet rows = count.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "integration fixtures remain in " + table);
            }
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement deleteDocuments = connection.prepareStatement("DELETE FROM policy_documents")) {
            deleteDocuments.executeUpdate();
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM policy_documents");
             ResultSet rows = count.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1), "policy document test fixtures remain");
        }
    }

    @Test
    void verifiedEmailIsCaseInsensitiveUniqueButCanBeReusedAfterDeletion() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertUser(connection, "First Reader", "Reader@example.test", "active", false);

                Savepoint beforeDuplicate = connection.setSavepoint();
                SQLException duplicateEmail = assertThrows(SQLException.class,
                        () -> insertUser(connection, "Second Reader", "reader@example.test", "active", false));
                assertEquals("23505", duplicateEmail.getSQLState(), "expected unique-index violation");
                connection.rollback(beforeDuplicate);

                insertUser(connection, "Deleted Reader", "reader@example.test", "deleted", true);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void authAttemptRejectsProtocolRelativeReturnPath() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO google_auth_attempts (id, nonce_hash, safe_return_path, expires_at, correlation_id)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setBytes(2, new byte[32]);
                insert.setString(3, "//attacker.example/collect");
                insert.setTimestamp(4, Timestamp.from(Instant.now().plusSeconds(300)));
                insert.setObject(5, UUID.randomUUID());

                SQLException invalidPath = assertThrows(SQLException.class, insert::executeUpdate);
                assertEquals("23514", invalidPath.getSQLState(), "expected CHECK constraint violation");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void googleAuthAttemptStoresOnlyHashedNonceAndCorrelatesTheSafePath() throws Exception {
        Instant beforeCreate = Instant.now();
        UUID correlationId = UUID.randomUUID();

        GoogleAuthAttemptService.CreatedAttempt attempt = googleAuthAttemptService.create(
                "/app/reader?work=private-123", correlationId);
        byte[] nonceHash = sha256(attempt.nonce());
        try {
            assertEquals(43, attempt.nonce().length(), "32 random bytes should use unpadded base64url encoding");
            assertEquals("/app/reader?work=private-123", attempt.returnTo());
            assertEquals(correlationId, attempt.correlationId());
            long secondsUntilExpiry = java.time.Duration.between(beforeCreate, attempt.expiresAt()).toSeconds();
            assertTrue(secondsUntilExpiry >= 290 && secondsUntilExpiry <= 300,
                    "new attempts should expire in approximately five minutes");

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement query = connection.prepareStatement("""
                         SELECT nonce_hash, safe_return_path, correlation_id,
                                expires_at > created_at AS expires_after_creation
                         FROM google_auth_attempts
                         WHERE nonce_hash = ?
                         """)) {
                query.setBytes(1, nonceHash);
                try (ResultSet storedAttempt = query.executeQuery()) {
                    assertTrue(storedAttempt.next(), "the auth attempt must be persisted");
                    byte[] storedHash = storedAttempt.getBytes("nonce_hash");
                    assertEquals(32, storedHash.length);
                    assertEquals("/app/reader?work=private-123", storedAttempt.getString("safe_return_path"));
                    assertEquals(correlationId, storedAttempt.getObject("correlation_id", UUID.class));
                    assertTrue(storedAttempt.getBoolean("expires_after_creation"));
                    assertFalse(java.util.Arrays.equals(attempt.nonce().getBytes(StandardCharsets.UTF_8), storedHash));
                }
            }
        } finally {
            deleteAuthAttempt(nonceHash);
        }
    }

    @Test
    void googleAuthAttemptNoncesAreUniqueAndUnsafeReturnPathsFallBackToHome() throws Exception {
        Set<String> nonces = new HashSet<>();
        try {
            for (int index = 0; index < 16; index++) {
                GoogleAuthAttemptService.CreatedAttempt attempt = googleAuthAttemptService.create(
                        "//attacker.example/collect", UUID.randomUUID());
                assertTrue(nonces.add(attempt.nonce()), "generated auth attempt nonces must be unique");
                assertEquals("/app/home", attempt.returnTo());
            }
        } finally {
            for (String nonce : nonces) {
                deleteAuthAttempt(sha256(nonce));
            }
        }
    }

    @Test
    void googleAuthAttemptIsConsumedAtMostOnce() throws Exception {
        GoogleAuthAttemptService.CreatedAttempt attempt = googleAuthAttemptService.create(
                "/app/library", UUID.randomUUID());
        byte[] nonceHash = sha256(attempt.nonce());
        try {
            Optional<GoogleAuthAttemptService.ConsumedAttempt> consumed =
                    googleAuthAttemptService.consumeOnce(attempt.nonce());

            assertTrue(consumed.isPresent());
            assertEquals("/app/library", consumed.orElseThrow().returnTo());
            assertEquals(attempt.correlationId(), consumed.orElseThrow().correlationId());
            assertTrue(googleAuthAttemptService.consumeOnce(attempt.nonce()).isEmpty(),
                    "a replay must not consume the same attempt twice");
            assertTrue(googleAuthAttemptService.consumeOnce("not-a-valid-nonce").isEmpty());
        } finally {
            deleteAuthAttempt(nonceHash);
        }
    }

    @Test
    void expiredGoogleAuthAttemptCannotBeConsumed() throws Exception {
        GoogleAuthAttemptService.CreatedAttempt attempt = googleAuthAttemptService.create(
                "/app/home", UUID.randomUUID());
        byte[] nonceHash = sha256(attempt.nonce());
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement expire = connection.prepareStatement("""
                         UPDATE google_auth_attempts
                         SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 millisecond'
                         WHERE nonce_hash = ?
                         """)) {
                expire.setBytes(1, nonceHash);
                assertEquals(1, expire.executeUpdate());
            }

            assertTrue(googleAuthAttemptService.consumeOnce(attempt.nonce()).isEmpty(),
                    "an expired attempt must not return its stored path");
        } finally {
            deleteAuthAttempt(nonceHash);
        }
    }

    @Test
    void concurrentGoogleAuthAttemptConsumersHaveOnlyOneWinner() throws Exception {
        GoogleAuthAttemptService.CreatedAttempt attempt = googleAuthAttemptService.create(
                "/app/reader", UUID.randomUUID());
        byte[] nonceHash = sha256(attempt.nonce());
        int consumers = 5;
        ExecutorService executor = Executors.newFixedThreadPool(consumers);
        CountDownLatch ready = new CountDownLatch(consumers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<GoogleAuthAttemptService.ConsumedAttempt>>> results = new ArrayList<>();

        try {
            for (int index = 0; index < consumers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("consumer start signal timed out");
                    }
                    return googleAuthAttemptService.consumeOnce(attempt.nonce());
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "all consumers should reach the start barrier");
            start.countDown();
            long winners = 0;
            for (Future<Optional<GoogleAuthAttemptService.ConsumedAttempt>> result : results) {
                if (result.get(10, TimeUnit.SECONDS).isPresent()) {
                    winners++;
                }
            }
            assertEquals(1, winners, "the database compare-and-set should admit one concurrent consumer");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            deleteAuthAttempt(nonceHash);
        }
    }

    @Test
    void googleAuthBootstrapRateLimiterAllowsTenRequestsThenRefillsOneToken() throws Exception {
        byte[] rateLimitKeyHash = sha256(UUID.randomUUID().toString());
        try {
            for (int request = 0; request < 10; request++) {
                assertTrue(googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash),
                        "the initial bucket should allow ten bootstrap requests");
            }
            assertFalse(googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash),
                    "the eleventh request should be throttled");

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement refill = connection.prepareStatement("""
                         UPDATE google_auth_bootstrap_rate_limits
                         SET available_tokens = 0,
                             refilled_at = clock_timestamp() - INTERVAL '24 hours'
                         WHERE rate_limit_key_hash = ?
                         """)) {
                refill.setBytes(1, rateLimitKeyHash);
                assertEquals(1, refill.executeUpdate());
            }

            assertTrue(googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash),
                    "a full configured refill interval should restore one request");
            assertFalse(googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash),
                    "the refilled token should be consumed once");
        } finally {
            deleteRateLimitBucket(rateLimitKeyHash);
        }
    }

    @Test
    void googleBootstrapHttpEndpointReturnsFreshSafeAttempt() throws Exception {
        String clientAddress = bootstrapTestClientAddress();
        List<UUID> correlationIds = new ArrayList<>();
        try {
            MvcResult result = mockMvc.perform(get("/api/v1/auth/google/bootstrap")
                            .param("returnTo", "/library/recent?sort=updated")
                            .param("locale", "en")
                            .with(request -> {
                                request.setRemoteAddr(clientAddress);
                                return request;
                            }))
                    .andReturn();
            UUID correlationId = UUID.fromString(result.getResponse().getHeader("X-Correlation-Id"));
            correlationIds.add(correlationId);

            assertEquals(200, result.getResponse().getStatus());
            assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
            assertEquals("no-cache", result.getResponse().getHeader("Pragma"));
            assertNull(result.getResponse().getHeader("Set-Cookie"));

            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertEquals("novel-web-integration-test.apps.googleusercontent.com", body.path("clientId").asText());
            assertEquals("https://api.example.test/api/v1/auth/google/credential", body.path("loginUri").asText());
            assertTrue(body.path("nonce").asText().matches("[A-Za-z0-9_-]{43}"));
            assertEquals("en", body.path("locale").asText());
            assertTrue(Instant.parse(body.path("attemptExpiresAt").asText()).isAfter(Instant.now()));
            assertEquals("/library/recent?sort=updated", storedBootstrapReturnPath(body.path("nonce").asText()));
            assertEquals(1, googleAuthAttemptCount(correlationId));
        } finally {
            deleteBootstrapFixtures(clientAddress, correlationIds);
        }
    }

    @Test
    void googleCredentialCallbackRejectsMissingCsrfCookieBeforeDecodingCredential() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/google/credential")
                        .header("Origin", "https://app.example.test")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("credential=synthetic-invalid-token&g_csrf_token=synthetic-csrf-token"))
                .andReturn();

        assertEquals(401, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentType().startsWith("application/problem+json"));
        assertTrue(result.getResponse().getContentAsString().contains("SIGN_IN_FAILED"));
        assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
        verify(jwtDecoder, never()).decode(anyString());
    }

    @Test
    void googleCredentialCallbackRejectsMissingOriginAndUnsupportedMediaType() throws Exception {
        MvcResult missingOrigin = mockMvc.perform(post("/api/v1/auth/google/credential")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("credential=synthetic-invalid-token&g_csrf_token=synthetic-csrf-token"))
                .andReturn();
        assertEquals(403, missingOrigin.getResponse().getStatus());
        assertTrue(missingOrigin.getResponse().getContentAsString().contains("INVALID_ORIGIN"));

        MvcResult wrongContentType = mockMvc.perform(post("/api/v1/auth/google/credential")
                        .header("Origin", "https://app.example.test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertEquals(415, wrongContentType.getResponse().getStatus());
        assertTrue(wrongContentType.getResponse().getContentAsString().contains("UNSUPPORTED_MEDIA_TYPE"));
        verify(jwtDecoder, never()).decode(anyString());
    }

    @Test
    void googleCredentialCallbackRejectsOversizedAndDuplicateCredentialForms() throws Exception {
        String oversized = "x".repeat(24 * 1024 + 1);
        MvcResult tooLarge = mockMvc.perform(post("/api/v1/auth/google/credential")
                        .header("Origin", "https://app.example.test")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(oversized))
                .andReturn();
        assertEquals(413, tooLarge.getResponse().getStatus());

        MvcResult duplicate = mockMvc.perform(post("/api/v1/auth/google/credential")
                        .header("Origin", "https://app.example.test")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("credential=first&credential=second&g_csrf_token=csrf-token")
                        .cookie(new jakarta.servlet.http.Cookie("g_csrf_token", "csrf-token")))
                .andReturn();
        assertEquals(400, duplicate.getResponse().getStatus());
        verify(jwtDecoder, never()).decode(anyString());
    }

    @Test
    void googleCredentialCallbackCreatesIdentitySessionAndRedirectsToStoredPath() throws Exception {
        String subject = "callback-" + UUID.randomUUID();
        String email = subject + "@example.test";
        GoogleAuthAttemptService.CreatedAttempt attempt = googleAuthAttemptService.create(
                "/library/recent?sort=updated", UUID.randomUUID());
        when(jwtDecoder.decode("synthetic-google-id-token"))
                .thenReturn(googleJwt(attempt.nonce(), subject, email));

        try {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/google/credential")
                            .header("Origin", "https://app.example.test")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("credential=synthetic-google-id-token&g_csrf_token=csrf-token")
                            .cookie(new jakarta.servlet.http.Cookie("g_csrf_token", "csrf-token")))
                    .andReturn();

            assertEquals(303, result.getResponse().getStatus());
            assertEquals("/library/recent?sort=updated", result.getResponse().getHeader("Location"));
            assertTrue(result.getResponse().getHeader("Cache-Control").contains("no-store"));
            assertEquals("no-cache", result.getResponse().getHeader("Pragma"));
            assertEquals("no-referrer", result.getResponse().getHeader("Referrer-Policy"));
            assertEquals("", result.getResponse().getContentAsString());

            String setCookie = result.getResponse().getHeader("Set-Cookie");
            assertTrue(setCookie.startsWith("__Host-novel_session="));
            assertTrue(setCookie.contains("Path=/"));
            assertTrue(setCookie.contains("Secure"));
            assertTrue(setCookie.contains("HttpOnly"));
            assertTrue(setCookie.contains("SameSite=Lax"));
            String sessionToken = setCookie.substring("__Host-novel_session=".length(), setCookie.indexOf(';'));
            assertTrue(sessionToken.matches("[A-Za-z0-9_-]{43}"));

            UUID userId = userIdForGoogleSubject(subject);
            assertEquals(1, identityCount(subject));
            assertTrue(sessionHashMatches(sessionToken, userId), "only the cookie-token hash is stored");
            assertEquals(userId, sessionService.validateAndTouch(sessionToken).orElseThrow().userId());
        } finally {
            deleteAuthAttempt(sha256(attempt.nonce()));
            deleteGoogleSignInFixtures(subject);
        }
    }

    @Test
    void googleCredentialCallbackRotatesAnExistingSessionForTheSameAccount() throws Exception {
        String subject = "rotate-callback-" + UUID.randomUUID();
        String email = subject + "@example.test";
        GoogleIdentityService.IdentityAccount account = googleIdentityService.findOrCreate(
                verifiedIdentity(subject, email, "Callback Reader"));
        SessionService.IssuedSession previous = sessionService.create(account.userId()).orElseThrow();
        GoogleAuthAttemptService.CreatedAttempt attempt = googleAuthAttemptService.create(
                "/app/home", UUID.randomUUID());
        when(jwtDecoder.decode("synthetic-google-id-token"))
                .thenReturn(googleJwt(attempt.nonce(), subject, email));

        try {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/google/credential")
                            .header("Origin", "https://app.example.test")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("credential=synthetic-google-id-token&g_csrf_token=csrf-token")
                            .cookie(
                                    new jakarta.servlet.http.Cookie("g_csrf_token", "csrf-token"),
                                    new jakarta.servlet.http.Cookie("__Host-novel_session", previous.cookieValue())))
                    .andReturn();

            assertEquals(303, result.getResponse().getStatus());
            String setCookie = result.getResponse().getHeader("Set-Cookie");
            String rotatedToken = setCookie.substring("__Host-novel_session=".length(), setCookie.indexOf(';'));
            assertNotEquals(previous.cookieValue(), rotatedToken);
            assertTrue(sessionService.validateAndTouch(previous.cookieValue()).isEmpty());
            assertEquals(account.userId(), sessionService.validateAndTouch(rotatedToken).orElseThrow().userId());
            assertTrue(sessionHashMatches(rotatedToken, account.userId()));
            assertEquals(1, activeSessionCount(account.userId()));
        } finally {
            deleteAuthAttempt(sha256(attempt.nonce()));
            deleteGoogleSignInFixtures(subject);
        }
    }

    @Test
    void googleCredentialCallbackRevokesThePreviousAccountSessionOnAccountSwitch() throws Exception {
        String previousSubject = "previous-callback-" + UUID.randomUUID();
        String previousEmail = previousSubject + "@example.test";
        GoogleIdentityService.IdentityAccount previousAccount = googleIdentityService.findOrCreate(
                verifiedIdentity(previousSubject, previousEmail, "Previous Reader"));
        SessionService.IssuedSession previous = sessionService.create(previousAccount.userId()).orElseThrow();

        String nextSubject = "next-callback-" + UUID.randomUUID();
        String nextEmail = nextSubject + "@example.test";
        GoogleAuthAttemptService.CreatedAttempt attempt = googleAuthAttemptService.create(
                "/app/home", UUID.randomUUID());
        when(jwtDecoder.decode("synthetic-google-id-token"))
                .thenReturn(googleJwt(attempt.nonce(), nextSubject, nextEmail));

        try {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/google/credential")
                            .header("Origin", "https://app.example.test")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("credential=synthetic-google-id-token&g_csrf_token=csrf-token")
                            .cookie(
                                    new jakarta.servlet.http.Cookie("g_csrf_token", "csrf-token"),
                                    new jakarta.servlet.http.Cookie("__Host-novel_session", previous.cookieValue())))
                    .andReturn();

            assertEquals(303, result.getResponse().getStatus());
            String setCookie = result.getResponse().getHeader("Set-Cookie");
            String nextToken = setCookie.substring("__Host-novel_session=".length(), setCookie.indexOf(';'));
            UUID nextUserId = userIdForGoogleSubject(nextSubject);
            assertTrue(sessionService.validateAndTouch(previous.cookieValue()).isEmpty());
            assertEquals(nextUserId, sessionService.validateAndTouch(nextToken).orElseThrow().userId());
            assertEquals(0, activeSessionCount(previousAccount.userId()));
            assertEquals(1, activeSessionCount(nextUserId));
        } finally {
            deleteAuthAttempt(sha256(attempt.nonce()));
            deleteGoogleSignInFixtures(previousSubject);
            deleteGoogleSignInFixtures(nextSubject);
        }
    }

    @Test
    void csrfTokenRouteRequiresSessionAndChangesWhenSessionRotates() throws Exception {
        MvcResult unauthenticated = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn();
        assertEquals(401, unauthenticated.getResponse().getStatus());
        assertTrue(unauthenticated.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

        UUID userId = createSessionTestUser("CSRF Route Reader");
        try {
            SessionService.IssuedSession original = sessionService.create(userId).orElseThrow();
            MvcResult first = mockMvc.perform(get("/api/v1/auth/csrf")
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", original.cookieValue())))
                    .andReturn();

            assertEquals(200, first.getResponse().getStatus());
            assertTrue(first.getResponse().getHeader("Cache-Control").contains("no-store"));
            assertEquals("no-cache", first.getResponse().getHeader("Pragma"));
            assertNull(first.getResponse().getHeader("Set-Cookie"));
            JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
            String firstToken = firstBody.path("token").asText();
            assertTrue(firstToken.matches("[A-Za-z0-9_-]{43}"));
            assertTrue(csrfTokenService.matches(original.cookieValue(), firstToken));
            assertEquals(original.expiresAt(), Instant.parse(firstBody.path("expiresAt").asText()));

            SessionService.IssuedSession rotated = sessionService.rotate(original.cookieValue()).orElseThrow();
            MvcResult second = mockMvc.perform(get("/api/v1/auth/csrf")
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", rotated.cookieValue())))
                    .andReturn();
            JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
            String secondToken = secondBody.path("token").asText();
            assertEquals(200, second.getResponse().getStatus());
            assertNotEquals(firstToken, secondToken);
            assertTrue(csrfTokenService.matches(rotated.cookieValue(), secondToken));

            MvcResult revokedMutation = mockMvc.perform(post("/api/v1/auth/policies/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-CSRF-Token", firstToken)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", original.cookieValue())))
                    .andReturn();
            assertEquals(401, revokedMutation.getResponse().getStatus());
            assertTrue(revokedMutation.getResponse().getContentAsString().contains("UNAUTHENTICATED"));
            assertFalse(revokedMutation.getResponse().getContentAsString().contains("CSRF_FAILED"));
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void cookieAuthenticatedMutationRequiresTheSessionBoundCsrfHeader() throws Exception {
        UUID userId = createSessionTestUser("CSRF Mutation Reader");
        try {
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
            String csrfToken = csrfTokenService.tokenFor(session.cookieValue());

            MvcResult invalid = mockMvc.perform(post("/api/v1/auth/policies/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-CSRF-Token", "invalid-token")
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();
            assertEquals(403, invalid.getResponse().getStatus());
            assertTrue(invalid.getResponse().getContentAsString().contains("CSRF_FAILED"));

            MvcResult validCsrf = mockMvc.perform(post("/api/v1/auth/policies/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();
            assertEquals(422, validCsrf.getResponse().getStatus());
            assertTrue(validCsrf.getResponse().getContentAsString().contains("POLICY_ACCEPTANCE_REQUIRED"));
            assertFalse(validCsrf.getResponse().getContentAsString().contains("CSRF_FAILED"));
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void currentUserRequiresSessionAndReturnsOnlyTheSessionOwnersCurrentProfile() throws Exception {
        MvcResult unauthenticated = mockMvc.perform(get("/api/v1/auth/me")).andReturn();
        assertEquals(401, unauthenticated.getResponse().getStatus());
        assertTrue(unauthenticated.getResponse().getContentAsString().contains("UNAUTHENTICATED"));

        UUID userId = createSessionTestUser("Current User Reader");
        try {
            addSessionTestGoogleIdentity(userId);
            addPolicyAcceptance(userId, "TERMS", "terms-current-1", "TERMS_ACCEPTED");
            addPolicyAcceptance(userId, "PRIVACY_NOTICE", "privacy-current-1", "PRIVACY_NOTICE_ACKNOWLEDGED");
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();

            MvcResult authenticated = mockMvc.perform(get("/api/v1/auth/me")
                            .queryParam("userId", UUID.randomUUID().toString())
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();

            assertEquals(200, authenticated.getResponse().getStatus());
            assertTrue(authenticated.getResponse().getHeader("Cache-Control").contains("no-store"));
            assertEquals("no-cache", authenticated.getResponse().getHeader("Pragma"));
            assertNull(authenticated.getResponse().getHeader("Set-Cookie"));
            JsonNode body = objectMapper.readTree(authenticated.getResponse().getContentAsString());
            assertEquals(userId.toString(), body.path("user").path("id").asText());
            assertEquals("Current User Reader", body.path("user").path("displayName").asText());
            assertEquals("active", body.path("user").path("status").asText());
            assertEquals("session-" + userId + "@example.test", body.path("user").path("contactEmail").asText());
            assertTrue(body.path("user").path("avatarUrl").isNull());
            assertEquals(List.of("google"), objectMapper.convertValue(
                    body.path("identityProviders"), objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, String.class)));
            assertEquals("terms-current-1", body.path("policy").path("termsAcceptedVersion").asText());
            assertEquals("privacy-current-1", body.path("policy")
                    .path("privacyNoticeAcknowledgedVersion").asText());
            assertTrue(body.path("policy").path("requiredAcceptanceComplete").asBoolean());
            assertEquals("personal", body.path("roles").get(0).path("scope").asText());
            assertEquals(0, body.path("roles").get(0).path("permissions").size());
            assertFalse(body.path("features").path("privateLibraryEnabled").asBoolean());
            assertFalse(body.path("features").path("importEnabled").asBoolean());
            assertFalse(body.path("features").path("readerEnabled").asBoolean());
            assertFalse(body.path("features").path("cultivationEnabled").asBoolean());
            assertFalse(body.path("features").path("commerceSandboxEnabled").asBoolean());
            assertFalse(authenticated.getResponse().getContentAsString().contains(session.cookieValue()));
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void currentUserMarksOldPolicyVersionsIncomplete() throws Exception {
        UUID userId = createSessionTestUser("Old Policy Reader");
        try {
            addPolicyAcceptance(userId, "TERMS", "terms-old", "TERMS_ACCEPTED");
            addPolicyAcceptance(userId, "PRIVACY_NOTICE", "privacy-old", "PRIVACY_NOTICE_ACKNOWLEDGED");
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();

            MvcResult response = mockMvc.perform(get("/api/v1/auth/me")
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();

            assertEquals(200, response.getResponse().getStatus());
            JsonNode policy = objectMapper.readTree(response.getResponse().getContentAsString()).path("policy");
            assertEquals("terms-old", policy.path("termsAcceptedVersion").asText());
            assertEquals("privacy-old", policy.path("privacyNoticeAcknowledgedVersion").asText());
            assertFalse(policy.path("requiredAcceptanceComplete").asBoolean());
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void currentUserRejectsAUserWhoseAccountWasSuspendedAfterSessionIssue() throws Exception {
        UUID userId = createSessionTestUser("Suspended Reader");
        try {
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement suspend = connection.prepareStatement(
                         "UPDATE users SET status = 'suspended' WHERE id = ?")) {
                suspend.setObject(1, userId);
                assertEquals(1, suspend.executeUpdate());
            }

            MvcResult response = mockMvc.perform(get("/api/v1/auth/me")
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();
            assertEquals(401, response.getResponse().getStatus());
            assertTrue(response.getResponse().getContentAsString().contains("UNAUTHENTICATED"));
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void logoutRevokesTheSessionClearsTheCookieAndCanBeRepeated() throws Exception {
        UUID userId = createSessionTestUser("Logout Reader");
        try {
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
            String csrfToken = csrfTokenService.tokenFor(session.cookieValue());

            MvcResult first = mockMvc.perform(post("/api/v1/auth/logout")
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();
            assertEquals(204, first.getResponse().getStatus());
            assertTrue(first.getResponse().getHeader("Cache-Control").contains("no-store"));
            assertTrue(first.getResponse().getHeader("Set-Cookie").contains("__Host-novel_session="));
            assertTrue(first.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
            assertTrue(first.getResponse().getHeader("Set-Cookie").contains("Secure"));
            assertTrue(first.getResponse().getHeader("Set-Cookie").contains("HttpOnly"));
            assertTrue(first.getResponse().getHeader("Set-Cookie").contains("SameSite=Lax"));
            assertFalse(first.getResponse().getHeader("Set-Cookie").contains("Domain="));
            assertTrue(sessionService.validateAndTouch(session.cookieValue()).isEmpty());

            MvcResult repeated = mockMvc.perform(post("/api/v1/auth/logout")
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();
            assertEquals(204, repeated.getResponse().getStatus());
            assertTrue(repeated.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void logoutWithoutASessionIsIdempotentAndStillClearsCookie() throws Exception {
        MvcResult response = mockMvc.perform(post("/api/v1/auth/logout")).andReturn();
        assertEquals(204, response.getResponse().getStatus());
        assertTrue(response.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
    }

    @Test
    void logoutRejectsInvalidCsrfWithoutRevokingTheCurrentSession() throws Exception {
        UUID userId = createSessionTestUser("Logout CSRF Reader");
        try {
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
            MvcResult response = mockMvc.perform(post("/api/v1/auth/logout")
                            .header("X-CSRF-Token", "invalid-token")
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();

            assertEquals(403, response.getResponse().getStatus());
            assertTrue(response.getResponse().getContentAsString().contains("CSRF_FAILED"));
            assertNull(response.getResponse().getHeader("Set-Cookie"));
            assertTrue(sessionService.validateAndTouch(session.cookieValue()).isPresent());
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void logoutAllConfirmationIsNoStoreHashedBoundAndReplacedPerSession() throws Exception {
        MvcResult unauthenticated = mockMvc.perform(post("/api/v1/auth/logout-all/confirmation")).andReturn();
        assertEquals(403, unauthenticated.getResponse().getStatus());
        assertTrue(unauthenticated.getResponse().getContentAsString().contains("CSRF_FAILED"));

        UUID userId = createSessionTestUser("Logout All Confirmation Reader");
        try {
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
            String csrfToken = csrfTokenService.tokenFor(session.cookieValue());
            MvcResult first = mockMvc.perform(post("/api/v1/auth/logout-all/confirmation")
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();

            assertEquals(200, first.getResponse().getStatus());
            assertTrue(first.getResponse().getHeader("Cache-Control").contains("no-store"));
            assertEquals("no-cache", first.getResponse().getHeader("Pragma"));
            JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
            String firstToken = firstBody.path("confirmationToken").asText();
            assertTrue(firstToken.matches("[A-Za-z0-9_-]{43}"));
            Instant firstExpiry = Instant.parse(firstBody.path("expiresAt").asText());
            assertTrue(firstExpiry.isAfter(Instant.now()));
            assertTrue(firstExpiry.isBefore(Instant.now().plusSeconds(6 * 60)));
            assertTrue(confirmationHashStoredForUser(firstToken, userId, session.cookieValue()));

            MvcResult replacement = mockMvc.perform(post("/api/v1/auth/logout-all/confirmation")
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();
            assertEquals(200, replacement.getResponse().getStatus());
            String replacementToken = objectMapper.readTree(replacement.getResponse().getContentAsString())
                    .path("confirmationToken").asText();
            assertFalse(confirmationHashExists(firstToken));
            assertTrue(confirmationHashExists(replacementToken));
            assertEquals(1, activeSessionCount(userId));
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void logoutAllRejectsWrongUserOrSessionThenRevokesOnlyTheBoundUsersSessions() throws Exception {
        UUID userId = createSessionTestUser("Logout All Owner");
        UUID otherUserId = createSessionTestUser("Other Logout All Owner");
        try {
            SessionService.IssuedSession current = sessionService.create(userId).orElseThrow();
            SessionService.IssuedSession otherDevice = sessionService.create(userId).orElseThrow();
            SessionService.IssuedSession otherUserSession = sessionService.create(otherUserId).orElseThrow();
            String currentCsrf = csrfTokenService.tokenFor(current.cookieValue());
            MvcResult confirmationResponse = mockMvc.perform(post("/api/v1/auth/logout-all/confirmation")
                            .header("X-CSRF-Token", currentCsrf)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", current.cookieValue())))
                    .andReturn();
            String confirmationToken = objectMapper.readTree(confirmationResponse.getResponse().getContentAsString())
                    .path("confirmationToken").asText();

            MvcResult wrongSession = mockMvc.perform(post("/api/v1/auth/logout-all")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("confirmationToken", confirmationToken)))
                            .header("X-CSRF-Token", csrfTokenService.tokenFor(otherDevice.cookieValue()))
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", otherDevice.cookieValue())))
                    .andReturn();
            assertEquals(403, wrongSession.getResponse().getStatus());
            assertTrue(wrongSession.getResponse().getContentAsString().contains("LOGOUT_ALL_CONFIRMATION_INVALID"));

            MvcResult wrongUser = mockMvc.perform(post("/api/v1/auth/logout-all")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("confirmationToken", confirmationToken)))
                            .header("X-CSRF-Token", csrfTokenService.tokenFor(otherUserSession.cookieValue()))
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", otherUserSession.cookieValue())))
                    .andReturn();
            assertEquals(403, wrongUser.getResponse().getStatus());
            assertTrue(wrongUser.getResponse().getContentAsString().contains("LOGOUT_ALL_CONFIRMATION_INVALID"));
            assertEquals(2, activeSessionCount(userId));
            assertEquals(1, activeSessionCount(otherUserId));

            MvcResult success = mockMvc.perform(post("/api/v1/auth/logout-all")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("confirmationToken", confirmationToken)))
                            .header("X-CSRF-Token", currentCsrf)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", current.cookieValue())))
                    .andReturn();
            assertEquals(204, success.getResponse().getStatus());
            assertTrue(success.getResponse().getHeader("Cache-Control").contains("no-store"));
            assertTrue(success.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
            assertEquals(0, activeSessionCount(userId));
            assertEquals(1, activeSessionCount(otherUserId));

            MvcResult replay = mockMvc.perform(post("/api/v1/auth/logout-all")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("confirmationToken", confirmationToken)))
                            .header("X-CSRF-Token", currentCsrf)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", current.cookieValue())))
                    .andReturn();
            assertEquals(401, replay.getResponse().getStatus());
            assertEquals(1, activeSessionCount(otherUserId));
        } finally {
            deleteSessionTestUser(userId);
            deleteSessionTestUser(otherUserId);
        }
    }

    @Test
    void expiredLogoutAllConfirmationDoesNotRevokeSessions() throws Exception {
        UUID userId = createSessionTestUser("Expired Logout All Reader");
        try {
            SessionService.IssuedSession session = sessionService.create(userId).orElseThrow();
            String csrfToken = csrfTokenService.tokenFor(session.cookieValue());
            MvcResult confirmationResponse = mockMvc.perform(post("/api/v1/auth/logout-all/confirmation")
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();
            String confirmationToken = objectMapper.readTree(confirmationResponse.getResponse().getContentAsString())
                    .path("confirmationToken").asText();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement expire = connection.prepareStatement("""
                         UPDATE session_revoke_confirmations
                         SET created_at = clock_timestamp() - INTERVAL '2 seconds',
                             expires_at = clock_timestamp() - INTERVAL '1 second'
                         WHERE token_hash = ?
                         """)) {
                expire.setBytes(1, sessionTokenHash(confirmationToken));
                assertEquals(1, expire.executeUpdate());
            }

            MvcResult response = mockMvc.perform(post("/api/v1/auth/logout-all")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("confirmationToken", confirmationToken)))
                            .header("X-CSRF-Token", csrfToken)
                            .cookie(new jakarta.servlet.http.Cookie("__Host-novel_session", session.cookieValue())))
                    .andReturn();
            assertEquals(403, response.getResponse().getStatus());
            assertTrue(response.getResponse().getContentAsString().contains("LOGOUT_ALL_CONFIRMATION_INVALID"));
            assertNull(response.getResponse().getHeader("Set-Cookie"));
            assertEquals(1, activeSessionCount(userId));
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void concurrentLogoutAllRequestsConsumeConfirmationOnlyOnce() throws Exception {
        UUID userId = createSessionTestUser("Concurrent Logout All Reader");
        int requestCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            SessionService.IssuedSession current = sessionService.create(userId).orElseThrow();
            sessionService.create(userId).orElseThrow();
            LogoutAllConfirmationService.IssuedConfirmation confirmation = logoutAllConfirmationService
                    .issue(userId, current.cookieValue()).orElseThrow();
            for (int index = 0; index < requestCount; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent logout-all test did not start");
                    }
                    return logoutAllConfirmationService.consumeAndRevokeAll(
                            userId, current.cookieValue(), confirmation.token());
                }));
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get(20, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, winners);
            assertEquals(0, activeSessionCount(userId));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void googleBootstrapHttpEndpointStopsAtTenAttemptsPerClientAddress() throws Exception {
        String clientAddress = bootstrapTestClientAddress();
        List<UUID> correlationIds = new ArrayList<>();
        try {
            for (int index = 0; index < 11; index++) {
                MvcResult result = mockMvc.perform(get("/api/v1/auth/google/bootstrap")
                                .with(request -> {
                                    request.setRemoteAddr(clientAddress);
                                    return request;
                                }))
                        .andReturn();
                UUID correlationId = UUID.fromString(result.getResponse().getHeader("X-Correlation-Id"));
                correlationIds.add(correlationId);

                if (index < 10) {
                    assertEquals(200, result.getResponse().getStatus());
                    assertEquals(1, googleAuthAttemptCount(correlationId));
                } else {
                    assertEquals(429, result.getResponse().getStatus());
                    assertEquals(0, googleAuthAttemptCount(correlationId));
                    assertTrue(objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("detail").asText().contains("wait"));
                }
            }
        } finally {
            deleteBootstrapFixtures(clientAddress, correlationIds);
        }
    }

    @Test
    void concurrentGoogleAuthBootstrapRequestsCannotExceedBucketCapacity() throws Exception {
        byte[] rateLimitKeyHash = sha256(UUID.randomUUID().toString());
        int requests = 20;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            for (int index = 0; index < requests; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("rate-limit consumer start signal timed out");
                    }
                    return googleAuthBootstrapRateLimiter.tryAcquire(rateLimitKeyHash);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS), "all rate-limit requests should reach the start barrier");
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(15, TimeUnit.SECONDS)) {
                    winners++;
                }
            }
            assertEquals(10, winners, "the locked PostgreSQL token bucket should admit exactly its capacity");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            deleteRateLimitBucket(rateLimitKeyHash);
        }
    }

    @Test
    void inactiveGoogleAuthRateLimitBucketsCanBePurged() throws Exception {
        byte[] staleKeyHash = sha256(UUID.randomUUID().toString());
        byte[] activeKeyHash = sha256(UUID.randomUUID().toString());
        try {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement insert = connection.prepareStatement("""
                         INSERT INTO google_auth_bootstrap_rate_limits (
                             rate_limit_key_hash, available_tokens, refilled_at, last_seen_at)
                         VALUES (?, 0, clock_timestamp(), clock_timestamp() - INTERVAL '25 hours'),
                                (?, 4, clock_timestamp(), clock_timestamp())
                         """)) {
                insert.setBytes(1, staleKeyHash);
                insert.setBytes(2, activeKeyHash);
                assertEquals(2, insert.executeUpdate());
            }

            assertEquals(1, googleAuthBootstrapRateLimiter.purgeInactiveBuckets());
            assertEquals(0, rateLimitBucketCount(staleKeyHash));
            assertEquals(1, rateLimitBucketCount(activeKeyHash));
        } finally {
            deleteRateLimitBucket(staleKeyHash);
            deleteRateLimitBucket(activeKeyHash);
        }
    }

    @Test
    void firstGoogleSignInCreatesUserAndCanonicalProviderIdentityAtomically() throws Exception {
        String subject = "first-" + UUID.randomUUID();
        String email = subject + "@example.test";
        GoogleIdTokenVerifier.VerifiedGoogleIdentity identity = verifiedIdentity(subject, email, "First Reader");
        GoogleIdentityService.IdentityAccount account = null;

        try {
            account = googleIdentityService.findOrCreate(identity);

            assertTrue(account.created());
            assertEquals("active", account.status());
            assertEquals("First Reader", account.displayName());
            assertEquals(email, account.contactEmail());
            assertEquals(1, identityCount(subject));
            assertEquals(1, userCount(account.userId()));
            assertEquals("https://accounts.google.com", identityIssuer(subject));
        } finally {
            if (account != null) {
                deleteMappedIdentity(account.userId(), subject);
            }
        }
    }

    @Test
    void returningGoogleIdentityUpdatesVerifiedEmailWithoutCreatingAnotherUser() throws Exception {
        String subject = "returning-" + UUID.randomUUID();
        String firstEmail = subject + "@old.example.test";
        String updatedEmail = subject + "@new.example.test";
        GoogleIdentityService.IdentityAccount first = null;

        try {
            first = googleIdentityService.findOrCreate(verifiedIdentity(subject, firstEmail, "Old Name"));
            GoogleIdentityService.IdentityAccount returned = googleIdentityService.findOrCreate(
                    verifiedIdentity(subject, updatedEmail, "Updated Name"));

            assertFalse(returned.created());
            assertEquals(first.userId(), returned.userId());
            assertEquals(updatedEmail, returned.contactEmail());
            assertEquals("Updated Name", returned.displayName());
            assertEquals(1, identityCount(subject));
            assertEquals(1, userCount(first.userId()));
            assertEquals(updatedEmail, storedProviderEmail(subject));
            assertEquals(updatedEmail, storedUserEmail(first.userId()));
            assertTrue(providerIdentityLastSeen(subject));
        } finally {
            if (first != null) {
                deleteMappedIdentity(first.userId(), subject);
            }
        }
    }

    @Test
    void verifiedEmailCollisionRequiresAccountLinkWithoutCreatingOrLinking() throws Exception {
        String subject = "collision-" + UUID.randomUUID();
        String existingEmail = subject + "@example.test";
        try (Connection connection = dataSource.getConnection()) {
            insertUser(connection, "Existing Reader", existingEmail, "active", false);
        }

        try {
            GoogleAccountLinkRequiredException failure = assertThrows(GoogleAccountLinkRequiredException.class,
                    () -> googleIdentityService.findOrCreate(verifiedIdentity(subject, existingEmail, "New Reader")));

            assertEquals(GoogleAccountLinkRequiredException.CODE, failure.code());
            assertFalse(failure.getMessage().contains(existingEmail));
            assertEquals(0, identityCount(subject));
            assertEquals(1, userCountByEmail(existingEmail));
        } finally {
            deleteUserByEmail(existingEmail);
        }
    }

    @Test
    void returningIdentityEmailCollisionRollsBackBothProfileAndProviderClaimUpdates() throws Exception {
        String subject = "return-collision-" + UUID.randomUUID();
        String originalEmail = subject + "@original.example.test";
        String collidingEmail = subject + "@claimed.example.test";
        GoogleIdentityService.IdentityAccount account = googleIdentityService.findOrCreate(
                verifiedIdentity(subject, originalEmail, "Original Name"));
        try (Connection connection = dataSource.getConnection()) {
            insertUser(connection, "Other Reader", collidingEmail, "active", false);
        }

        try {
            GoogleAccountLinkRequiredException failure = assertThrows(GoogleAccountLinkRequiredException.class,
                    () -> googleIdentityService.findOrCreate(
                            verifiedIdentity(subject, collidingEmail, "Changed Name")));

            assertEquals(GoogleAccountLinkRequiredException.CODE, failure.code());
            assertEquals(originalEmail, storedUserEmail(account.userId()));
            assertEquals("Original Name", storedUserDisplayName(account.userId()));
            assertEquals(originalEmail, storedProviderEmail(subject));
            assertEquals(1, identityCount(subject));
            assertEquals(1, userCountByEmail(collidingEmail));
        } finally {
            deleteMappedIdentity(account.userId(), subject);
            deleteUserByEmail(collidingEmail);
        }
    }

    @Test
    void concurrentFirstSignInsForOneSubjectResolveToOneInternalUser() throws Exception {
        String subject = "concurrent-" + UUID.randomUUID();
        String email = subject + "@example.test";
        GoogleIdTokenVerifier.VerifiedGoogleIdentity identity = verifiedIdentity(subject, email, "Concurrent Reader");
        int consumers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(consumers);
        CountDownLatch ready = new CountDownLatch(consumers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<GoogleIdentityService.IdentityAccount>> results = new ArrayList<>();

        try {
            for (int index = 0; index < consumers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("identity consumers did not receive the start signal");
                    }
                    return googleIdentityService.findOrCreate(identity);
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            UUID userId = null;
            int createdCount = 0;
            for (Future<GoogleIdentityService.IdentityAccount> result : results) {
                GoogleIdentityService.IdentityAccount account = result.get(15, TimeUnit.SECONDS);
                userId = account.userId();
                if (account.created()) {
                    createdCount++;
                }
            }
            assertEquals(1, createdCount);
            assertEquals(1, identityCount(subject));
            assertEquals(1, userCount(userId));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            deleteMappedIdentityBySubject(subject);
        }
    }

    @Test
    void suspendedGoogleAccountCannotBeReactivatedBySignIn() throws Exception {
        String subject = "suspended-" + UUID.randomUUID();
        String email = subject + "@example.test";
        UUID userId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            insertUser(connection, "Suspended Reader", email, "suspended", false);
            try (PreparedStatement find = connection.prepareStatement("SELECT id FROM users WHERE contact_email = ?")) {
                find.setString(1, email);
                try (ResultSet row = find.executeQuery()) {
                    assertTrue(row.next());
                    userId = row.getObject(1, UUID.class);
                }
            }
            insertGoogleIdentity(connection, userId, subject, email);
        }

        try {
            assertThrows(UnavailableGoogleIdentityException.class,
                    () -> googleIdentityService.findOrCreate(verifiedIdentity(subject, email, "Changed Name")));
            assertEquals("suspended", storedUserStatus(userId));
            assertEquals("Suspended Reader", storedUserDisplayName(userId));
        } finally {
            deleteMappedIdentity(userId, subject);
        }
    }

    @Test
    void sessionCreationPersistsOnlyTheHashAndValidatesAnActiveUser() throws Exception {
        UUID userId = createSessionTestUser("Session Reader");
        try {
            SessionService.IssuedSession issued = sessionService.create(userId).orElseThrow();
            assertEquals(userId, issued.userId());
            assertEquals(43, issued.cookieValue().length());
            assertTrue(sessionHashMatches(issued.cookieValue(), userId));
            assertFalse(Arrays.equals(issued.cookieValue().getBytes(StandardCharsets.UTF_8),
                    sessionTokenHash(issued.cookieValue())));

            SessionService.SessionPrincipal principal = sessionService.validateAndTouch(issued.cookieValue())
                    .orElseThrow();
            assertEquals(userId, principal.userId());
            assertEquals(issued.expiresAt(), principal.expiresAt());
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void malformedTokensAreRejectedAndRevokeIsIdempotent() throws Exception {
        UUID userId = createSessionTestUser("Revoke Reader");
        try {
            SessionService.IssuedSession issued = sessionService.create(userId).orElseThrow();
            assertTrue(sessionService.validateAndTouch("not-a-session").isEmpty());
            assertFalse(sessionService.revoke("not-a-session"));

            String tampered = (issued.cookieValue().charAt(0) == 'A' ? "B" : "A")
                    + issued.cookieValue().substring(1);
            assertTrue(sessionService.validateAndTouch(tampered).isEmpty());

            assertTrue(sessionService.revoke(issued.cookieValue()));
            assertFalse(sessionService.revoke(issued.cookieValue()));
            assertTrue(sessionService.validateAndTouch(issued.cookieValue()).isEmpty());
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void idleAndAbsoluteExpirationBothRejectTheSession() throws Exception {
        UUID userId = createSessionTestUser("Expired Session Reader");
        try {
            SessionService.IssuedSession idleExpired = sessionService.create(userId).orElseThrow();
            SessionService.IssuedSession absoluteExpired = sessionService.create(userId).orElseThrow();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement expire = connection.prepareStatement("""
                         UPDATE sessions
                         SET created_at = CASE WHEN id_hash = ?
                                               THEN clock_timestamp() - INTERVAL '9 days'
                                               ELSE clock_timestamp() - INTERVAL '31 days' END,
                             last_seen_at = CASE WHEN id_hash = ?
                                                 THEN clock_timestamp() - INTERVAL '8 days'
                                                 ELSE clock_timestamp() - INTERVAL '1 day' END,
                             expires_at = CASE WHEN id_hash = ?
                                               THEN clock_timestamp() + INTERVAL '20 days'
                                               ELSE clock_timestamp() - INTERVAL '1 second' END
                         WHERE user_id = ?
                         """)) {
                expire.setBytes(1, sessionTokenHash(idleExpired.cookieValue()));
                expire.setBytes(2, sessionTokenHash(idleExpired.cookieValue()));
                expire.setBytes(3, sessionTokenHash(idleExpired.cookieValue()));
                expire.setObject(4, userId);
                assertEquals(2, expire.executeUpdate());
            }

            assertTrue(sessionService.validateAndTouch(idleExpired.cookieValue()).isEmpty());
            assertTrue(sessionService.validateAndTouch(absoluteExpired.cookieValue()).isEmpty());
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void activityTouchesAfterThrottleIntervalButDoesNotWriteOnEveryRequest() throws Exception {
        UUID userId = createSessionTestUser("Active Session Reader");
        try {
            SessionService.IssuedSession issued = sessionService.create(userId).orElseThrow();
            byte[] tokenHash = sessionTokenHash(issued.cookieValue());
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement age = connection.prepareStatement("""
                         UPDATE sessions
                         SET created_at = clock_timestamp() - INTERVAL '1 day',
                             last_seen_at = clock_timestamp() - INTERVAL '10 minutes',
                             expires_at = clock_timestamp() + INTERVAL '29 days'
                         WHERE id_hash = ?
                         """)) {
                age.setBytes(1, tokenHash);
                assertEquals(1, age.executeUpdate());
            }

            Instant agedLastSeen = storedSessionLastSeen(tokenHash);
            assertTrue(sessionService.validateAndTouch(issued.cookieValue()).isPresent());
            Instant touchedLastSeen = storedSessionLastSeen(tokenHash);
            assertTrue(touchedLastSeen.isAfter(agedLastSeen));

            assertTrue(sessionService.validateAndTouch(issued.cookieValue()).isPresent());
            assertEquals(touchedLastSeen, storedSessionLastSeen(tokenHash));
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void rotationRevokesOldSecretAndRevokeAllInvalidatesReplacement() throws Exception {
        UUID userId = createSessionTestUser("Rotating Session Reader");
        try {
            SessionService.IssuedSession original = sessionService.create(userId).orElseThrow();
            SessionService.IssuedSession rotated = sessionService.rotate(original.cookieValue()).orElseThrow();

            assertNotEquals(original.cookieValue(), rotated.cookieValue());
            assertEquals(original.expiresAt(), rotated.expiresAt(), "rotation must preserve absolute expiry");
            assertTrue(sessionService.validateAndTouch(original.cookieValue()).isEmpty());
            assertEquals(userId, sessionService.validateAndTouch(rotated.cookieValue()).orElseThrow().userId());
            assertEquals(1, activeSessionCount(userId));
            assertEquals(1, sessionService.revokeAll(userId));
            assertEquals(0, sessionService.revokeAll(userId));
            assertTrue(sessionService.validateAndTouch(rotated.cookieValue()).isEmpty());
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void concurrentRotationOfOneSecretHasExactlyOneWinner() throws Exception {
        UUID userId = createSessionTestUser("Concurrent Session Reader");
        int consumers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(consumers);
        CountDownLatch ready = new CountDownLatch(consumers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<SessionService.IssuedSession>>> results = new ArrayList<>();

        try {
            SessionService.IssuedSession original = sessionService.create(userId).orElseThrow();
            for (int index = 0; index < consumers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("session rotation requests did not reach the barrier");
                    }
                    return sessionService.rotate(original.cookieValue());
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            SessionService.IssuedSession winner = null;
            int winnerCount = 0;
            for (Future<Optional<SessionService.IssuedSession>> result : results) {
                Optional<SessionService.IssuedSession> rotated = result.get(15, TimeUnit.SECONDS);
                if (rotated.isPresent()) {
                    winner = rotated.orElseThrow();
                    winnerCount++;
                }
            }
            assertEquals(1, winnerCount);
            assertTrue(sessionService.validateAndTouch(original.cookieValue()).isEmpty());
            assertEquals(userId, sessionService.validateAndTouch(winner.cookieValue()).orElseThrow().userId());
            assertEquals(1, activeSessionCount(userId));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            deleteSessionTestUser(userId);
        }
    }

    @Test
    void suspendedUsersCannotCreateOrContinueASession() throws Exception {
        UUID userId = createSessionTestUser("Suspended Session Reader");
        try {
            SessionService.IssuedSession issued = sessionService.create(userId).orElseThrow();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement suspend = connection.prepareStatement(
                         "UPDATE users SET status = 'suspended' WHERE id = ?")) {
                suspend.setObject(1, userId);
                assertEquals(1, suspend.executeUpdate());
            }

            assertTrue(sessionService.create(userId).isEmpty());
            assertTrue(sessionService.validateAndTouch(issued.cookieValue()).isEmpty());
            assertTrue(sessionService.rotate(issued.cookieValue()).isEmpty());
        } finally {
            deleteSessionTestUser(userId);
        }
    }

    private UUID createSessionTestUser(String displayName) throws SQLException {
        UUID userId = UUID.randomUUID();
        String email = "session-" + userId + "@example.test";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO users (id, display_name, contact_email, email_verified_at, status)
                     VALUES (?, ?, ?, clock_timestamp(), 'active')
                     """)) {
            insert.setObject(1, userId);
            insert.setString(2, displayName);
            insert.setString(3, email);
            assertEquals(1, insert.executeUpdate());
        }
        return userId;
    }

    private void insertTestVolume(UUID volumeId, UUID workId, int ordinal, String title) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO volumes (id, work_id, ordinal, title) VALUES (?, ?, ?, ?)
                     """)) {
            insert.setObject(1, volumeId);
            insert.setObject(2, workId);
            insert.setInt(3, ordinal);
            insert.setString(4, title);
            insert.executeUpdate();
        }
    }

    private void insertTestChapter(UUID chapterId, UUID workId, UUID volumeId, int ordinal, String title)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language)
                     VALUES (?, ?, ?, ?, ?, 'und')
                     """)) {
            insert.setObject(1, chapterId);
            insert.setObject(2, workId);
            insert.setObject(3, volumeId);
            insert.setInt(4, ordinal);
            insert.setString(5, title);
            insert.executeUpdate();
        }
    }

    private int segmentCount(UUID revisionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM segments WHERE chapter_revision_id = ?")) {
            select.setObject(1, revisionId);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private int revisionCount(UUID chapterId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM chapter_revisions WHERE chapter_id = ?")) {
            select.setObject(1, chapterId);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private void setWorkCreatedAt(UUID workId, Instant createdAt) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE works SET created_at = ? WHERE id = ?")) {
            update.setTimestamp(1, Timestamp.from(createdAt));
            update.setObject(2, workId);
            assertEquals(1, update.executeUpdate());
        }
    }

    private void setWorkStatus(UUID workId, String status) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE works SET status = ? WHERE id = ?")) {
            update.setString(1, status);
            update.setObject(2, workId);
            assertEquals(1, update.executeUpdate());
        }
    }

    private void deleteLibraryTestData(UUID ownerId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement deleteWorkTags = connection.prepareStatement(
                    "DELETE FROM work_tags WHERE owner_user_id = ? OR assigned_by_user_id = ?")) {
                deleteWorkTags.setObject(1, ownerId);
                deleteWorkTags.setObject(2, ownerId);
                deleteWorkTags.executeUpdate();
            }
            try (PreparedStatement deleteSegments = connection.prepareStatement("""
                    DELETE FROM segments s USING chapter_revisions r, chapters c, works w
                    WHERE s.chapter_revision_id = r.id AND r.chapter_id = c.id
                      AND c.work_id = w.id AND w.owner_user_id = ?
                    """)) {
                deleteSegments.setObject(1, ownerId);
                deleteSegments.executeUpdate();
            }
            try (PreparedStatement clearCurrentRevision = connection.prepareStatement("""
                    UPDATE chapters c SET source_revision_id = NULL FROM works w
                    WHERE c.work_id = w.id AND w.owner_user_id = ?
                    """)) {
                clearCurrentRevision.setObject(1, ownerId);
                clearCurrentRevision.executeUpdate();
            }
            try (PreparedStatement deleteRevisions = connection.prepareStatement("""
                    DELETE FROM chapter_revisions r USING chapters c, works w
                    WHERE r.chapter_id = c.id AND c.work_id = w.id AND w.owner_user_id = ?
                    """)) {
                deleteRevisions.setObject(1, ownerId);
                deleteRevisions.executeUpdate();
            }
            try (PreparedStatement deleteChapters = connection.prepareStatement("""
                    DELETE FROM chapters c USING works w
                    WHERE c.work_id = w.id AND w.owner_user_id = ?
                    """)) {
                deleteChapters.setObject(1, ownerId);
                deleteChapters.executeUpdate();
            }
            try (PreparedStatement deleteVolumes = connection.prepareStatement("""
                    DELETE FROM volumes v USING works w
                    WHERE v.work_id = w.id AND w.owner_user_id = ?
                    """)) {
                deleteVolumes.setObject(1, ownerId);
                deleteVolumes.executeUpdate();
            }
            try (PreparedStatement deleteAssets = connection.prepareStatement(
                    "DELETE FROM assets WHERE owner_user_id = ?")) {
                deleteAssets.setObject(1, ownerId);
                deleteAssets.executeUpdate();
            }
            try (PreparedStatement deleteTags = connection.prepareStatement("DELETE FROM tags WHERE owner_user_id = ?")) {
                deleteTags.setObject(1, ownerId);
                deleteTags.executeUpdate();
            }
            try (PreparedStatement deleteWorks = connection.prepareStatement(
                    "DELETE FROM works WHERE owner_user_id = ?")) {
                deleteWorks.setObject(1, ownerId);
                deleteWorks.executeUpdate();
            }
            try (PreparedStatement deleteLibraries = connection.prepareStatement(
                    "DELETE FROM libraries WHERE owner_user_id = ?")) {
                deleteLibraries.setObject(1, ownerId);
                deleteLibraries.executeUpdate();
            }
        }
    }

    private void deleteWorkspaceTestData(UUID workspaceId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement deleteWorkspace = connection.prepareStatement(
                     "DELETE FROM workspaces WHERE id = ?")) {
            deleteWorkspace.setObject(1, workspaceId);
            deleteWorkspace.executeUpdate();
        }
    }

    private void addSessionTestGoogleIdentity(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO external_identities (
                         id, user_id, provider, issuer, subject, provider_email, provider_email_verified)
                     VALUES (?, ?, 'google', 'https://accounts.google.com', ?, ?, TRUE)
                     """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, userId);
            insert.setString(3, "current-user-" + userId);
            insert.setString(4, "session-" + userId + "@example.test");
            assertEquals(1, insert.executeUpdate());
        }
    }

    private void addCurrentPolicyAcceptances(UUID userId) throws SQLException {
        addPolicyAcceptance(userId, "TERMS", "terms-current-1", "TERMS_ACCEPTED");
        addPolicyAcceptance(userId, "PRIVACY_NOTICE", "privacy-current-1", "PRIVACY_NOTICE_ACKNOWLEDGED");
    }

    private void addPolicyAcceptance(UUID userId, String type, String version, String decision) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO policy_acceptances (id, user_id, policy_type, version, decision, source)
                     VALUES (?, ?, ?, ?, ?, 'web')
                     """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, userId);
            insert.setString(3, type);
            insert.setString(4, version);
            insert.setString(5, decision);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private void insertPolicyDocument(String type, String version, int effectiveDayOffset, String document)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO policy_documents (policy_type, version, effective_at, document_markdown)
                     VALUES (?, ?, clock_timestamp() + (? * INTERVAL '1 day'), ?)
                     """)) {
            insert.setString(1, type);
            insert.setString(2, version);
            insert.setInt(3, effectiveDayOffset);
            insert.setString(4, document);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private int policyAcceptanceCount(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM policy_acceptances WHERE user_id = ?")) {
            select.setObject(1, userId);
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private int policyAcceptanceCountBySource(UUID userId, String source) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM policy_acceptances WHERE user_id = ? AND source = ?")) {
            select.setObject(1, userId);
            select.setString(2, source);
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private boolean sessionHashMatches(String token, UUID userId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT id_hash FROM sessions WHERE user_id = ?")) {
            select.setObject(1, userId);
            try (ResultSet row = select.executeQuery()) {
                byte[] expectedHash = sessionTokenHash(token);
                byte[] rawToken = token.getBytes(StandardCharsets.US_ASCII);
                boolean hashMatches = false;
                while (row.next()) {
                    byte[] storedHash = row.getBytes(1);
                    hashMatches |= MessageDigest.isEqual(expectedHash, storedHash);
                    assertFalse(MessageDigest.isEqual(rawToken, storedHash),
                            "the database must never store the raw cookie token");
                }
                return hashMatches;
            }
        }
    }

    private boolean confirmationHashStoredForUser(String token, UUID userId, String sessionToken) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT token_hash, user_id, session_id_hash
                     FROM session_revoke_confirmations
                     WHERE token_hash = ?
                     """)) {
            byte[] expectedConfirmationHash = sessionTokenHash(token);
            byte[] expectedSessionHash = sessionTokenHash(sessionToken);
            select.setBytes(1, expectedConfirmationHash);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return false;
                }
                assertEquals(userId, row.getObject("user_id", UUID.class));
                assertTrue(MessageDigest.isEqual(expectedConfirmationHash, row.getBytes("token_hash")));
                assertTrue(MessageDigest.isEqual(expectedSessionHash, row.getBytes("session_id_hash")));
                assertFalse(MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), row.getBytes("token_hash")),
                        "the database must never store the raw confirmation token");
                return true;
            }
        }
    }

    private boolean confirmationHashExists(String token) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM session_revoke_confirmations WHERE token_hash = ?")) {
            select.setBytes(1, sessionTokenHash(token));
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getInt(1) == 1;
            }
        }
    }

    private static byte[] sessionTokenHash(String token) throws Exception {
        byte[] secret = Base64.getUrlDecoder().decode(token);
        return MessageDigest.getInstance("SHA-256").digest(secret);
    }

    private Instant storedSessionLastSeen(byte[] tokenHash) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT last_seen_at FROM sessions WHERE id_hash = ?")) {
            select.setBytes(1, tokenHash);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getObject(1, java.time.OffsetDateTime.class).toInstant();
            }
        }
    }

    private int activeSessionCount(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM sessions WHERE user_id = ? AND revoked_at IS NULL")) {
            select.setObject(1, userId);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getInt(1);
            }
        }
    }

    private void deleteSessionTestUser(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement deleteConfirmations = connection.prepareStatement(
                    "DELETE FROM session_revoke_confirmations WHERE user_id = ?")) {
                deleteConfirmations.setObject(1, userId);
                deleteConfirmations.executeUpdate();
            }
            try (PreparedStatement deletePolicyAcceptances = connection.prepareStatement(
                    "DELETE FROM policy_acceptances WHERE user_id = ?")) {
                deletePolicyAcceptances.setObject(1, userId);
                deletePolicyAcceptances.executeUpdate();
            }
            try (PreparedStatement deleteIdentities = connection.prepareStatement(
                    "DELETE FROM external_identities WHERE user_id = ?")) {
                deleteIdentities.setObject(1, userId);
                deleteIdentities.executeUpdate();
            }
            try (PreparedStatement deleteSessions = connection.prepareStatement(
                    "DELETE FROM sessions WHERE user_id = ?")) {
                deleteSessions.setObject(1, userId);
                deleteSessions.executeUpdate();
            }
            try (PreparedStatement deleteUser = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
                deleteUser.setObject(1, userId);
                deleteUser.executeUpdate();
            }
        }
    }

    private void deleteLibraryUserData(UUID userId, UUID workId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement deleteAssignments = connection.prepareStatement(
                    "DELETE FROM library_collection_works WHERE owner_user_id = ?")) {
                deleteAssignments.setObject(1, userId);
                deleteAssignments.executeUpdate();
            }
            try (PreparedStatement deleteStates = connection.prepareStatement(
                    "DELETE FROM library_reading_states WHERE user_id = ?")) {
                deleteStates.setObject(1, userId);
                deleteStates.executeUpdate();
            }
            try (PreparedStatement deleteCollections = connection.prepareStatement(
                    "DELETE FROM library_collections WHERE owner_user_id = ?")) {
                deleteCollections.setObject(1, userId);
                deleteCollections.executeUpdate();
            }
            if (workId != null) {
                try (PreparedStatement deleteWork = connection.prepareStatement(
                        "DELETE FROM works WHERE id = ? AND owner_user_id = ?")) {
                    deleteWork.setObject(1, workId);
                    deleteWork.setObject(2, userId);
                    deleteWork.executeUpdate();
                }
            }
            try (PreparedStatement deleteLibraries = connection.prepareStatement(
                    "DELETE FROM libraries WHERE owner_user_id = ?")) {
                deleteLibraries.setObject(1, userId);
                deleteLibraries.executeUpdate();
            }
        }
    }

    private String storedBootstrapReturnPath(String nonce) throws SQLException, NoSuchAlgorithmException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT safe_return_path FROM google_auth_attempts WHERE nonce_hash = ?")) {
            select.setBytes(1, sha256(nonce));
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private int googleAuthAttemptCount(UUID correlationId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM google_auth_attempts WHERE correlation_id = ?")) {
            select.setObject(1, correlationId);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getInt(1);
            }
        }
    }

    private void deleteBootstrapFixtures(String clientAddress, List<UUID> correlationIds) throws Exception {
        byte[] keyHash = bootstrapRateLimitKeyHash(clientAddress);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement deleteAttempt = connection.prepareStatement(
                    "DELETE FROM google_auth_attempts WHERE correlation_id = ?")) {
                for (UUID correlationId : correlationIds) {
                    deleteAttempt.setObject(1, correlationId);
                    deleteAttempt.addBatch();
                }
                deleteAttempt.executeBatch();
            }
            try (PreparedStatement deleteBucket = connection.prepareStatement(
                    "DELETE FROM google_auth_bootstrap_rate_limits WHERE rate_limit_key_hash = ?")) {
                deleteBucket.setBytes(1, keyHash);
                deleteBucket.executeUpdate();
            }
        }
    }

    private static String bootstrapTestClientAddress() {
        return "203.0.113." + (1 + Math.floorMod(UUID.randomUUID().hashCode(), 254));
    }

    private static byte[] bootstrapRateLimitKeyHash(String clientAddress) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(BOOTSTRAP_RATE_LIMIT_HMAC_KEY), "HmacSHA256"));
        return mac.doFinal(InetAddress.getByName(clientAddress).getAddress());
    }

    private static GoogleIdTokenVerifier.VerifiedGoogleIdentity verifiedIdentity(
            String subject, String email, String name) {
        return GoogleIdentityTestFixtures.verifiedIdentity(subject, email, name);
    }

    private static Jwt googleJwt(String nonce, String subject, String email) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("synthetic-google-id-token")
                .header("alg", "RS256")
                .claim("iss", "https://accounts.google.com")
                .claim("aud", List.of("novel-web-integration-test.apps.googleusercontent.com"))
                .claim("sub", subject)
                .claim("email", email)
                .claim("email_verified", true)
                .claim("name", "Integration Reader")
                .claim("nonce", nonce)
                .issuedAt(now.minusSeconds(10))
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    private static byte[] sha256(String value) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private void deleteAuthAttempt(byte[] nonceHash) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM google_auth_attempts WHERE nonce_hash = ?")) {
            delete.setBytes(1, nonceHash);
            delete.executeUpdate();
        }
    }

    private void deleteRateLimitBucket(byte[] keyHash) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM google_auth_bootstrap_rate_limits WHERE rate_limit_key_hash = ?")) {
            delete.setBytes(1, keyHash);
            delete.executeUpdate();
        }
    }

    private int rateLimitBucketCount(byte[] keyHash) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM google_auth_bootstrap_rate_limits WHERE rate_limit_key_hash = ?")) {
            select.setBytes(1, keyHash);
            try (ResultSet result = select.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int identityCount(String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT COUNT(*) FROM external_identities WHERE issuer = 'https://accounts.google.com' AND subject = ?")) {
            select.setString(1, subject);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private UUID userIdForGoogleSubject(String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT user_id FROM external_identities
                     WHERE issuer = 'https://accounts.google.com' AND subject = ?
                     """)) {
            select.setString(1, subject);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getObject(1, UUID.class);
            }
        }
    }

    private void deleteGoogleSignInFixtures(String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT user_id FROM external_identities
                     WHERE issuer = 'https://accounts.google.com' AND subject = ?
                     """)) {
            select.setString(1, subject);
            UUID userId = null;
            try (ResultSet row = select.executeQuery()) {
                if (row.next()) {
                    userId = row.getObject(1, UUID.class);
                }
            }
            if (userId == null) {
                return;
            }
            for (String table : List.of("sessions", "policy_acceptances", "auth_events", "external_identities")) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE user_id = ?")) {
                    delete.setObject(1, userId);
                    delete.executeUpdate();
                }
            }
            try (PreparedStatement deleteUser = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
                deleteUser.setObject(1, userId);
                deleteUser.executeUpdate();
            }
        }
    }

    private int userCount(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE id = ?")) {
            select.setObject(1, userId);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private int userCountByEmail(String email) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE contact_email = ?")) {
            select.setString(1, email);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private String identityIssuer(String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT issuer FROM external_identities WHERE subject = ?")) {
            select.setString(1, subject);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private String storedProviderEmail(String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT provider_email FROM external_identities WHERE subject = ?")) {
            select.setString(1, subject);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private boolean providerIdentityLastSeen(String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT last_seen_at IS NOT NULL FROM external_identities WHERE subject = ?")) {
            select.setString(1, subject);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getBoolean(1);
            }
        }
    }

    private String storedUserEmail(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT contact_email FROM users WHERE id = ?")) {
            select.setObject(1, userId);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private String storedUserStatus(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT status FROM users WHERE id = ?")) {
            select.setObject(1, userId);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private String storedUserDisplayName(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT display_name FROM users WHERE id = ?")) {
            select.setObject(1, userId);
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private void deleteMappedIdentity(UUID userId, String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement deleteIdentity = connection.prepareStatement(
                    "DELETE FROM external_identities WHERE user_id = ? AND subject = ?")) {
                deleteIdentity.setObject(1, userId);
                deleteIdentity.setString(2, subject);
                deleteIdentity.executeUpdate();
            }
            try (PreparedStatement deleteUser = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
                deleteUser.setObject(1, userId);
                deleteUser.executeUpdate();
            }
        }
    }

    private void deleteMappedIdentityBySubject(String subject) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = null;
            try (PreparedStatement deleteIdentity = connection.prepareStatement("""
                    DELETE FROM external_identities
                    WHERE issuer = 'https://accounts.google.com' AND subject = ?
                    RETURNING user_id
                    """)) {
                deleteIdentity.setString(1, subject);
                try (ResultSet row = deleteIdentity.executeQuery()) {
                    if (row.next()) {
                        userId = row.getObject(1, UUID.class);
                    }
                }
            }
            if (userId != null) {
                try (PreparedStatement deleteUser = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
                    deleteUser.setObject(1, userId);
                    deleteUser.executeUpdate();
                }
            }
        }
    }

    private void deleteUserByEmail(String email) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement("DELETE FROM users WHERE contact_email = ?")) {
            delete.setString(1, email);
            delete.executeUpdate();
        }
    }

    private static void insertGoogleIdentity(
            Connection connection, UUID userId, String subject, String email) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO external_identities (
                    id, user_id, provider, issuer, subject, provider_email, provider_email_verified, last_seen_at)
                VALUES (?, ?, 'google', 'https://accounts.google.com', ?, ?, TRUE, clock_timestamp())
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, userId);
            insert.setString(3, subject);
            insert.setString(4, email);
            insert.executeUpdate();
        }
    }

    private static void insertUser(
            Connection connection,
            String displayName,
            String email,
            String status,
            boolean deleted) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO users (id, display_name, contact_email, email_verified_at, status, deleted_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, displayName);
            insert.setString(3, email);
            insert.setString(4, status);
            if (deleted) {
                insert.setTimestamp(5, Timestamp.from(Instant.now()));
            } else {
                insert.setTimestamp(5, null);
            }
            insert.executeUpdate();
        }
    }
}
