package com.novelweb.platform.postgres;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "postgres.integration", matches = "true")
class PostgresSchemaIntegrationTests {

    private static final Set<String> EXPECTED_APPLICATION_TABLES = Set.of(
            "users",
            "external_identities",
            "sessions",
            "google_auth_attempts",
            "policy_acceptances",
            "auth_events");

    @Autowired
    private DataSource dataSource;

    @Test
    void liquibaseAppliesTheBaselineAndCreatesExpectedTables() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement changesetCount = connection.prepareStatement(
                     "SELECT COUNT(*) FROM databasechangelog");
             ResultSet changesets = changesetCount.executeQuery()) {
            changesets.next();
            assertEquals(11, changesets.getInt(1), "unexpected number of applied baseline changesets");
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
