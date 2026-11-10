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
import com.novelweb.identity.session.SessionService;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
@org.springframework.test.context.TestPropertySource(properties = {
        "novel.auth.google.rate-limit-refill-interval=24h",
        "novel.auth.google.rate-limit-hmac-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "novel.auth.google.web-client-id=novel-web-integration-test.apps.googleusercontent.com",
        "novel.auth.google.credential-callback-uri=https://api.example.test/api/v1/auth/google/credential",
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
            "auth_events");

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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void liquibaseAppliesTheBaselineAndCreatesExpectedTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement changesetCount = connection.prepareStatement(
                     "SELECT COUNT(*) FROM databasechangelog");
             ResultSet changesets = changesetCount.executeQuery()) {
            changesets.next();
            assertEquals(12, changesets.getInt(1), "unexpected number of applied baseline changesets");
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
