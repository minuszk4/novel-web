package com.novelweb.identity.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class PolicyService {

  private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  private static final String DOCUMENT_BASE_PATH = "/api/v1/auth/policies";

  private final DataSource dataSource;

  public PolicyService(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public Optional<CurrentPolicies> currentPolicies() {
    try (Connection connection = dataSource.getConnection()) {
      return currentPolicies(connection).map(PolicyService::currentPoliciesResponse);
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load current policy documents", exception);
    }
  }

  public RequiredAcceptance requiredAcceptance(UUID userId) {
    Objects.requireNonNull(userId, "user id is required");
    try (Connection connection = dataSource.getConnection()) {
      Optional<Map<PolicyType, PolicyVersion>> active = currentPolicies(connection);
      if (active.isEmpty()) return RequiredAcceptance.NOT_CONFIGURED;
      PolicyVersion terms = active.orElseThrow().get(PolicyType.TERMS);
      PolicyVersion privacy = active.orElseThrow().get(PolicyType.PRIVACY_NOTICE);
      if (terms == null || privacy == null) return RequiredAcceptance.NOT_CONFIGURED;
      try (PreparedStatement select =
          connection.prepareStatement(
              """
                    SELECT
                      EXISTS (SELECT 1 FROM policy_acceptances
                              WHERE user_id = ? AND policy_type = 'TERMS' AND version = ?
                                AND decision = 'TERMS_ACCEPTED') AS terms_accepted,
                      EXISTS (SELECT 1 FROM policy_acceptances
                              WHERE user_id = ? AND policy_type = 'PRIVACY_NOTICE' AND version = ?
                                AND decision = 'PRIVACY_NOTICE_ACKNOWLEDGED') AS privacy_acknowledged
                    """)) {
        select.setObject(1, userId);
        select.setString(2, terms.version());
        select.setObject(3, userId);
        select.setString(4, privacy.version());
        try (ResultSet row = select.executeQuery()) {
          row.next();
          return row.getBoolean("terms_accepted") && row.getBoolean("privacy_acknowledged")
              ? RequiredAcceptance.COMPLETE
              : RequiredAcceptance.REQUIRED;
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to check current policy acceptance", exception);
    }
  }

  public Optional<PolicyDocumentContent> document(PolicyType policyType, String version) {
    Objects.requireNonNull(policyType, "policy type is required");
    if (!isValidVersion(version)) {
      return Optional.empty();
    }
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT version, effective_at, document_markdown
                     FROM policy_documents
                     WHERE policy_type = ? AND version = ?
                       AND effective_at <= clock_timestamp()
                     """)) {
      select.setString(1, policyType.databaseValue());
      select.setString(2, version);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new PolicyDocumentContent(
                policyType,
                row.getString("version"),
                row.getObject("effective_at", OffsetDateTime.class).toInstant(),
                row.getString("document_markdown")));
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load a versioned policy document", exception);
    }
  }

  public AcceptanceResult accept(UUID userId, AcceptanceCommand command) {
    Objects.requireNonNull(userId, "user id is required");
    Objects.requireNonNull(command, "acceptance command is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        // Keep current versions stable until both acceptance rows are committed.
        try (PreparedStatement lock =
            connection.prepareStatement("LOCK TABLE policy_documents IN SHARE MODE")) {
          lock.execute();
        }

        Optional<Map<PolicyType, PolicyVersion>> active = currentPolicies(connection);
        if (active.isEmpty()) {
          connection.commit();
          return AcceptanceResult.notConfigured();
        }
        PolicyVersion terms = active.orElseThrow().get(PolicyType.TERMS);
        PolicyVersion privacy = active.orElseThrow().get(PolicyType.PRIVACY_NOTICE);
        if (terms == null || privacy == null) {
          connection.commit();
          return AcceptanceResult.notConfigured();
        }
        if (!terms.version().equals(command.termsVersion())
            || !privacy.version().equals(command.privacyNoticeVersion())) {
          connection.commit();
          return AcceptanceResult.stale(currentPoliciesResponse(active.orElseThrow()));
        }

        Instant termsAcceptedAt =
            insertAcceptance(
                connection, userId, PolicyType.TERMS, terms.version(), "TERMS_ACCEPTED");
        Instant privacyAcknowledgedAt =
            insertAcceptance(
                connection,
                userId,
                PolicyType.PRIVACY_NOTICE,
                privacy.version(),
                "PRIVACY_NOTICE_ACKNOWLEDGED");
        connection.commit();
        return AcceptanceResult.accepted(
            new PolicyAcceptanceResponse(
                terms.version(), termsAcceptedAt, privacy.version(), privacyAcknowledgedAt));
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to record policy decisions", exception);
    }
  }

  public static boolean isValidVersion(String version) {
    return version != null && VERSION_PATTERN.matcher(version).matches();
  }

  private Optional<Map<PolicyType, PolicyVersion>> currentPolicies(Connection connection)
      throws SQLException {
    String query =
        """
                SELECT policy_type, version, effective_at
                FROM policy_documents
                WHERE effective_at <= clock_timestamp()
                ORDER BY policy_type, effective_at DESC
                """;
    EnumMap<PolicyType, PolicyVersion> current = new EnumMap<>(PolicyType.class);
    try (PreparedStatement select = connection.prepareStatement(query);
        ResultSet rows = select.executeQuery()) {
      while (rows.next()) {
        PolicyType type = PolicyType.fromDatabaseValue(rows.getString("policy_type"));
        current.putIfAbsent(
            type,
            new PolicyVersion(
                rows.getString("version"),
                rows.getObject("effective_at", OffsetDateTime.class).toInstant()));
      }
    }
    return current.containsKey(PolicyType.TERMS) && current.containsKey(PolicyType.PRIVACY_NOTICE)
        ? Optional.of(current)
        : Optional.empty();
  }

  private Instant insertAcceptance(
      Connection connection, UUID userId, PolicyType policyType, String version, String decision)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO policy_acceptances (id, user_id, policy_type, version, decision, recorded_at, source)
                VALUES (?, ?, ?, ?, ?, clock_timestamp(), 'web-onboarding')
                ON CONFLICT (user_id, policy_type, version)
                DO UPDATE SET recorded_at = policy_acceptances.recorded_at
                RETURNING recorded_at
                """)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, userId);
      insert.setString(3, policyType.databaseValue());
      insert.setString(4, version);
      insert.setString(5, decision);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) {
          throw new SQLException("Policy acceptance did not return a server timestamp");
        }
        return row.getObject("recorded_at", OffsetDateTime.class).toInstant();
      }
    }
  }

  private static CurrentPolicies currentPoliciesResponse(Map<PolicyType, PolicyVersion> policies) {
    PolicyVersion terms = policies.get(PolicyType.TERMS);
    PolicyVersion privacy = policies.get(PolicyType.PRIVACY_NOTICE);
    return new CurrentPolicies(
        documentMetadata(PolicyType.TERMS, terms),
        documentMetadata(PolicyType.PRIVACY_NOTICE, privacy));
  }

  private static PolicyDocumentMetadata documentMetadata(PolicyType type, PolicyVersion policy) {
    return new PolicyDocumentMetadata(
        policy.version(),
        policy.effectiveAt(),
        DOCUMENT_BASE_PATH + type.documentPath() + "/" + policy.version());
  }

  private static void rollback(Connection connection, Exception originalException) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      originalException.addSuppressed(rollbackException);
    }
  }

  public enum PolicyType {
    TERMS("TERMS", "/terms"),
    PRIVACY_NOTICE("PRIVACY_NOTICE", "/privacy-notice");

    private final String databaseValue;
    private final String documentPath;

    PolicyType(String databaseValue, String documentPath) {
      this.databaseValue = databaseValue;
      this.documentPath = documentPath;
    }

    public String databaseValue() {
      return databaseValue;
    }

    public String documentPath() {
      return documentPath;
    }

    private static PolicyType fromDatabaseValue(String value) {
      return switch (value) {
        case "TERMS" -> TERMS;
        case "PRIVACY_NOTICE" -> PRIVACY_NOTICE;
        default -> throw new IllegalStateException("Unsupported policy document type");
      };
    }
  }

  public enum AcceptanceOutcome {
    ACCEPTED,
    STALE,
    NOT_CONFIGURED
  }

  public enum RequiredAcceptance {
    COMPLETE,
    REQUIRED,
    NOT_CONFIGURED
  }

  public record PolicyVersion(String version, Instant effectiveAt) {}

  public record PolicyDocumentMetadata(String version, Instant effectiveAt, String documentUrl) {}

  public record CurrentPolicies(
      PolicyDocumentMetadata terms, PolicyDocumentMetadata privacyNotice) {}

  public record PolicyDocumentContent(
      PolicyType policyType, String version, Instant effectiveAt, String documentMarkdown) {}

  public record AcceptanceCommand(
      String termsVersion,
      String privacyNoticeVersion,
      Boolean termsAccepted,
      Boolean privacyNoticeAcknowledged) {}

  public record PolicyAcceptanceResponse(
      String termsAcceptedVersion,
      Instant termsAcceptedAt,
      String privacyNoticeAcknowledgedVersion,
      Instant privacyNoticeAcknowledgedAt) {}

  public record AcceptanceResult(
      AcceptanceOutcome outcome,
      PolicyAcceptanceResponse acceptance,
      CurrentPolicies currentPolicies) {

    static AcceptanceResult accepted(PolicyAcceptanceResponse acceptance) {
      return new AcceptanceResult(AcceptanceOutcome.ACCEPTED, acceptance, null);
    }

    static AcceptanceResult stale(CurrentPolicies currentPolicies) {
      return new AcceptanceResult(AcceptanceOutcome.STALE, null, currentPolicies);
    }

    static AcceptanceResult notConfigured() {
      return new AcceptanceResult(AcceptanceOutcome.NOT_CONFIGURED, null, null);
    }
  }
}
