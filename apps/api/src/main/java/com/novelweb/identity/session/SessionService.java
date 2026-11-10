package com.novelweb.identity.session;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class SessionService {

    private static final int SESSION_SECRET_BYTES = 32;
    private static final Pattern SESSION_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Duration MAXIMUM_ABSOLUTE_TTL = Duration.ofDays(90);

    private final DataSource dataSource;
    private final Duration absoluteTtl;
    private final Duration idleTtl;
    private final Duration touchInterval;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionService(
            DataSource dataSource,
            @Value("${novel.identity.session.absolute-ttl:30d}") Duration absoluteTtl,
            @Value("${novel.identity.session.idle-ttl:7d}") Duration idleTtl,
            @Value("${novel.identity.session.touch-interval:5m}") Duration touchInterval) {
        this.dataSource = dataSource;
        if (absoluteTtl.isZero() || absoluteTtl.isNegative()
                || absoluteTtl.compareTo(MAXIMUM_ABSOLUTE_TTL) > 0
                || idleTtl.isZero() || idleTtl.isNegative() || idleTtl.compareTo(absoluteTtl) > 0
                || touchInterval.isZero() || touchInterval.isNegative() || touchInterval.compareTo(idleTtl) > 0) {
            throw new IllegalArgumentException("Session TTLs must be positive, bounded and ordered");
        }
        this.absoluteTtl = absoluteTtl;
        this.idleTtl = idleTtl;
        this.touchInterval = touchInterval;
    }

    public Optional<IssuedSession> create(UUID userId) {
        Objects.requireNonNull(userId, "user id is required");
        String sessionToken = newSessionToken();
        byte[] tokenHash = hashSessionToken(sessionToken);

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement activeUser = connection.prepareStatement("""
                        SELECT id FROM users
                        WHERE id = ? AND status = 'active'
                        FOR SHARE
                        """)) {
                    activeUser.setObject(1, userId);
                    try (ResultSet row = activeUser.executeQuery()) {
                        if (!row.next()) {
                            connection.commit();
                            return Optional.empty();
                        }
                    }
                }

                Instant expiresAt;
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO sessions (id_hash, user_id, expires_at)
                        VALUES (?, ?, clock_timestamp() + (? * INTERVAL '1 millisecond'))
                        RETURNING expires_at
                        """)) {
                    insert.setBytes(1, tokenHash);
                    insert.setObject(2, userId);
                    insert.setLong(3, absoluteTtl.toMillis());
                    try (ResultSet row = insert.executeQuery()) {
                        if (!row.next()) {
                            throw new SQLException("Session creation did not return its expiry");
                        }
                        expiresAt = row.getObject("expires_at", OffsetDateTime.class).toInstant();
                    }
                }

                connection.commit();
                return Optional.of(new IssuedSession(sessionToken, userId, expiresAt));
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to create an application session", exception);
        }
    }

    public Optional<SessionPrincipal> validateAndTouch(String sessionToken) {
        byte[] tokenHash = parseAndHashSessionToken(sessionToken);
        if (tokenHash == null) {
            return Optional.empty();
        }

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID userId;
                Instant expiresAt;
                boolean shouldTouch;
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT s.user_id, s.expires_at,
                               s.last_seen_at <= clock_timestamp() - (? * INTERVAL '1 millisecond') AS should_touch
                        FROM sessions s
                        JOIN users u ON u.id = s.user_id
                        WHERE s.id_hash = ?
                          AND s.revoked_at IS NULL
                          AND s.expires_at > clock_timestamp()
                          AND s.last_seen_at > clock_timestamp() - (? * INTERVAL '1 millisecond')
                          AND u.status = 'active'
                        FOR UPDATE OF s FOR SHARE OF u
                        """)) {
                    select.setLong(1, touchInterval.toMillis());
                    select.setBytes(2, tokenHash);
                    select.setLong(3, idleTtl.toMillis());
                    try (ResultSet row = select.executeQuery()) {
                        if (!row.next()) {
                            connection.commit();
                            return Optional.empty();
                        }
                        userId = row.getObject("user_id", UUID.class);
                        expiresAt = row.getObject("expires_at", OffsetDateTime.class).toInstant();
                        shouldTouch = row.getBoolean("should_touch");
                    }
                }

                if (shouldTouch) {
                    try (PreparedStatement touch = connection.prepareStatement("""
                            UPDATE sessions
                            SET last_seen_at = clock_timestamp()
                            WHERE id_hash = ? AND revoked_at IS NULL
                            """)) {
                        touch.setBytes(1, tokenHash);
                        if (touch.executeUpdate() != 1) {
                            throw new SQLException("Session touch affected an unexpected number of rows");
                        }
                    }
                }

                connection.commit();
                return Optional.of(new SessionPrincipal(userId, expiresAt));
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to validate an application session", exception);
        }
    }

    public Optional<IssuedSession> rotate(String currentToken) {
        byte[] currentHash = parseAndHashSessionToken(currentToken);
        if (currentHash == null) {
            return Optional.empty();
        }

        String replacementToken = newSessionToken();
        byte[] replacementHash = hashSessionToken(replacementToken);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                UUID userId;
                Instant absoluteExpiresAt;
                try (PreparedStatement select = connection.prepareStatement("""
                        SELECT s.user_id, s.expires_at
                        FROM sessions s
                        JOIN users u ON u.id = s.user_id
                        WHERE s.id_hash = ?
                          AND s.revoked_at IS NULL
                          AND s.expires_at > clock_timestamp()
                          AND s.last_seen_at > clock_timestamp() - (? * INTERVAL '1 millisecond')
                          AND u.status = 'active'
                        FOR UPDATE OF s FOR SHARE OF u
                        """)) {
                    select.setBytes(1, currentHash);
                    select.setLong(2, idleTtl.toMillis());
                    try (ResultSet row = select.executeQuery()) {
                        if (!row.next()) {
                            connection.commit();
                            return Optional.empty();
                        }
                        userId = row.getObject("user_id", UUID.class);
                        absoluteExpiresAt = row.getObject("expires_at", OffsetDateTime.class).toInstant();
                    }
                }

                try (PreparedStatement revoke = connection.prepareStatement("""
                        UPDATE sessions SET revoked_at = clock_timestamp()
                        WHERE id_hash = ? AND revoked_at IS NULL
                        """)) {
                    revoke.setBytes(1, currentHash);
                    if (revoke.executeUpdate() != 1) {
                        throw new SQLException("Session rotation could not revoke its old session");
                    }
                }

                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO sessions (id_hash, user_id, expires_at)
                        VALUES (?, ?, ?)
                        """)) {
                    insert.setBytes(1, replacementHash);
                    insert.setObject(2, userId);
                    insert.setObject(3, OffsetDateTime.ofInstant(absoluteExpiresAt, java.time.ZoneOffset.UTC));
                    if (insert.executeUpdate() != 1) {
                        throw new SQLException("Session replacement could not be created");
                    }
                }

                connection.commit();
                return Optional.of(new IssuedSession(replacementToken, userId, absoluteExpiresAt));
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to rotate an application session", exception);
        }
    }

    public boolean revoke(String sessionToken) {
        byte[] tokenHash = parseAndHashSessionToken(sessionToken);
        if (tokenHash == null) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement revoke = connection.prepareStatement("""
                     UPDATE sessions SET revoked_at = clock_timestamp()
                     WHERE id_hash = ? AND revoked_at IS NULL
                     """)) {
            revoke.setBytes(1, tokenHash);
            return revoke.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to revoke an application session", exception);
        }
    }

    public int revokeAll(UUID userId) {
        Objects.requireNonNull(userId, "user id is required");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement revoke = connection.prepareStatement("""
                     UPDATE sessions SET revoked_at = clock_timestamp()
                     WHERE user_id = ? AND revoked_at IS NULL
                     """)) {
            revoke.setObject(1, userId);
            return revoke.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to revoke application sessions", exception);
        }
    }

    private String newSessionToken() {
        byte[] secret = new byte[SESSION_SECRET_BYTES];
        secureRandom.nextBytes(secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private static byte[] parseAndHashSessionToken(String token) {
        if (token == null || !SESSION_TOKEN_PATTERN.matcher(token).matches()) {
            return null;
        }
        try {
            byte[] secret = Base64.getUrlDecoder().decode(token);
            if (secret.length != SESSION_SECRET_BYTES
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(secret).equals(token)) {
                return null;
            }
            return hash(secret);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static boolean isCanonicalSessionToken(String token) {
        return parseAndHashSessionToken(token) != null;
    }

    private static byte[] hashSessionToken(String sessionToken) {
        byte[] secret = Base64.getUrlDecoder().decode(sessionToken);
        return hash(secret);
    }

    private static byte[] hash(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void rollback(Connection connection, Exception originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    public record IssuedSession(String cookieValue, UUID userId, Instant expiresAt) {
    }

    public record SessionPrincipal(UUID userId, Instant expiresAt) {
    }
}
