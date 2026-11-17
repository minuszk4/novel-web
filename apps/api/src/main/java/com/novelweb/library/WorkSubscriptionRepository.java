package com.novelweb.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Actor-owned follow state for works that are currently public. */
@Repository
@Profile("postgres")
public class WorkSubscriptionRepository {

  private final DataSource dataSource;

  public WorkSubscriptionRepository(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
  }

  public SubscriptionState find(UUID actorUserId, UUID workId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      requireActiveActor(connection, actorUserId);
      requirePublicWork(connection, workId, false);
      return select(connection, actorUserId, workId, false).orElse(SubscriptionState.absent());
    } catch (SubscriptionNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load work subscription", exception);
    }
  }

  public SubscriptionState setNotifications(
      UUID actorUserId, UUID workId, boolean enabled, long expectedVersion) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireActiveActor(connection, actorUserId);
        requirePublicWork(connection, workId, true);
        Optional<SubscriptionState> existing = select(connection, actorUserId, workId, true);
        if (existing.isEmpty()) {
          if (!enabled) throw new NotFollowingException();
          if (expectedVersion != 0) throw new StaleSubscriptionException();
          if (insert(connection, actorUserId, workId)) {
            connection.commit();
            return new SubscriptionState(true, true, 1);
          }
          SubscriptionState raced = select(connection, actorUserId, workId, true).orElseThrow();
          if (raced.following() && raced.notificationsEnabled()) {
            connection.commit();
            return raced;
          }
          throw new StaleSubscriptionException();
        }

        SubscriptionState current = existing.orElseThrow();
        if (!current.following() && !enabled) throw new NotFollowingException();
        if (current.following() && current.notificationsEnabled() == enabled) {
          connection.commit();
          return current;
        }
        if (current.version() != expectedVersion) throw new StaleSubscriptionException();

        SubscriptionState updated = update(connection, current, actorUserId, workId, enabled);
        connection.commit();
        return updated;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SubscriptionNotFoundException
        | NotFollowingException
        | StaleSubscriptionException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to save work subscription", exception);
    }
  }

  public SubscriptionState unfollow(UUID actorUserId, UUID workId, Long expectedVersion) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        requireActiveActor(connection, actorUserId);
        // A previously-followed work may have been withdrawn or taken down. Its owner-scoped
        // subscription row is still safe to retire and must not become impossible to clean up.
        Optional<SubscriptionState> existing = select(connection, actorUserId, workId, true);
        if (existing.isEmpty()) {
          connection.commit();
          return SubscriptionState.absent();
        }
        SubscriptionState current = existing.orElseThrow();
        if (!current.following()) {
          connection.commit();
          return current;
        }
        if (expectedVersion == null) throw new SubscriptionPreconditionRequiredException();
        if (current.version() != expectedVersion) throw new StaleSubscriptionException();
        SubscriptionState updated = deactivate(connection, current, actorUserId, workId);
        connection.commit();
        return updated;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SubscriptionNotFoundException
        | StaleSubscriptionException
        | SubscriptionPreconditionRequiredException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to stop work subscription", exception);
    }
  }

  public SubscriptionSlice listActive(
      UUID actorUserId, LibraryCursorCodec.Position position, int fetchLimit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    if (fetchLimit < 1 || fetchLimit > 51) {
      throw new IllegalArgumentException("subscription fetch limit must be 1 to 51");
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      try {
        requireActiveActor(connection, actorUserId);
        List<SubscriptionItem> selected =
            readActiveSubscriptions(connection, actorUserId, position, fetchLimit);
        boolean hasMore = selected.size() == fetchLimit;
        List<SubscriptionItem> page =
            hasMore ? List.copyOf(selected.subList(0, selected.size() - 1)) : List.copyOf(selected);
        LibraryCursorCodec.Position nextPosition =
            hasMore && !page.isEmpty()
                ? new LibraryCursorCodec.Position(
                    0, page.get(page.size() - 1).followedAt(), page.get(page.size() - 1).id())
                : null;
        connection.commit();
        return new SubscriptionSlice(page, nextPosition);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to list actor work subscriptions", exception);
    }
  }

  private static List<SubscriptionItem> readActiveSubscriptions(
      Connection connection, UUID actorUserId, LibraryCursorCodec.Position position, int fetchLimit)
      throws SQLException {
    String cursorPredicate =
        position == null
            ? ""
            : " AND (subscription.followed_at < ? OR"
                + " (subscription.followed_at = ? AND subscription.id < ?))";
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT subscription.id, subscription.work_id, subscription.version,
                       subscription.notifications_enabled, subscription.followed_at,
                       CASE WHEN work.visibility = 'public' AND work.status = 'active'
                                  AND work.deleted_at IS NULL AND owner.status = 'active'
                                  AND library.deleted_at IS NULL AND library.workspace_id IS NULL
                                  AND publication_scope_access_is_current(work.id, 'PUBLIC_METADATA')
                                  AND EXISTS (
                                      SELECT 1 FROM chapters chapter
                                       WHERE chapter.work_id = work.id
                                         AND chapter.status = 'ready'
                                         AND chapter.deleted_at IS NULL
                                         AND chapter.source_revision_id IS NOT NULL
                                  )
                            THEN TRUE ELSE FALSE END AS available,
                       CASE WHEN work.visibility = 'public' AND work.status = 'active'
                                  AND work.deleted_at IS NULL AND owner.status = 'active'
                                  AND library.deleted_at IS NULL AND library.workspace_id IS NULL
                                  AND publication_scope_access_is_current(work.id, 'PUBLIC_METADATA')
                                  AND EXISTS (
                                      SELECT 1 FROM chapters chapter
                                       WHERE chapter.work_id = work.id
                                         AND chapter.status = 'ready'
                                         AND chapter.deleted_at IS NULL
                                         AND chapter.source_revision_id IS NOT NULL
                                  )
                            THEN work.title ELSE NULL END AS title,
                       CASE WHEN work.visibility = 'public' AND work.status = 'active'
                                  AND work.deleted_at IS NULL AND owner.status = 'active'
                                  AND library.deleted_at IS NULL AND library.workspace_id IS NULL
                                  AND publication_scope_access_is_current(work.id, 'PUBLIC_METADATA')
                                  AND EXISTS (
                                      SELECT 1 FROM chapters chapter
                                       WHERE chapter.work_id = work.id
                                         AND chapter.status = 'ready'
                                         AND chapter.deleted_at IS NULL
                                         AND chapter.source_revision_id IS NOT NULL
                                  )
                            THEN work.author ELSE NULL END AS author
                  FROM work_subscriptions subscription
                  JOIN works work ON work.id = subscription.work_id
                  JOIN users owner ON owner.id = work.owner_user_id
                  JOIN libraries library ON library.id = work.library_id
                 WHERE subscription.actor_user_id = ? AND subscription.active = TRUE
                """
                + cursorPredicate
                + " ORDER BY subscription.followed_at DESC, subscription.id DESC LIMIT ?")) {
      int parameter = 1;
      select.setObject(parameter++, actorUserId);
      if (position != null) {
        OffsetDateTime cursorTime = OffsetDateTime.ofInstant(position.createdAt(), ZoneOffset.UTC);
        select.setObject(parameter++, cursorTime);
        select.setObject(parameter++, cursorTime);
        select.setObject(parameter++, position.itemId());
      }
      select.setInt(parameter, fetchLimit);
      try (ResultSet rows = select.executeQuery()) {
        List<SubscriptionItem> items = new ArrayList<>(fetchLimit);
        while (rows.next()) {
          boolean available = rows.getBoolean("available");
          items.add(
              new SubscriptionItem(
                  rows.getObject("id", UUID.class),
                  rows.getObject("work_id", UUID.class),
                  available,
                  available && rows.getBoolean("notifications_enabled"),
                  rows.getLong("version"),
                  rows.getObject("followed_at", java.time.OffsetDateTime.class).toInstant(),
                  rows.getString("title"),
                  rows.getString("author")));
        }
        return List.copyOf(items);
      }
    }
  }

  private static void requireActiveActor(Connection connection, UUID actorUserId)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement("SELECT 1 FROM users WHERE id = ? AND status = 'active'")) {
      select.setObject(1, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SubscriptionNotFoundException();
      }
    }
  }

  private static void requirePublicWork(Connection connection, UUID workId, boolean lock)
      throws SQLException {
    String lockClause = lock ? " FOR SHARE OF work, owner, library" : "";
    String sql =
        """
            SELECT work.id
            FROM works work
            JOIN libraries library ON library.id = work.library_id
              AND library.owner_user_id = work.owner_user_id AND library.deleted_at IS NULL
            JOIN users owner ON owner.id = work.owner_user_id AND owner.status = 'active'
            WHERE work.id = ? AND work.source_kind = 'authored'
              AND work.visibility = 'public' AND work.status = 'active'
              AND work.deleted_at IS NULL
              AND publication_scope_access_is_current(work.id, 'PUBLIC_METADATA')
              AND EXISTS (
                SELECT 1 FROM chapters chapter WHERE chapter.work_id = work.id
                  AND chapter.status = 'ready' AND chapter.deleted_at IS NULL
                  AND chapter.source_revision_id IS NOT NULL
              )
            """
            + lockClause;
    try (PreparedStatement select = connection.prepareStatement(sql)) {
      select.setObject(1, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new SubscriptionNotFoundException();
      }
    }
  }

  private static Optional<SubscriptionState> select(
      Connection connection, UUID actorUserId, UUID workId, boolean lock) throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            "SELECT active, notifications_enabled, version FROM work_subscriptions "
                + "WHERE actor_user_id = ? AND work_id = ?"
                + (lock ? " FOR UPDATE" : ""))) {
      select.setObject(1, actorUserId);
      select.setObject(2, workId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) return Optional.empty();
        return Optional.of(
            new SubscriptionState(
                row.getBoolean("active"),
                row.getBoolean("notifications_enabled"),
                row.getLong("version")));
      }
    }
  }

  private static boolean insert(Connection connection, UUID actorUserId, UUID workId)
      throws SQLException {
    try (PreparedStatement insert =
        connection.prepareStatement(
            "INSERT INTO work_subscriptions (id, actor_user_id, work_id, active, "
                + "notifications_enabled, version) VALUES (?, ?, ?, TRUE, TRUE, 1) "
                + "ON CONFLICT (actor_user_id, work_id) DO NOTHING")) {
      insert.setObject(1, UUID.randomUUID());
      insert.setObject(2, actorUserId);
      insert.setObject(3, workId);
      return insert.executeUpdate() == 1;
    }
  }

  private static SubscriptionState update(
      Connection connection,
      SubscriptionState current,
      UUID actorUserId,
      UUID workId,
      boolean enabled)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE work_subscriptions SET active = TRUE, notifications_enabled = ?, "
                + "version = version + 1 WHERE actor_user_id = ? AND work_id = ? "
                + "AND version = ?")) {
      update.setBoolean(1, enabled);
      update.setObject(2, actorUserId);
      update.setObject(3, workId);
      update.setLong(4, current.version());
      if (update.executeUpdate() != 1) throw new StaleSubscriptionException();
      return new SubscriptionState(true, enabled, current.version() + 1);
    }
  }

  private static SubscriptionState deactivate(
      Connection connection, SubscriptionState current, UUID actorUserId, UUID workId)
      throws SQLException {
    try (PreparedStatement update =
        connection.prepareStatement(
            "UPDATE work_subscriptions SET active = FALSE, notifications_enabled = FALSE, "
                + "version = version + 1 WHERE actor_user_id = ? AND work_id = ? AND version = ?")) {
      update.setObject(1, actorUserId);
      update.setObject(2, workId);
      update.setLong(3, current.version());
      if (update.executeUpdate() != 1) throw new StaleSubscriptionException();
      return new SubscriptionState(false, false, current.version() + 1);
    }
  }

  public record SubscriptionState(boolean following, boolean notificationsEnabled, long version) {
    static SubscriptionState absent() {
      return new SubscriptionState(false, false, 0);
    }
  }

  public record SubscriptionItem(
      UUID id,
      UUID workId,
      boolean available,
      boolean notificationsEnabled,
      long version,
      java.time.Instant followedAt,
      String title,
      String author) {}

  public record SubscriptionSlice(
      List<SubscriptionItem> items, LibraryCursorCodec.Position nextPosition) {
    public SubscriptionSlice {
      items = List.copyOf(items);
    }
  }

  public static class SubscriptionNotFoundException extends RuntimeException {}

  public static class NotFollowingException extends RuntimeException {}

  public static class StaleSubscriptionException extends RuntimeException {}

  public static class SubscriptionPreconditionRequiredException extends RuntimeException {}
}
