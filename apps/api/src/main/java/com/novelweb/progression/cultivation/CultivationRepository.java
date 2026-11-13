package com.novelweb.progression.cultivation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class CultivationRepository {

  private static final String DEFAULT_LOCALE = "vi-VN";

  private final DataSource dataSource;

  public CultivationRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public CultivationSnapshot find(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    try (Connection connection = dataSource.getConnection()) {
      ProfileState profile = selectProfileState(connection, actorUserId);
      return snapshot(connection, profile);
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load cultivation profile", exception);
    }
  }

  public CultivationSnapshot setEnabled(UUID actorUserId, boolean enabled) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireActiveUserForUpdate(connection, actorUserId);
        ProfileState current = selectProfileState(connection, actorUserId);
        if (current.enabled() == enabled) {
          CultivationSnapshot unchanged = snapshot(connection, current);
          connection.commit();
          return unchanged;
        }

        ProfileState updated;
        if (current.profileVersion() == 0) {
          if (!enabled) {
            CultivationSnapshot unchanged = snapshot(connection, current);
            connection.commit();
            return unchanged;
          }
          updated = insertEnabledProfile(connection, actorUserId, current.catalogVersion());
        } else {
          updated = updateEnabledProfile(connection, actorUserId, enabled);
          if (!enabled) closeActiveReadingSessions(connection, actorUserId);
        }

        CultivationSnapshot saved = snapshot(connection, updated);
        connection.commit();
        return saved;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to update cultivation settings", exception);
    }
  }

  private static ProfileState selectProfileState(Connection connection, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT COALESCE(p.enabled, FALSE) AS enabled,
                       COALESCE(p.catalog_version, active_catalog.version) AS catalog_version,
                       COALESCE(p.version, 0) AS profile_version
                FROM users u
                LEFT JOIN cultivation_profiles p ON p.user_id = u.id
                LEFT JOIN LATERAL (
                    SELECT version
                    FROM cultivation_rank_catalogs
                    WHERE effective_at <= clock_timestamp()
                    ORDER BY effective_at DESC, version DESC
                    LIMIT 1
                ) active_catalog ON TRUE
                WHERE u.id = ? AND u.status = 'active'
                """)) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new CultivationProfileNotFoundException();
        String version = row.getString("catalog_version");
        if (version == null) throw new RankCatalogUnavailableException();
        return new ProfileState(row.getBoolean("enabled"), version, row.getLong("profile_version"));
      }
    }
  }

  private static CultivationSnapshot snapshot(Connection connection, ProfileState profile)
      throws SQLException {
    String policyVersion;
    int xpPerActiveMinute;
    int dailyXpCap;
    List<RankRecord> ranks = new ArrayList<>();
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT c.policy_version, c.xp_per_active_minute, c.daily_xp_cap,
                       e.rank_key, e.group_label, e.display_name, e.threshold_xp, e.sort_order
                FROM cultivation_rank_catalogs c
                JOIN cultivation_rank_catalog_entries e ON e.catalog_version = c.version
                WHERE c.version = ? AND e.locale = ?
                ORDER BY e.sort_order
                """)) {
      select.setString(1, profile.catalogVersion());
      select.setString(2, DEFAULT_LOCALE);
      try (ResultSet rows = select.executeQuery()) {
        if (!rows.next()) throw new RankCatalogUnavailableException();
        policyVersion = rows.getString("policy_version");
        xpPerActiveMinute = rows.getInt("xp_per_active_minute");
        dailyXpCap = rows.getInt("daily_xp_cap");
        do {
          ranks.add(
              new RankRecord(
                  rows.getString("rank_key"),
                  rows.getString("group_label"),
                  rows.getString("display_name"),
                  rows.getLong("threshold_xp"),
                  rows.getInt("sort_order")));
        } while (rows.next());
      }
    }
    validateRanks(ranks);
    return new CultivationSnapshot(
        profile.enabled(),
        profile.profileVersion(),
        profile.catalogVersion(),
        policyVersion,
        DEFAULT_LOCALE,
        xpPerActiveMinute,
        dailyXpCap,
        0,
        List.copyOf(ranks));
  }

  private static void validateRanks(List<RankRecord> ranks) {
    if (ranks.isEmpty() || ranks.getFirst().thresholdXp() != 0) {
      throw new RankCatalogUnavailableException();
    }
    long previousThreshold = -1;
    for (int index = 0; index < ranks.size(); index++) {
      RankRecord rank = ranks.get(index);
      if (rank.sortOrder() != index || rank.thresholdXp() <= previousThreshold) {
        throw new RankCatalogUnavailableException();
      }
      previousThreshold = rank.thresholdXp();
    }
  }

  private static ProfileState insertEnabledProfile(
      Connection connection, UUID actorUserId, String catalogVersion) throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
                INSERT INTO cultivation_profiles (user_id, enabled, catalog_version, version)
                VALUES (?, TRUE, ?, 1)
                RETURNING enabled, catalog_version, version
                """)) {
      insert.setObject(1, actorUserId);
      insert.setString(2, catalogVersion);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new SQLException("Cultivation profile insert returned no row");
        return profileState(row);
      }
    }
  }

  private static ProfileState updateEnabledProfile(
      Connection connection, UUID actorUserId, boolean enabled) throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            """
                UPDATE cultivation_profiles
                SET enabled = ?, version = version + 1, updated_at = clock_timestamp()
                WHERE user_id = ?
                RETURNING enabled, catalog_version, version
                """)) {
      update.setBoolean(1, enabled);
      update.setObject(2, actorUserId);
      try (ResultSet row = update.executeQuery()) {
        if (!row.next()) throw new SQLException("Cultivation profile disappeared during update");
        return profileState(row);
      }
    }
  }

  private static void closeActiveReadingSessions(Connection connection, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement close =
        connection.prepareStatement(
            """
                UPDATE cultivation_reading_sessions
                SET state = 'revoked',
                    closed_at = clock_timestamp(),
                    last_heartbeat_at = clock_timestamp(),
                    last_client_active = FALSE
                WHERE user_id = ? AND state = 'active'
                """)) {
      close.setObject(1, actorUserId);
      close.executeUpdate();
    }
  }

  private static ProfileState profileState(ResultSet row) throws SQLException {
    return new ProfileState(
        row.getBoolean("enabled"), row.getString("catalog_version"), row.getLong("version"));
  }

  private static void requireActiveUserForUpdate(Connection connection, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE")) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new CultivationProfileNotFoundException();
      }
    }
  }

  private static void rollback(Connection connection, Exception exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record ProfileState(boolean enabled, String catalogVersion, long profileVersion) {}

  public record RankRecord(
      String key, String group, String name, long thresholdXp, int sortOrder) {}

  public record CultivationSnapshot(
      boolean enabled,
      long profileVersion,
      String rankCatalogVersion,
      String policyVersion,
      String locale,
      int xpPerActiveMinute,
      int dailyXpCap,
      long totalXp,
      List<RankRecord> ranks) {}

  public static final class CultivationProfileNotFoundException extends RuntimeException {}

  public static final class RankCatalogUnavailableException extends RuntimeException {}
}
