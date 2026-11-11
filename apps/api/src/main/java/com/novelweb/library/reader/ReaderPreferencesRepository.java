package com.novelweb.library.reader;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Stores display-only Reader preferences on the current user's account. */
@Repository
@Profile("postgres")
public class ReaderPreferencesRepository {

  private static final PreferencesRecord DEFAULTS =
      new PreferencesRecord(1, "light", "serif", 20, 1.8, 720);

  private final DataSource dataSource;

  public ReaderPreferencesRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public PreferencesRecord find(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    try (Connection connection = dataSource.getConnection()) {
      return select(connection, actorUserId, false);
    } catch (PreferencesNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load Reader preferences", exception);
    }
  }

  public PreferencesRecord save(UUID actorUserId, PreferencesRecord preferences) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(preferences, "Reader preferences are required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireActiveUser(connection, actorUserId);
        PreferencesRecord current = select(connection, actorUserId, true);
        if (current.equals(preferences)) {
          connection.commit();
          return current;
        }
        try (PreparedStatement upsert =
            connection.prepareStatement(
                """
                    INSERT INTO reader_preferences
                        (user_id, schema_version, theme, font_family, font_size, line_height, content_width)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET
                        schema_version = EXCLUDED.schema_version,
                        theme = EXCLUDED.theme,
                        font_family = EXCLUDED.font_family,
                        font_size = EXCLUDED.font_size,
                        line_height = EXCLUDED.line_height,
                        content_width = EXCLUDED.content_width,
                        updated_at = clock_timestamp()
                    """)) {
          upsert.setObject(1, actorUserId);
          upsert.setInt(2, preferences.schemaVersion());
          upsert.setString(3, preferences.theme());
          upsert.setString(4, preferences.fontFamily());
          upsert.setInt(5, preferences.fontSize());
          upsert.setDouble(6, preferences.lineHeight());
          upsert.setInt(7, preferences.contentWidth());
          upsert.executeUpdate();
        }
        PreferencesRecord saved = select(connection, actorUserId, false);
        connection.commit();
        return saved;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (PreferencesNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to save Reader preferences", exception);
    }
  }

  private static void requireActiveUser(Connection connection, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT id FROM users WHERE id = ? AND status = 'active' FOR SHARE")) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new PreferencesNotFoundException();
      }
    }
  }

  private static PreferencesRecord select(Connection connection, UUID actorUserId, boolean lock)
      throws SQLException {
    String lockClause = lock ? " FOR UPDATE OF u" : "";
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT p.user_id IS NOT NULL AS has_preferences,
                       p.schema_version, p.theme, p.font_family, p.font_size,
                       p.line_height, p.content_width
                FROM users u
                LEFT JOIN reader_preferences p ON p.user_id = u.id
                WHERE u.id = ? AND u.status = 'active'
                """
                + lockClause)) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new PreferencesNotFoundException();
        if (!row.getBoolean("has_preferences")) return DEFAULTS;
        return new PreferencesRecord(
            row.getInt("schema_version"),
            row.getString("theme"),
            row.getString("font_family"),
            row.getInt("font_size"),
            row.getDouble("line_height"),
            row.getInt("content_width"));
      }
    }
  }

  public record PreferencesRecord(
      int schemaVersion,
      String theme,
      String fontFamily,
      int fontSize,
      double lineHeight,
      int contentWidth) {}

  public static final class PreferencesNotFoundException extends RuntimeException {}
}
