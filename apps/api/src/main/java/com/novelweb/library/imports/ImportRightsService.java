package com.novelweb.library.imports;

import com.novelweb.identity.auth.PolicyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Reads the owner-approved private-import rights text and stores an immutable versioned
 * attestation.
 */
@Service
@Profile("postgres")
public class ImportRightsService {

  private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private final DataSource dataSource;
  private final PolicyService policyService;

  public ImportRightsService(DataSource dataSource, PolicyService policyService) {
    this.dataSource = dataSource;
    this.policyService = policyService;
  }

  public Optional<ImportRightsStatement> currentStatement() {
    try (Connection connection = dataSource.getConnection()) {
      return currentStatement(connection);
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to load the current import-rights statement", exception);
    }
  }

  public AcceptanceResult accept(UUID userId, String requestedVersion, UUID idempotencyKey) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(requestedVersion, "statement version is required");
    Objects.requireNonNull(idempotencyKey, "idempotency key is required");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        // Keep the approved version stable while the acceptance row is recorded.
        try (PreparedStatement lock =
            connection.prepareStatement("LOCK TABLE import_rights_statements IN SHARE MODE")) {
          lock.execute();
        }

        Optional<Acceptance> priorAttempt = findAcceptance(connection, userId, idempotencyKey);
        if (priorAttempt.isPresent()) {
          Acceptance prior = priorAttempt.orElseThrow();
          connection.commit();
          return prior.statementVersion().equals(requestedVersion)
              ? AcceptanceResult.accepted(prior)
              : AcceptanceResult.keyReused();
        }

        PolicyService.RequiredAcceptance policyState = policyService.requiredAcceptance(userId);
        if (policyState == PolicyService.RequiredAcceptance.REQUIRED) {
          connection.commit();
          return AcceptanceResult.policyRequired();
        }
        if (policyState == PolicyService.RequiredAcceptance.NOT_CONFIGURED) {
          connection.commit();
          return AcceptanceResult.policyNotConfigured();
        }

        Optional<ImportRightsStatement> active = currentStatement(connection);
        if (active.isEmpty()) {
          connection.commit();
          return AcceptanceResult.notConfigured();
        }
        ImportRightsStatement statement = active.orElseThrow();
        if (!statement.version().equals(requestedVersion)) {
          connection.commit();
          return AcceptanceResult.stale(statement);
        }

        byte[] contentHash = sha256(statement.displayText());
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                        INSERT INTO import_rights_acceptances
                            (id, user_id, idempotency_key, statement_version, statement_sha256, source)
                        VALUES (?, ?, ?, ?, ?, 'web-import-flow')
                        ON CONFLICT (user_id, idempotency_key) DO NOTHING
                        """)) {
          insert.setObject(1, UUID.randomUUID());
          insert.setObject(2, userId);
          insert.setObject(3, idempotencyKey);
          insert.setString(4, statement.version());
          insert.setBytes(5, contentHash);
          insert.executeUpdate();
        }

        Acceptance acceptance =
            findAcceptance(connection, userId, idempotencyKey)
                .orElseThrow(
                    () -> new SQLException("Acceptance insert did not produce an audit record"));
        connection.commit();
        return AcceptanceResult.accepted(acceptance);
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to record import-rights acceptance", exception);
    }
  }

  public static boolean isValidVersion(String version) {
    return version != null && VERSION_PATTERN.matcher(version).matches();
  }

  private Optional<ImportRightsStatement> currentStatement(Connection connection)
      throws SQLException {
    try (PreparedStatement select =
            connection.prepareStatement(
                """
                SELECT version, effective_at, display_text
                FROM import_rights_statements
                WHERE status = 'APPROVED' AND effective_at <= clock_timestamp()
                ORDER BY effective_at DESC, version DESC
                LIMIT 1
                """);
        ResultSet row = select.executeQuery()) {
      if (!row.next()) return Optional.empty();
      return Optional.of(
          new ImportRightsStatement(
              row.getString("version"),
              row.getObject("effective_at", OffsetDateTime.class).toInstant(),
              row.getString("display_text")));
    }
  }

  private Optional<Acceptance> findAcceptance(
      Connection connection, UUID userId, UUID idempotencyKey) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT id, statement_version, accepted_at, statement_sha256
                FROM import_rights_acceptances
                WHERE user_id = ? AND idempotency_key = ?
                """)) {
      select.setObject(1, userId);
      select.setObject(2, idempotencyKey);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new Acceptance(
                row.getObject("id", UUID.class),
                row.getString("statement_version"),
                row.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                HexFormat.of().formatHex(row.getBytes("statement_sha256"))));
      }
    }
  }

  private static byte[] sha256(String displayText) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(displayText.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  public record ImportRightsStatement(String version, Instant effectiveAt, String displayText) {}

  public record Acceptance(
      UUID id, String statementVersion, Instant acceptedAt, String statementSha256) {}

  public enum Outcome {
    ACCEPTED,
    STALE,
    POLICY_REQUIRED,
    POLICY_NOT_CONFIGURED,
    KEY_REUSED,
    NOT_CONFIGURED
  }

  public record AcceptanceResult(
      Outcome outcome, Acceptance acceptance, ImportRightsStatement currentStatement) {
    static AcceptanceResult accepted(Acceptance acceptance) {
      return new AcceptanceResult(Outcome.ACCEPTED, acceptance, null);
    }

    static AcceptanceResult stale(ImportRightsStatement current) {
      return new AcceptanceResult(Outcome.STALE, null, current);
    }

    static AcceptanceResult policyRequired() {
      return new AcceptanceResult(Outcome.POLICY_REQUIRED, null, null);
    }

    static AcceptanceResult policyNotConfigured() {
      return new AcceptanceResult(Outcome.POLICY_NOT_CONFIGURED, null, null);
    }

    static AcceptanceResult keyReused() {
      return new AcceptanceResult(Outcome.KEY_REUSED, null, null);
    }

    static AcceptanceResult notConfigured() {
      return new AcceptanceResult(Outcome.NOT_CONFIGURED, null, null);
    }
  }
}
