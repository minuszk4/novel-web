package com.novelweb.platform.postgres;

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

import javax.sql.DataSource;

import com.novelweb.identity.auth.GoogleAuthBootstrapRateLimiter;
import com.novelweb.identity.auth.GoogleAuthAttemptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
@org.springframework.test.context.TestPropertySource(properties =
        "novel.auth.google.rate-limit-refill-interval=24h")
class PostgresSchemaIntegrationTests {

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
