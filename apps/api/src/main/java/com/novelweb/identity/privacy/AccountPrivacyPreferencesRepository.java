package com.novelweb.identity.privacy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Stores reversible, actor-owned optional privacy choices in PostgreSQL. */
@Repository
@Profile("postgres")
public class AccountPrivacyPreferencesRepository {

  private static final Preferences DEFAULTS = new Preferences(false, false, 0, null);
  private static final String RETURNING_COLUMNS =
      "RETURNING analytics_enabled, content_provider_sharing_enabled, version, updated_at";
  private static final String INITIAL_INSERT =
      """
          INSERT INTO account_privacy_preferences (
              user_id, analytics_enabled, content_provider_sharing_enabled, version
          ) VALUES (?, ?, ?, 1)
          ON CONFLICT (user_id) DO UPDATE SET
              analytics_enabled = EXCLUDED.analytics_enabled,
              content_provider_sharing_enabled = EXCLUDED.content_provider_sharing_enabled,
              version = account_privacy_preferences.version + CASE
                  WHEN account_privacy_preferences.analytics_enabled IS DISTINCT FROM EXCLUDED.analytics_enabled
                    OR account_privacy_preferences.content_provider_sharing_enabled IS DISTINCT FROM EXCLUDED.content_provider_sharing_enabled
                  THEN 1 ELSE 0 END,
              updated_at = CASE
                  WHEN account_privacy_preferences.analytics_enabled IS DISTINCT FROM EXCLUDED.analytics_enabled
                    OR account_privacy_preferences.content_provider_sharing_enabled IS DISTINCT FROM EXCLUDED.content_provider_sharing_enabled
                  THEN clock_timestamp()
                  ELSE account_privacy_preferences.updated_at END
          WHERE account_privacy_preferences.version = 0
          """
          + RETURNING_COLUMNS;
  private static final String VERSIONED_UPDATE =
      """
          UPDATE account_privacy_preferences SET
              analytics_enabled = ?,
              content_provider_sharing_enabled = ?,
              version = version + CASE
                  WHEN analytics_enabled IS DISTINCT FROM ? OR content_provider_sharing_enabled IS DISTINCT FROM ?
                  THEN 1 ELSE 0 END,
              updated_at = CASE
                  WHEN analytics_enabled IS DISTINCT FROM ? OR content_provider_sharing_enabled IS DISTINCT FROM ?
                  THEN clock_timestamp()
                  ELSE updated_at END
          WHERE user_id = ? AND version = ?
          """
          + RETURNING_COLUMNS;

  private final DataSource dataSource;

  public AccountPrivacyPreferencesRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public Preferences find(UUID userId) {
    Objects.requireNonNull(userId, "user id is required");
    try (var connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT analytics_enabled, content_provider_sharing_enabled, version, updated_at
                    FROM account_privacy_preferences
                    WHERE user_id = ?
                    """)) {
      select.setObject(1, userId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return DEFAULTS;
        return new Preferences(
            row.getBoolean("analytics_enabled"),
            row.getBoolean("content_provider_sharing_enabled"),
            row.getLong("version"),
            row.getTimestamp("updated_at").toInstant());
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to read account privacy preferences", exception);
    }
  }

  public Optional<Preferences> update(
      UUID userId,
      boolean analyticsEnabled,
      boolean contentProviderSharingEnabled,
      long expectedVersion) {
    Objects.requireNonNull(userId, "user id is required");
    if (expectedVersion < 0) throw new IllegalArgumentException("expected version is invalid");
    if (expectedVersion == 0 && !analyticsEnabled && !contentProviderSharingEnabled) {
      Preferences current = find(userId);
      return current.version() == 0 ? Optional.of(current) : Optional.empty();
    }
    try (var connection = dataSource.getConnection()) {
      String sql = expectedVersion == 0 ? INITIAL_INSERT : VERSIONED_UPDATE;
      try (PreparedStatement mutation = connection.prepareStatement(sql)) {
        if (expectedVersion == 0) {
          mutation.setObject(1, userId);
          mutation.setBoolean(2, analyticsEnabled);
          mutation.setBoolean(3, contentProviderSharingEnabled);
        } else {
          mutation.setBoolean(1, analyticsEnabled);
          mutation.setBoolean(2, contentProviderSharingEnabled);
          mutation.setBoolean(3, analyticsEnabled);
          mutation.setBoolean(4, contentProviderSharingEnabled);
          mutation.setBoolean(5, analyticsEnabled);
          mutation.setBoolean(6, contentProviderSharingEnabled);
          mutation.setObject(7, userId);
          mutation.setLong(8, expectedVersion);
        }
        try (ResultSet row = mutation.executeQuery()) {
          if (!row.next()) return Optional.empty();
          return Optional.of(
              new Preferences(
                  row.getBoolean("analytics_enabled"),
                  row.getBoolean("content_provider_sharing_enabled"),
                  row.getLong("version"),
                  row.getTimestamp("updated_at").toInstant()));
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to update account privacy preferences", exception);
    }
  }

  public record Preferences(
      boolean analyticsEnabled,
      boolean contentProviderSharingEnabled,
      long version,
      Instant updatedAt) {}
}
