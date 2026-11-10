package com.novelweb.identity.auth;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class CurrentUserService {

    private final DataSource dataSource;
    public CurrentUserService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<CurrentUserResponse> findActiveUser(UUID userId) {
        Objects.requireNonNull(userId, "user id is required");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT u.id, u.display_name, u.contact_email, u.status,
                            COALESCE((
                                SELECT array_agg(e.provider ORDER BY e.provider)
                                FROM external_identities e
                                WHERE e.user_id = u.id
                            ), ARRAY[]::VARCHAR[]) AS identity_providers,
                            (
                                SELECT p.version
                                FROM policy_acceptances p
                                WHERE p.user_id = u.id
                                  AND p.policy_type = 'TERMS'
                                  AND p.decision = 'TERMS_ACCEPTED'
                                ORDER BY p.recorded_at DESC, p.id DESC
                                LIMIT 1
                            ) AS terms_accepted_version,
                            (
                                SELECT p.version
                                FROM policy_acceptances p
                                WHERE p.user_id = u.id
                                  AND p.policy_type = 'PRIVACY_NOTICE'
                                  AND p.decision = 'PRIVACY_NOTICE_ACKNOWLEDGED'
                                ORDER BY p.recorded_at DESC, p.id DESC
                                LIMIT 1
                            ) AS privacy_notice_acknowledged_version,
                            (
                                SELECT d.version
                                FROM policy_documents d
                                WHERE d.policy_type = 'TERMS'
                                  AND d.effective_at <= clock_timestamp()
                                ORDER BY d.effective_at DESC
                                LIMIT 1
                            ) AS current_terms_version,
                            (
                                SELECT d.version
                                FROM policy_documents d
                                WHERE d.policy_type = 'PRIVACY_NOTICE'
                                  AND d.effective_at <= clock_timestamp()
                                ORDER BY d.effective_at DESC
                                LIMIT 1
                            ) AS current_privacy_notice_version
                     FROM users u
                     WHERE u.id = ? AND u.status = 'active'
                     """)) {
            select.setObject(1, userId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }

                String acceptedTermsVersion = row.getString("terms_accepted_version");
                String acknowledgedPrivacyVersion = row.getString("privacy_notice_acknowledged_version");
                String currentTermsVersion = row.getString("current_terms_version");
                String currentPrivacyNoticeVersion = row.getString("current_privacy_notice_version");
                boolean requiredAcceptanceComplete = currentTermsVersion != null
                        && currentPrivacyNoticeVersion != null
                        && currentTermsVersion.equals(acceptedTermsVersion)
                        && currentPrivacyNoticeVersion.equals(acknowledgedPrivacyVersion);

                CurrentUser user = new CurrentUser(
                        row.getObject("id", UUID.class),
                        row.getString("display_name"),
                        row.getString("contact_email"),
                        null,
                        row.getString("status"));

                return Optional.of(new CurrentUserResponse(
                        user,
                        identityProviders(row.getArray("identity_providers")),
                        new UserPolicyState(
                                acceptedTermsVersion,
                                acknowledgedPrivacyVersion,
                                requiredAcceptanceComplete),
                        List.of(new RoleScope("personal", List.of())),
                        new UserFeatureFlags(false, false, false, false, false)));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load the current application user", exception);
        }
    }

    private static List<String> identityProviders(Array providerArray) throws SQLException {
        if (providerArray == null) {
            return List.of();
        }
        try {
            Object values = providerArray.getArray();
            if (!(values instanceof Object[] array)) {
                return List.of();
            }
            List<String> providers = new ArrayList<>(array.length);
            for (Object value : array) {
                if (value != null) {
                    providers.add(value.toString());
                }
            }
            return List.copyOf(providers);
        } finally {
            providerArray.free();
        }
    }

    public record CurrentUserResponse(
            CurrentUser user,
            List<String> identityProviders,
            UserPolicyState policy,
            List<RoleScope> roles,
            UserFeatureFlags features) {
    }

    public record CurrentUser(UUID id, String displayName, String contactEmail, String avatarUrl, String status) {
    }

    public record UserPolicyState(
            String termsAcceptedVersion,
            String privacyNoticeAcknowledgedVersion,
            boolean requiredAcceptanceComplete) {
    }

    public record RoleScope(String scope, List<String> permissions) {
    }

    public record UserFeatureFlags(
            boolean privateLibraryEnabled,
            boolean importEnabled,
            boolean readerEnabled,
            boolean cultivationEnabled,
            boolean commerceSandboxEnabled) {
    }
}
