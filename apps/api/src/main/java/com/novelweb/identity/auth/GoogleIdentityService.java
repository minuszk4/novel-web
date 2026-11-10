package com.novelweb.identity.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class GoogleIdentityService {

    private static final String GOOGLE_PROVIDER = "google";
    private static final String CANONICAL_GOOGLE_ISSUER = "https://accounts.google.com";
    private static final int MAX_DISPLAY_NAME_LENGTH = 200;

    private final DataSource dataSource;

    public GoogleIdentityService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public IdentityAccount findOrCreate(GoogleIdTokenVerifier.VerifiedGoogleIdentity verifiedIdentity) {
        Objects.requireNonNull(verifiedIdentity, "verified Google identity is required");
        if (verifiedIdentity.subject() == null || verifiedIdentity.subject().isBlank()
                || !CANONICAL_GOOGLE_ISSUER.equals(verifiedIdentity.issuer())
                || verifiedIdentity.email() == null || verifiedIdentity.email().isBlank()
                || verifiedIdentity.email().indexOf('@') <= 0
                || verifiedIdentity.email().indexOf('@') == verifiedIdentity.email().length() - 1) {
            throw new InvalidGoogleCredentialException();
        }

        for (int retry = 0; retry < 2; retry++) {
            try {
                return findOrCreateInTransaction(verifiedIdentity);
            } catch (IdentityInsertRaceException race) {
                if (retry == 1) {
                    throw new IllegalStateException("Unable to resolve a concurrent Google sign-in", race);
                }
            }
        }
        throw new IllegalStateException("Unable to resolve a Google sign-in");
    }

    private IdentityAccount findOrCreateInTransaction(
            GoogleIdTokenVerifier.VerifiedGoogleIdentity identity) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                IdentityAccount existing = findExisting(connection, identity.issuer(), identity.subject());
                IdentityAccount account = existing == null
                        ? createNewAccount(connection, identity)
                        : updateExistingAccount(connection, existing, identity);
                connection.commit();
                return account;
            } catch (IdentityInsertRaceException | GoogleAccountLinkRequiredException
                    | UnavailableGoogleIdentityException | InvalidGoogleCredentialException exception) {
                connection.rollback();
                throw exception;
            } catch (SQLException exception) {
                connection.rollback();
                if ("23505".equals(exception.getSQLState())) {
                    throw new GoogleAccountLinkRequiredException();
                }
                throw new IllegalStateException("Unable to persist a Google identity", exception);
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new GoogleAccountLinkRequiredException();
            }
            throw new IllegalStateException("Unable to connect while resolving a Google identity", exception);
        }
    }

    private static IdentityAccount findExisting(Connection connection, String issuer, String subject) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT u.id, u.display_name, u.contact_email, u.status
                FROM external_identities ei
                JOIN users u ON u.id = ei.user_id
                WHERE ei.provider = ? AND ei.issuer = ? AND ei.subject = ?
                FOR UPDATE OF ei, u
                """)) {
            select.setString(1, GOOGLE_PROVIDER);
            select.setString(2, issuer);
            select.setString(3, subject);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return new IdentityAccount(
                        row.getObject("id", UUID.class),
                        row.getString("display_name"),
                        row.getString("contact_email"),
                        row.getString("status"),
                        false);
            }
        }
    }

    private static IdentityAccount createNewAccount(
            Connection connection,
            GoogleIdTokenVerifier.VerifiedGoogleIdentity identity) throws SQLException {
        UUID userId = UUID.randomUUID();
        String displayName = displayName(identity.displayName(), identity.email());
        try (PreparedStatement insertUser = connection.prepareStatement("""
                INSERT INTO users (id, display_name, contact_email, email_verified_at, status)
                VALUES (?, ?, ?, clock_timestamp(), 'active')
                ON CONFLICT DO NOTHING
                RETURNING id, display_name, contact_email, status
                """)) {
            insertUser.setObject(1, userId);
            insertUser.setString(2, displayName);
            insertUser.setString(3, identity.email());
            try (ResultSet row = insertUser.executeQuery()) {
                if (!row.next()) {
                    if (findExisting(connection, identity.issuer(), identity.subject()) != null) {
                        throw new IdentityInsertRaceException();
                    }
                    throw new GoogleAccountLinkRequiredException();
                }
            }
        }

        try (PreparedStatement insertIdentity = connection.prepareStatement("""
                INSERT INTO external_identities (
                    id, user_id, provider, issuer, subject, provider_email, provider_email_verified, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, TRUE, clock_timestamp())
                ON CONFLICT (issuer, subject) DO NOTHING
                RETURNING user_id
                """)) {
            insertIdentity.setObject(1, UUID.randomUUID());
            insertIdentity.setObject(2, userId);
            insertIdentity.setString(3, GOOGLE_PROVIDER);
            insertIdentity.setString(4, identity.issuer());
            insertIdentity.setString(5, identity.subject());
            insertIdentity.setString(6, identity.email());
            try (ResultSet row = insertIdentity.executeQuery()) {
                if (!row.next()) {
                    throw new IdentityInsertRaceException();
                }
            }
        }

        return new IdentityAccount(userId, displayName, identity.email(), "active", true);
    }

    private static IdentityAccount updateExistingAccount(
            Connection connection,
            IdentityAccount existing,
            GoogleIdTokenVerifier.VerifiedGoogleIdentity identity) throws SQLException {
        if (!"active".equals(existing.status()) && !"pending".equals(existing.status())) {
            throw new UnavailableGoogleIdentityException();
        }

        String updatedDisplayName = identity.displayName() == null || identity.displayName().isBlank()
                ? existing.displayName()
                : displayName(identity.displayName(), identity.email());
        try (PreparedStatement updateUser = connection.prepareStatement("""
                UPDATE users
                SET display_name = ?, contact_email = ?, email_verified_at = clock_timestamp(),
                    updated_at = clock_timestamp()
                WHERE id = ? AND status IN ('active', 'pending')
                RETURNING id, display_name, contact_email, status
                """)) {
            updateUser.setString(1, updatedDisplayName);
            updateUser.setString(2, identity.email());
            updateUser.setObject(3, existing.userId());
            try (ResultSet row = updateUser.executeQuery()) {
                if (!row.next()) {
                    throw new UnavailableGoogleIdentityException();
                }
                updatedDisplayName = row.getString("display_name");
            }
        }

        try (PreparedStatement updateIdentity = connection.prepareStatement("""
                UPDATE external_identities
                SET provider_email = ?, provider_email_verified = TRUE, last_seen_at = clock_timestamp()
                WHERE provider = ? AND issuer = ? AND subject = ? AND user_id = ?
                """)) {
            updateIdentity.setString(1, identity.email());
            updateIdentity.setString(2, GOOGLE_PROVIDER);
            updateIdentity.setString(3, identity.issuer());
            updateIdentity.setString(4, identity.subject());
            updateIdentity.setObject(5, existing.userId());
            if (updateIdentity.executeUpdate() != 1) {
                throw new UnavailableGoogleIdentityException();
            }
        }

        return new IdentityAccount(existing.userId(), updatedDisplayName, identity.email(), existing.status(), false);
    }

    private static String displayName(String preferred, String email) {
        String name = preferred == null || preferred.isBlank() ? email.substring(0, email.indexOf('@')) : preferred.trim();
        if (name.isBlank()) {
            name = "Reader";
        }
        return name.length() <= MAX_DISPLAY_NAME_LENGTH ? name : name.substring(0, MAX_DISPLAY_NAME_LENGTH).trim();
    }

    public record IdentityAccount(
            UUID userId,
            String displayName,
            String contactEmail,
            String status,
            boolean created) {
    }

    private static final class IdentityInsertRaceException extends RuntimeException {
    }
}
