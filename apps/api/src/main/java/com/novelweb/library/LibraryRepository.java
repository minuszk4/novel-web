package com.novelweb.library;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class LibraryRepository {

  private final DataSource dataSource;

  public LibraryRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public String libraryListAccessFingerprint(UUID actorUserId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    MessageDigest digest = accessDigest("library-list-access-v2");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT w.id, w.scope_version, m.role
                     FROM workspace_members m
                     JOIN workspaces w ON w.id = m.workspace_id
                     WHERE m.user_id = ? AND m.status = 'active'
                     ORDER BY w.id
                     """)) {
      select.setObject(1, actorUserId);
      try (ResultSet rows = select.executeQuery()) {
        while (rows.next()) {
          updateUuid(digest, rows.getObject("id", UUID.class));
          digest.update(
              ByteBuffer.allocate(Long.BYTES).putLong(rows.getLong("scope_version")).array());
          updateFingerprintString(digest, rows.getString("role"));
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to fingerprint the actor's library access scope", exception);
    }
  }

  public String workAccessFingerprint(UUID actorUserId, UUID libraryId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT l.workspace_id, w.scope_version, m.role
                     FROM libraries l
                     LEFT JOIN workspaces w ON w.id = l.workspace_id
                     LEFT JOIN workspace_members m
                       ON m.workspace_id = l.workspace_id AND m.user_id = ? AND m.status = 'active'
                     WHERE l.id = ? AND can_access_library(l.id, ?, FALSE)
                     """)) {
      select.setObject(1, actorUserId);
      select.setObject(2, libraryId);
      select.setObject(3, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        if (!row.next()) throw new LibraryNotFoundException();
        UUID workspaceId = row.getObject("workspace_id", UUID.class);
        MessageDigest digest = accessDigest("work-library-access-v2");
        if (workspaceId == null) {
          updateFingerprintString(digest, "personal");
        } else {
          Long scopeVersion = row.getObject("scope_version", Long.class);
          String role = row.getString("role");
          if (scopeVersion == null || role == null) throw new LibraryNotFoundException();
          updateFingerprintString(digest, "workspace");
          updateUuid(digest, workspaceId);
          digest.update(ByteBuffer.allocate(Long.BYTES).putLong(scopeVersion).array());
          updateFingerprintString(digest, role);
        }
        return HexFormat.of().formatHex(digest.digest());
      }
    } catch (LibraryNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to fingerprint the actor's work access scope", exception);
    }
  }

  private static MessageDigest accessDigest(String purpose) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      updateFingerprintString(digest, purpose);
      return digest;
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void updateUuid(MessageDigest digest, UUID value) {
    digest.update(
        ByteBuffer.allocate(2 * Long.BYTES)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array());
  }

  private static void updateFingerprintString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }

  public LibraryRecord createLibrary(UUID ownerUserId, String name) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    UUID libraryId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        boolean isDefault;
        try (PreparedStatement owner =
            connection.prepareStatement(
                """
                        SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE
                        """)) {
          owner.setObject(1, ownerUserId);
          try (ResultSet row = owner.executeQuery()) {
            if (!row.next()) throw new LibraryOwnerNotFoundException();
          }
        }
        try (PreparedStatement existing =
            connection.prepareStatement(
                """
                        SELECT EXISTS (
                            SELECT 1 FROM libraries WHERE owner_user_id = ? AND is_default AND deleted_at IS NULL)
                        """)) {
          existing.setObject(1, ownerUserId);
          try (ResultSet row = existing.executeQuery()) {
            row.next();
            isDefault = !row.getBoolean(1);
          }
        }

        LibraryRecord created;
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                        INSERT INTO libraries (id, owner_user_id, name, is_default)
                        VALUES (?, ?, ?, ?)
                        RETURNING id, owner_user_id, name, visibility, is_default, created_at, workspace_id
                        """)) {
          insert.setObject(1, libraryId);
          insert.setObject(2, ownerUserId);
          insert.setString(3, name);
          insert.setBoolean(4, isDefault);
          try (ResultSet row = insert.executeQuery()) {
            row.next();
            created = library(row);
          }
        }
        connection.commit();
        return created;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (LibraryOwnerNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new LibraryNameConflictException();
      throw new IllegalStateException("Unable to create the user's library", exception);
    }
  }

  public LibraryRecord createWorkspaceLibrary(UUID actorUserId, UUID workspaceId, String name) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    UUID libraryId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO libraries (id, owner_user_id, name, is_default, workspace_id)
                     SELECT ?, m.user_id, ?, FALSE, w.id
                     FROM workspaces w
                     JOIN workspace_members m ON m.workspace_id = w.id
                     JOIN users u ON u.id = m.user_id AND u.status = 'active'
                     WHERE w.id = ? AND m.user_id = ? AND m.status = 'active'
                       AND m.role IN ('owner', 'admin')
                     RETURNING id, owner_user_id, name, visibility, is_default, created_at, workspace_id
                     """)) {
      insert.setObject(1, libraryId);
      insert.setString(2, name);
      insert.setObject(3, workspaceId);
      insert.setObject(4, actorUserId);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new LibraryNotFoundException();
        return library(row);
      }
    } catch (LibraryNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new LibraryNameConflictException();
      throw new IllegalStateException("Unable to create a workspace library", exception);
    }
  }

  public LibraryRecord movePersonalLibraryToWorkspace(
      UUID actorUserId, UUID libraryId, UUID workspaceId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    Objects.requireNonNull(workspaceId, "workspace id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        boolean isDefault;
        try (PreparedStatement lock =
            connection.prepareStatement(
                """
                        SELECT is_default FROM libraries
                        WHERE id = ? AND owner_user_id = ? AND workspace_id IS NULL
                          AND deleted_at IS NULL AND can_access_library(id, ?, TRUE)
                        FOR UPDATE
                        """)) {
          lock.setObject(1, libraryId);
          lock.setObject(2, actorUserId);
          lock.setObject(3, actorUserId);
          try (ResultSet row = lock.executeQuery()) {
            if (!row.next()) throw new LibraryNotFoundException();
            isDefault = row.getBoolean("is_default");
          }
        }
        if (isDefault) throw new DefaultLibraryCannotMoveException();

        lockWorkspaceLibraryScope(connection, actorUserId, workspaceId);
        LibraryRecord moved;
        try (PreparedStatement update =
            connection.prepareStatement(
                """
                        UPDATE libraries SET workspace_id = ?, updated_at = clock_timestamp()
                        WHERE id = ? AND owner_user_id = ? AND workspace_id IS NULL AND deleted_at IS NULL
                        RETURNING id, owner_user_id, name, visibility, is_default, created_at, workspace_id
                        """)) {
          update.setObject(1, workspaceId);
          update.setObject(2, libraryId);
          update.setObject(3, actorUserId);
          try (ResultSet row = update.executeQuery()) {
            if (!row.next()) throw new LibraryNotFoundException();
            moved = library(row);
          }
        }
        try (PreparedStatement bump =
            connection.prepareStatement(
                """
                        UPDATE workspaces
                        SET scope_version = scope_version + 1, updated_at = clock_timestamp()
                        WHERE id = ?
                        """)) {
          bump.setObject(1, workspaceId);
          if (bump.executeUpdate() != 1) throw new LibraryNotFoundException();
        }
        connection.commit();
        return moved;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (LibraryRepositoryException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new LibraryNameConflictException();
      throw new IllegalStateException(
          "Unable to move the personal library into a workspace", exception);
    }
  }

  private static void lockWorkspaceLibraryScope(
      Connection connection, UUID actorUserId, UUID workspaceId) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT id FROM workspaces WHERE id = ? FOR UPDATE")) {
      lock.setObject(1, workspaceId);
      try (ResultSet row = lock.executeQuery()) {
        if (!row.next()) throw new LibraryNotFoundException();
      }
    }
    try (PreparedStatement membership =
        connection.prepareStatement(
            """
                SELECT role FROM workspace_members
                WHERE workspace_id = ? AND user_id = ? AND status = 'active'
                FOR UPDATE
                """)) {
      membership.setObject(1, workspaceId);
      membership.setObject(2, actorUserId);
      try (ResultSet row = membership.executeQuery()) {
        if (!row.next() || !List.of("owner", "admin").contains(row.getString("role"))) {
          throw new LibraryNotFoundException();
        }
      }
    }
  }

  public Slice<LibraryRecord> findLibraries(
      UUID ownerUserId, int limit, LibraryCursorCodec.Position cursor) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    StringBuilder sql =
        new StringBuilder(
            """
                SELECT l.id, l.owner_user_id, l.name, l.visibility, l.is_default, l.created_at, l.workspace_id
                FROM libraries l
                WHERE can_access_library(l.id, ?, FALSE)
                """);
    if (cursor != null) {
      sql.append(
          """
                    AND (l.is_default, l.created_at, l.id) <
                        (CAST(? AS BOOLEAN), CAST(? AS TIMESTAMPTZ), CAST(? AS UUID))
                    """);
    }
    sql.append("ORDER BY l.is_default DESC, l.created_at DESC, l.id DESC LIMIT ?");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(sql.toString())) {
      select.setObject(1, ownerUserId);
      int parameter = 2;
      if (cursor != null) {
        select.setObject(parameter++, cursor.rank() == 1);
        select.setObject(
            parameter++, OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
        select.setObject(parameter++, cursor.itemId());
      }
      select.setInt(parameter, limit + 1);
      try (ResultSet rows = select.executeQuery()) {
        List<LibraryRecord> result = new ArrayList<>();
        while (rows.next()) result.add(library(rows));
        boolean hasMore = result.size() > limit;
        if (hasMore) result.remove(result.size() - 1);
        LibraryCursorCodec.Position nextPosition = null;
        if (hasMore && !result.isEmpty()) {
          LibraryRecord last = result.get(result.size() - 1);
          nextPosition =
              new LibraryCursorCodec.Position(
                  last.isDefault() ? 1 : 0, last.createdAt(), last.id());
        }
        return new Slice<>(result, hasMore, nextPosition);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the user's libraries", exception);
    }
  }

  public Optional<LibraryRecord> findLibrary(UUID ownerUserId, UUID libraryId) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT id, owner_user_id, name, visibility, is_default, created_at, workspace_id
                     FROM libraries
                     WHERE id = ? AND can_access_library(id, ?, FALSE)
                     """)) {
      select.setObject(1, libraryId);
      select.setObject(2, ownerUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(library(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the requested library", exception);
    }
  }

  public WorkRecord createPrivateImportedWork(
      UUID ownerUserId,
      UUID libraryId,
      String title,
      String author,
      String sourceLanguage,
      byte[] sourceHash) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    UUID workId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO works (id, library_id, owner_user_id, title, author, source_language,
                                        source_kind, visibility, status, source_hash)
                     SELECT ?, l.id, l.owner_user_id, ?, ?, ?, 'imported', 'private', 'active', ?
                     FROM libraries l
                     WHERE l.id = ? AND can_access_library(l.id, ?, TRUE)
                     RETURNING id, library_id, owner_user_id, title, author, source_language,
                               visibility, status, source_hash, created_at, 0 AS chapter_count
                     """)) {
      insert.setObject(1, workId);
      insert.setString(2, title);
      insert.setString(3, author);
      insert.setString(4, sourceLanguage);
      insert.setBytes(5, sourceHash);
      insert.setObject(6, libraryId);
      insert.setObject(7, ownerUserId);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new LibraryNotFoundException();
        return work(row);
      }
    } catch (LibraryNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new DuplicateImportedWorkException();
      throw new IllegalStateException("Unable to create a private work", exception);
    }
  }

  public WorkRecord createPrivateAuthoredWork(
      UUID ownerUserId,
      String title,
      String author,
      String sourceLanguage,
      String idempotencyKey,
      byte[] requestHash) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(requestHash, "request hash is required");
    UUID workId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement owner =
            connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE")) {
          owner.setObject(1, ownerUserId);
          try (ResultSet row = owner.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
          }
        }

        try (PreparedStatement prior =
            connection.prepareStatement(
                """
                    SELECT request_sha256, work_id FROM authoring_manuscript_creations
                    WHERE actor_user_id = ? AND idempotency_key = ?
                    """)) {
          prior.setObject(1, ownerUserId);
          prior.setString(2, idempotencyKey);
          try (ResultSet row = prior.executeQuery()) {
            if (row.next()) {
              if (!MessageDigest.isEqual(requestHash, row.getBytes("request_sha256"))) {
                throw new IdempotencyKeyReusedException();
              }
              UUID priorWorkId = row.getObject("work_id", UUID.class);
              WorkRecord replay;
              try (PreparedStatement select =
                  connection.prepareStatement(
                      workSelect()
                          + " WHERE w.id = ? AND w.owner_user_id = ? AND w.deleted_at IS NULL GROUP BY w.id")) {
                select.setObject(1, priorWorkId);
                select.setObject(2, ownerUserId);
                try (ResultSet work = select.executeQuery()) {
                  if (!work.next()) throw new WorkNotFoundException();
                  replay = work(work);
                }
              }
              connection.commit();
              return replay;
            }
          }
        }

        UUID libraryId = null;
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id FROM libraries
                    WHERE owner_user_id = ? AND workspace_id IS NULL AND deleted_at IS NULL
                    ORDER BY is_default DESC, created_at ASC, id ASC
                    LIMIT 1 FOR UPDATE
                    """)) {
          select.setObject(1, ownerUserId);
          try (ResultSet row = select.executeQuery()) {
            if (row.next()) libraryId = row.getObject("id", UUID.class);
          }
        }
        if (libraryId == null) {
          libraryId = UUID.randomUUID();
          try (PreparedStatement insert =
              connection.prepareStatement(
                  """
                      INSERT INTO libraries (id, owner_user_id, name, is_default)
                      VALUES (?, ?, 'Tác phẩm của tôi', TRUE)
                      """)) {
            insert.setObject(1, libraryId);
            insert.setObject(2, ownerUserId);
            insert.executeUpdate();
          }
        }

        WorkRecord created;
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO works (id, library_id, owner_user_id, title, author, source_language,
                                       source_kind, visibility, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'authored', 'private', 'active')
                    RETURNING id, library_id, owner_user_id, title, author, source_language,
                              visibility, status, source_hash, created_at, 0 AS chapter_count
                    """)) {
          insert.setObject(1, workId);
          insert.setObject(2, libraryId);
          insert.setObject(3, ownerUserId);
          insert.setString(4, title);
          insert.setString(5, author);
          insert.setString(6, sourceLanguage);
          try (ResultSet row = insert.executeQuery()) {
            row.next();
            created = work(row);
          }
        }
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO authoring_manuscript_creations
                        (actor_user_id, idempotency_key, request_sha256, work_id)
                    VALUES (?, ?, ?, ?)
                    """)) {
          insert.setObject(1, ownerUserId);
          insert.setString(2, idempotencyKey);
          insert.setBytes(3, requestHash);
          insert.setObject(4, workId);
          insert.executeUpdate();
        }
        connection.commit();
        return created;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (WorkNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to create a private authored work", exception);
    }
  }

  public static class IdempotencyKeyReusedException extends RuntimeException {}

  public static final class ManuscriptMetadataConflictException extends RuntimeException {
    private final long currentVersion;

    public ManuscriptMetadataConflictException(long currentVersion) {
      this.currentVersion = currentVersion;
    }

    public long currentVersion() {
      return currentVersion;
    }
  }

  public ChapterRecord createPrivateAuthoredChapter(
      UUID ownerUserId, UUID workId, String title, String idempotencyKey, byte[] requestHash) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(requestHash, "request hash is required");
    if (requestHash.length != 32)
      throw new IllegalArgumentException("request hash must be SHA-256");
    UUID chapterId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement actor =
            connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE")) {
          actor.setObject(1, ownerUserId);
          try (ResultSet row = actor.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
          }
        }

        String language;
        try (PreparedStatement manuscript =
            connection.prepareStatement(
                """
                    SELECT w.source_language
                    FROM works w
                    JOIN libraries l ON l.id = w.library_id
                    WHERE w.id = ? AND w.owner_user_id = ?
                      AND w.source_kind = 'authored' AND w.visibility IN ('private', 'public')
                      AND w.status = 'active' AND w.deleted_at IS NULL
                      AND l.owner_user_id = w.owner_user_id AND l.workspace_id IS NULL
                      AND l.visibility = 'private' AND l.deleted_at IS NULL
                    FOR UPDATE OF w, l
                    """)) {
          manuscript.setObject(1, workId);
          manuscript.setObject(2, ownerUserId);
          try (ResultSet row = manuscript.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
            language = row.getString("source_language");
          }
        }

        try (PreparedStatement prior =
            connection.prepareStatement(
                """
                    SELECT request_sha256, chapter_id FROM authoring_chapter_creations
                    WHERE actor_user_id = ? AND work_id = ? AND idempotency_key = ?
                    """)) {
          prior.setObject(1, ownerUserId);
          prior.setObject(2, workId);
          prior.setString(3, idempotencyKey);
          try (ResultSet row = prior.executeQuery()) {
            if (row.next()) {
              if (!MessageDigest.isEqual(requestHash, row.getBytes("request_sha256"))) {
                throw new IdempotencyKeyReusedException();
              }
              UUID priorChapterId = row.getObject("chapter_id", UUID.class);
              ChapterRecord replay;
              try (PreparedStatement select =
                  connection.prepareStatement(
                      """
                          SELECT id, work_id, volume_id, ordinal, title, source_language,
                                 source_revision_id, status, created_at
                          FROM chapters
                          WHERE id = ? AND work_id = ? AND deleted_at IS NULL
                          """)) {
                select.setObject(1, priorChapterId);
                select.setObject(2, workId);
                try (ResultSet chapter = select.executeQuery()) {
                  if (!chapter.next()) throw new WorkNotFoundException();
                  replay = chapter(chapter);
                }
              }
              connection.commit();
              return replay;
            }
          }
        }

        int ordinal;
        try (PreparedStatement next =
            connection.prepareStatement(
                "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM chapters WHERE work_id = ?")) {
          next.setObject(1, workId);
          try (ResultSet row = next.executeQuery()) {
            row.next();
            ordinal = row.getInt(1);
          }
        }

        ChapterRecord created;
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO chapters
                        (id, work_id, volume_id, ordinal, title, source_language, status)
                    VALUES (?, ?, NULL, ?, ?, ?, 'draft')
                    RETURNING id, work_id, volume_id, ordinal, title, source_language,
                              source_revision_id, status, created_at
                    """)) {
          insert.setObject(1, chapterId);
          insert.setObject(2, workId);
          insert.setInt(3, ordinal);
          insert.setString(4, title);
          insert.setString(5, language);
          try (ResultSet row = insert.executeQuery()) {
            row.next();
            created = chapter(row);
          }
        }
        try (PreparedStatement receipt =
            connection.prepareStatement(
                """
                    INSERT INTO authoring_chapter_creations
                        (actor_user_id, work_id, idempotency_key, request_sha256, chapter_id)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
          receipt.setObject(1, ownerUserId);
          receipt.setObject(2, workId);
          receipt.setString(3, idempotencyKey);
          receipt.setBytes(4, requestHash);
          receipt.setObject(5, chapterId);
          receipt.executeUpdate();
        }
        connection.commit();
        return created;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (WorkNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new OrderConflictException();
      throw new IllegalStateException(
          "Unable to create a private authored draft chapter", exception);
    }
  }

  public ChapterOutlinePage listPrivateAuthoredChapters(
      UUID ownerUserId, UUID workId, int afterOrdinal, int limit) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (afterOrdinal < 0) throw new IllegalArgumentException("after ordinal cannot be negative");
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1 to 100");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement actor =
            connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? AND status = 'active' FOR SHARE")) {
          actor.setObject(1, ownerUserId);
          try (ResultSet row = actor.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
          }
        }

        try (PreparedStatement manuscript =
            connection.prepareStatement(
                """
                    SELECT w.id
                    FROM works w
                    JOIN libraries l ON l.id = w.library_id
                    WHERE w.id = ? AND w.owner_user_id = ?
                      AND w.source_kind = 'authored' AND w.visibility IN ('private', 'public')
                      AND w.status = 'active' AND w.deleted_at IS NULL
                      AND l.owner_user_id = w.owner_user_id AND l.workspace_id IS NULL
                      AND l.visibility = 'private' AND l.deleted_at IS NULL
                    FOR SHARE OF w, l
                    """)) {
          manuscript.setObject(1, workId);
          manuscript.setObject(2, ownerUserId);
          try (ResultSet row = manuscript.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
          }
        }

        List<ChapterOutlineRecord> chapters = new ArrayList<>(limit + 1);
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT id, work_id, volume_id, ordinal, title, source_language,
                           source_revision_id, status, created_at,
                           (status = 'draft' AND EXISTS (
                             SELECT 1 FROM chapter_revisions revision
                             WHERE revision.chapter_id = chapters.id
                           )) AS has_saved_draft_content
                    FROM chapters
                    WHERE work_id = ? AND ordinal > ? AND deleted_at IS NULL
                    ORDER BY ordinal ASC
                    LIMIT ?
                    """)) {
          select.setObject(1, workId);
          select.setInt(2, afterOrdinal);
          select.setInt(3, limit + 1);
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) chapters.add(chapterOutline(rows));
          }
        }

        boolean hasMore = chapters.size() > limit;
        List<ChapterOutlineRecord> items =
            hasMore ? List.copyOf(chapters.subList(0, limit)) : List.copyOf(chapters);
        Integer lastOrdinal = items.isEmpty() ? null : items.get(items.size() - 1).ordinal();
        connection.commit();
        return new ChapterOutlinePage(items, hasMore, lastOrdinal);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (WorkNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to list private authored chapters", exception);
    }
  }

  public void deletePrivateEmptyAuthoredChapter(UUID ownerUserId, UUID workId, UUID chapterId) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement actor =
            connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE")) {
          actor.setObject(1, ownerUserId);
          try (ResultSet row = actor.executeQuery()) {
            if (!row.next()) throw new ChapterNotFoundException();
          }
        }

        String status;
        UUID currentRevisionId;
        boolean hasRevision;
        try (PreparedStatement lock =
            connection.prepareStatement(
                """
                    SELECT c.status, c.source_revision_id,
                           EXISTS (
                             SELECT 1 FROM chapter_revisions revision
                             WHERE revision.chapter_id = c.id
                           ) AS has_revision
                    FROM chapters c
                    JOIN works w ON w.id = c.work_id
                    JOIN libraries l ON l.id = w.library_id
                    WHERE c.id = ? AND c.work_id = ? AND w.owner_user_id = ?
                      AND w.source_kind = 'authored' AND w.visibility IN ('private', 'public')
                      AND w.status = 'active' AND w.deleted_at IS NULL
                      AND l.owner_user_id = w.owner_user_id AND l.workspace_id IS NULL
                      AND l.visibility = 'private' AND l.deleted_at IS NULL
                    FOR UPDATE OF c, w, l
                    """)) {
          lock.setObject(1, chapterId);
          lock.setObject(2, workId);
          lock.setObject(3, ownerUserId);
          try (ResultSet row = lock.executeQuery()) {
            if (!row.next()) throw new ChapterNotFoundException();
            status = row.getString("status");
            currentRevisionId = row.getObject("source_revision_id", UUID.class);
            hasRevision = row.getBoolean("has_revision");
          }
        }

        if ("deleted".equals(status)) {
          connection.commit();
          return;
        }
        if (!"draft".equals(status) || currentRevisionId != null || hasRevision) {
          throw new ChapterHasSavedContentException();
        }

        try (PreparedStatement delete =
            connection.prepareStatement(
                """
                    UPDATE chapters
                    SET status = 'deleted', deleted_at = clock_timestamp(),
                        updated_at = clock_timestamp()
                    WHERE id = ? AND work_id = ? AND status = 'draft'
                      AND source_revision_id IS NULL AND deleted_at IS NULL
                      AND NOT EXISTS (
                        SELECT 1 FROM chapter_revisions revision
                        WHERE revision.chapter_id = chapters.id
                      )
                    """)) {
          delete.setObject(1, chapterId);
          delete.setObject(2, workId);
          if (delete.executeUpdate() != 1) throw new ChapterHasSavedContentException();
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (ChapterNotFoundException | ChapterHasSavedContentException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Unable to remove an empty private authored chapter", exception);
    }
  }

  public AuthoredWorkPage listPrivateAuthoredWorks(
      UUID ownerUserId, Instant beforeCreatedAt, UUID beforeWorkId, int limit) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    if ((beforeCreatedAt == null) != (beforeWorkId == null)) {
      throw new IllegalArgumentException("both work cursor values are required together");
    }
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1 to 100");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement actor =
            connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? AND status = 'active' FOR SHARE")) {
          actor.setObject(1, ownerUserId);
          try (ResultSet row = actor.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
          }
        }

        List<WorkRecord> works = new ArrayList<>(limit + 1);
        String cursorPredicate =
            beforeCreatedAt == null
                ? ""
                : "AND (w.created_at < ? OR (w.created_at = ? AND w.id < ?))";
        String query =
            """
                SELECT w.id, w.library_id, w.owner_user_id, w.title, w.author, w.source_language,
                       w.visibility, w.status, w.source_hash, w.created_at,
                       count(c.id) FILTER (WHERE c.deleted_at IS NULL) AS chapter_count
                FROM works w
                JOIN libraries l ON l.id = w.library_id
                LEFT JOIN chapters c ON c.work_id = w.id
                WHERE w.owner_user_id = ? AND w.source_kind = 'authored'
                  AND w.visibility = 'private' AND w.status = 'active'
                  AND w.deleted_at IS NULL AND l.owner_user_id = w.owner_user_id
                  AND l.workspace_id IS NULL AND l.visibility = 'private' AND l.deleted_at IS NULL
                """
                + cursorPredicate
                + " GROUP BY w.id ORDER BY w.created_at DESC, w.id DESC LIMIT ?";
        try (PreparedStatement select = connection.prepareStatement(query)) {
          int parameter = 1;
          select.setObject(parameter++, ownerUserId);
          if (beforeCreatedAt != null) {
            Timestamp timestamp = Timestamp.from(beforeCreatedAt);
            select.setTimestamp(parameter++, timestamp);
            select.setTimestamp(parameter++, timestamp);
            select.setObject(parameter++, beforeWorkId);
          }
          select.setInt(parameter, limit + 1);
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) works.add(work(rows));
          }
        }

        boolean hasMore = works.size() > limit;
        List<WorkRecord> items =
            hasMore ? List.copyOf(works.subList(0, limit)) : List.copyOf(works);
        WorkRecord last = items.isEmpty() ? null : items.get(items.size() - 1);
        connection.commit();
        return new AuthoredWorkPage(
            items,
            hasMore,
            last == null ? null : last.createdAt(),
            last == null ? null : last.id());
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (WorkNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to list private authored works", exception);
    }
  }

  public PrivateAuthoredWorkDetails getPrivateAuthoredWorkDetails(UUID ownerUserId, UUID workId) {
    return getOwnedAuthoredWorkDetails(ownerUserId, workId, "private");
  }

  public PrivateAuthoredWorkDetails getPublicAuthoredWorkDraftingContext(
      UUID ownerUserId, UUID workId) {
    return getOwnedAuthoredWorkDetails(ownerUserId, workId, "public");
  }

  private PrivateAuthoredWorkDetails getOwnedAuthoredWorkDetails(
      UUID ownerUserId, UUID workId, String visibility) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement actor =
            connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? AND status = 'active' FOR SHARE")) {
          actor.setObject(1, ownerUserId);
          try (ResultSet row = actor.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
          }
        }

        String query =
            """
                SELECT w.id, w.title, w.author, w.source_language, w.visibility, w.created_at,
                       w.authoring_metadata_version,
                       count(c.id) FILTER (WHERE c.deleted_at IS NULL) AS chapter_count
                FROM works w
                JOIN libraries l ON l.id = w.library_id
                LEFT JOIN chapters c ON c.work_id = w.id
                WHERE w.id = ? AND w.owner_user_id = ? AND w.source_kind = 'authored'
                  AND w.visibility = ? AND w.status = 'active'
                  AND w.deleted_at IS NULL AND l.owner_user_id = w.owner_user_id
                  AND l.workspace_id IS NULL AND l.visibility = 'private' AND l.deleted_at IS NULL
                GROUP BY w.id
                """;
        PrivateAuthoredWorkDetails details;
        try (PreparedStatement select = connection.prepareStatement(query)) {
          select.setObject(1, workId);
          select.setObject(2, ownerUserId);
          select.setString(3, visibility);
          try (ResultSet row = select.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
            details =
                new PrivateAuthoredWorkDetails(
                    row.getObject("id", UUID.class),
                    row.getString("title"),
                    row.getString("author"),
                    row.getString("source_language"),
                    row.getString("visibility"),
                    row.getInt("chapter_count"),
                    instant(row, "created_at"),
                    row.getLong("authoring_metadata_version"));
          }
        }
        connection.commit();
        return details;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (WorkNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load authored manuscript context", exception);
    }
  }

  public PrivateAuthoredWorkDetails updatePrivateAuthoredWorkDetails(
      UUID ownerUserId,
      UUID workId,
      long expectedVersion,
      String title,
      String author,
      String language) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (expectedVersion < 1)
      throw new IllegalArgumentException("metadata version must be positive");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement actor =
            connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? AND status = 'active' FOR SHARE")) {
          actor.setObject(1, ownerUserId);
          try (ResultSet row = actor.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
          }
        }

        PrivateAuthoredWorkDetails current;
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT w.id, w.title, w.author, w.source_language, w.visibility, w.created_at,
                           w.authoring_metadata_version,
                           (SELECT count(*) FROM chapters c
                            WHERE c.work_id = w.id AND c.deleted_at IS NULL) AS chapter_count
                    FROM works w
                    JOIN libraries l ON l.id = w.library_id
                    WHERE w.id = ? AND w.owner_user_id = ? AND w.source_kind = 'authored'
                      AND w.visibility = 'private' AND w.status = 'active'
                      AND w.deleted_at IS NULL AND l.owner_user_id = w.owner_user_id
                      AND l.workspace_id IS NULL AND l.visibility = 'private' AND l.deleted_at IS NULL
                    FOR UPDATE OF w
                    """)) {
          select.setObject(1, workId);
          select.setObject(2, ownerUserId);
          try (ResultSet row = select.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
            current =
                new PrivateAuthoredWorkDetails(
                    row.getObject("id", UUID.class),
                    row.getString("title"),
                    row.getString("author"),
                    row.getString("source_language"),
                    row.getString("visibility"),
                    row.getInt("chapter_count"),
                    instant(row, "created_at"),
                    row.getLong("authoring_metadata_version"));
          }
        }

        boolean sameValues =
            current.title().equals(title)
                && current.author().equals(author)
                && current.language().equals(language);
        if (current.metadataVersion() != expectedVersion && !sameValues) {
          throw new ManuscriptMetadataConflictException(current.metadataVersion());
        }
        if (sameValues) {
          connection.commit();
          return current;
        }

        try (PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE works
                    SET title = ?, author = ?, source_language = ?,
                        authoring_metadata_version = authoring_metadata_version + 1,
                        updated_at = clock_timestamp()
                    WHERE id = ? AND owner_user_id = ? AND authoring_metadata_version = ?
                    """)) {
          update.setString(1, title);
          update.setString(2, author);
          update.setString(3, language);
          update.setObject(4, workId);
          update.setObject(5, ownerUserId);
          update.setLong(6, expectedVersion);
          if (update.executeUpdate() != 1) {
            throw new ManuscriptMetadataConflictException(current.metadataVersion());
          }
        }
        connection.commit();
        return new PrivateAuthoredWorkDetails(
            current.id(),
            title,
            author,
            language,
            "private",
            current.chapterCount(),
            current.createdAt(),
            current.metadataVersion() + 1);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (WorkNotFoundException | ManuscriptMetadataConflictException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to update private authored manuscript", exception);
    }
  }

  public Optional<WorkRecord> findWork(UUID ownerUserId, UUID workId) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                workSelect()
                    + """
                     WHERE w.id = ? AND can_access_library(w.library_id, ?, FALSE) AND w.deleted_at IS NULL
                     GROUP BY w.id
                     """)) {
      select.setObject(1, workId);
      select.setObject(2, ownerUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(work(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the requested work", exception);
    }
  }

  public TagRecord addTagToWork(UUID ownerUserId, UUID workId, String slug, String name) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    UUID tagId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        UUID workOwnerUserId;
        try (PreparedStatement work =
            connection.prepareStatement(
                """
                        SELECT id, owner_user_id FROM works
                        WHERE id = ? AND can_access_library(library_id, ?, TRUE) AND deleted_at IS NULL
                        FOR UPDATE
                        """)) {
          work.setObject(1, workId);
          work.setObject(2, ownerUserId);
          try (ResultSet row = work.executeQuery()) {
            if (!row.next()) throw new WorkNotFoundException();
            workOwnerUserId = row.getObject("owner_user_id", UUID.class);
          }
        }

        TagRecord tag;
        try (PreparedStatement insertTag =
            connection.prepareStatement(
                """
                        INSERT INTO tags (id, owner_user_id, slug, name)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (owner_user_id, slug) WHERE deleted_at IS NULL
                        DO UPDATE SET name = EXCLUDED.name, updated_at = clock_timestamp()
                        RETURNING id, owner_user_id, slug, name, created_at
                        """)) {
          insertTag.setObject(1, tagId);
          insertTag.setObject(2, ownerUserId);
          insertTag.setString(3, slug);
          insertTag.setString(4, name);
          try (ResultSet row = insertTag.executeQuery()) {
            row.next();
            tag = tag(row);
          }
        }

        try (PreparedStatement link =
            connection.prepareStatement(
                """
                        INSERT INTO work_tags (owner_user_id, work_id, tag_id, assigned_by_user_id)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (owner_user_id, work_id, tag_id) DO NOTHING
                        """)) {
          link.setObject(1, workOwnerUserId);
          link.setObject(2, workId);
          link.setObject(3, tag.id());
          link.setObject(4, ownerUserId);
          link.executeUpdate();
        }
        connection.commit();
        return tag;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (WorkNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to assign a tag to the owned work", exception);
    }
  }

  public Slice<WorkRecord> findWorks(
      UUID ownerUserId,
      UUID libraryId,
      String query,
      String tag,
      String status,
      int limit,
      LibraryCursorCodec.Position cursor) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    String normalizedQuery = query == null ? "" : query.trim();
    String normalizedTag = tag == null ? "" : tag;
    String normalizedStatus = status == null ? "" : status;
    String escapedQuery = escapeLike(normalizedQuery);
    StringBuilder workSql =
        new StringBuilder(workSelect())
            .append(
                """
                WHERE w.library_id = ? AND can_access_library(w.library_id, ?, FALSE) AND w.deleted_at IS NULL
                """);
    if (!normalizedQuery.isEmpty()) workSql.append("AND (w.title ILIKE ? OR w.author ILIKE ?) ");
    if (!normalizedStatus.isEmpty()) workSql.append("AND w.status = ? ");
    if (!normalizedTag.isEmpty())
      workSql.append(
          """
                AND EXISTS (
                    SELECT 1 FROM work_tags wt
                    JOIN tags t ON t.owner_user_id = wt.assigned_by_user_id AND t.id = wt.tag_id
                    WHERE wt.work_id = w.id
                      AND t.slug = ? AND t.deleted_at IS NULL)
                """);
    if (cursor != null)
      workSql.append(
          """
                AND (w.created_at, w.id) < (CAST(? AS TIMESTAMPTZ), CAST(? AS UUID))
                """);
    workSql.append("GROUP BY w.id ORDER BY w.created_at DESC, w.id DESC LIMIT ?");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select = connection.prepareStatement(workSql.toString())) {
      int parameter = 1;
      select.setObject(parameter++, libraryId);
      select.setObject(parameter++, ownerUserId);
      if (!normalizedQuery.isEmpty()) {
        select.setString(parameter++, "%" + escapedQuery + "%");
        select.setString(parameter++, "%" + escapedQuery + "%");
      }
      if (!normalizedStatus.isEmpty()) select.setString(parameter++, normalizedStatus);
      if (!normalizedTag.isEmpty()) select.setString(parameter++, normalizedTag);
      if (cursor != null) {
        select.setObject(
            parameter++, OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
        select.setObject(parameter++, cursor.itemId());
      }
      select.setInt(parameter, limit + 1);
      try (ResultSet rows = select.executeQuery()) {
        List<WorkRecord> result = new ArrayList<>();
        while (rows.next()) result.add(work(rows));
        boolean hasMore = result.size() > limit;
        if (hasMore) result.remove(result.size() - 1);
        LibraryCursorCodec.Position nextPosition = null;
        if (hasMore && !result.isEmpty()) {
          WorkRecord last = result.get(result.size() - 1);
          nextPosition = new LibraryCursorCodec.Position(0, last.createdAt(), last.id());
        }
        return new Slice<>(result, hasMore, nextPosition);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load works from the user's library", exception);
    }
  }

  public VolumeRecord createVolume(UUID ownerUserId, UUID workId, int ordinal, String title) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    UUID volumeId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO volumes (id, work_id, ordinal, title)
                     SELECT ?, w.id, ?, ? FROM works w
                     WHERE w.id = ? AND can_access_library(w.library_id, ?, TRUE) AND w.deleted_at IS NULL
                     RETURNING id, work_id, ordinal, title, created_at
                     """)) {
      insert.setObject(1, volumeId);
      insert.setInt(2, ordinal);
      insert.setString(3, title);
      insert.setObject(4, workId);
      insert.setObject(5, ownerUserId);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new WorkNotFoundException();
        return volume(row);
      }
    } catch (WorkNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new OrderConflictException();
      throw new IllegalStateException("Unable to create a volume", exception);
    }
  }

  public ChapterRecord createChapter(
      UUID ownerUserId,
      UUID workId,
      UUID volumeId,
      int ordinal,
      String title,
      String sourceLanguage) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    UUID chapterId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language)
                     SELECT ?, w.id, CAST(? AS UUID), ?, ?, ? FROM works w
                     WHERE w.id = ? AND can_access_library(w.library_id, ?, TRUE) AND w.deleted_at IS NULL
                       AND (CAST(? AS UUID) IS NULL OR EXISTS (
                           SELECT 1 FROM volumes v WHERE v.id = CAST(? AS UUID) AND v.work_id = w.id))
                     RETURNING id, work_id, volume_id, ordinal, title, source_language,
                               source_revision_id, status, created_at
                     """)) {
      insert.setObject(1, chapterId);
      insert.setObject(2, volumeId);
      insert.setInt(3, ordinal);
      insert.setString(4, title);
      insert.setString(5, sourceLanguage);
      insert.setObject(6, workId);
      insert.setObject(7, ownerUserId);
      insert.setObject(8, volumeId);
      insert.setObject(9, volumeId);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new WorkNotFoundException();
        return chapter(row);
      }
    } catch (WorkNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new OrderConflictException();
      throw new IllegalStateException("Unable to create a chapter", exception);
    }
  }

  public RevisionRecord appendChapterRevision(
      UUID ownerUserId,
      UUID chapterId,
      String sourceFormat,
      String normalizedContent,
      byte[] contentHash,
      List<SegmentInput> segments) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    UUID revisionId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        UUID chapterWorkId;
        try (PreparedStatement lock =
            connection.prepareStatement(
                """
                        SELECT c.work_id FROM chapters c
                        JOIN works w ON w.id = c.work_id
                        WHERE c.id = ? AND can_access_library(w.library_id, ?, TRUE) AND w.deleted_at IS NULL
                          AND c.deleted_at IS NULL
                        FOR UPDATE OF c
                        """)) {
          lock.setObject(1, chapterId);
          lock.setObject(2, ownerUserId);
          try (ResultSet row = lock.executeQuery()) {
            if (!row.next()) throw new ChapterNotFoundException();
            chapterWorkId = row.getObject("work_id", UUID.class);
          }
        }

        int revisionNo;
        try (PreparedStatement next =
            connection.prepareStatement(
                "SELECT COALESCE(MAX(revision_no), 0) + 1 FROM chapter_revisions WHERE chapter_id = ?")) {
          next.setObject(1, chapterId);
          try (ResultSet row = next.executeQuery()) {
            row.next();
            revisionNo = row.getInt(1);
          }
        }

        RevisionRecord revision;
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                        INSERT INTO chapter_revisions
                            (id, chapter_id, revision_no, source_format, normalized_content,
                             content_hash, created_by_user_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        RETURNING id, chapter_id, revision_no, source_format, content_hash, created_at
                        """)) {
          insert.setObject(1, revisionId);
          insert.setObject(2, chapterId);
          insert.setInt(3, revisionNo);
          insert.setString(4, sourceFormat);
          insert.setString(5, normalizedContent);
          insert.setBytes(6, contentHash);
          insert.setObject(7, ownerUserId);
          try (ResultSet row = insert.executeQuery()) {
            row.next();
            revision = revision(row);
          }
        }

        try (PreparedStatement insertSegment =
            connection.prepareStatement(
                """
                        INSERT INTO segments
                            (id, chapter_revision_id, ordinal, segment_type, source_text, stable_anchor, checksum)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
          for (SegmentInput segment : segments) {
            insertSegment.setObject(1, UUID.randomUUID());
            insertSegment.setObject(2, revisionId);
            insertSegment.setInt(3, segment.ordinal());
            insertSegment.setString(4, segment.type());
            insertSegment.setString(5, segment.sourceText());
            insertSegment.setString(6, segment.stableAnchor());
            insertSegment.setBytes(7, segment.checksum());
            insertSegment.addBatch();
          }
          insertSegment.executeBatch();
        }

        try (PreparedStatement update =
            connection.prepareStatement(
                """
                        UPDATE chapters SET source_revision_id = ?, status = 'ready', updated_at = clock_timestamp()
                        WHERE id = ? AND work_id = ?
                        """)) {
          update.setObject(1, revisionId);
          update.setObject(2, chapterId);
          update.setObject(3, chapterWorkId);
          if (update.executeUpdate() != 1) throw new ChapterNotFoundException();
        }
        connection.commit();
        return revision;
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (ChapterNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new DuplicateRevisionException();
      throw new IllegalStateException("Unable to append a chapter revision", exception);
    }
  }

  public DraftRevisionWrite savePrivateAuthoredDraftRevision(
      UUID ownerUserId,
      UUID workId,
      UUID chapterId,
      int expectedRevisionNo,
      String idempotencyKey,
      byte[] requestHash,
      String sourceFormat,
      String normalizedContent,
      byte[] contentHash,
      List<SegmentInput> segments) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    Objects.requireNonNull(requestHash, "request hash is required");
    if (requestHash.length != 32 || contentHash == null || contentHash.length != 32) {
      throw new IllegalArgumentException("revision hashes must be SHA-256");
    }
    UUID revisionId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement actor =
            connection.prepareStatement(
                "SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE")) {
          actor.setObject(1, ownerUserId);
          try (ResultSet row = actor.executeQuery()) {
            if (!row.next()) throw new ChapterNotFoundException();
          }
        }

        int currentRevisionNo;
        try (PreparedStatement lock =
            connection.prepareStatement(
                """
                    SELECT COALESCE(current_revision.revision_no, 0) AS current_revision_no
                    FROM chapters c
                    JOIN works w ON w.id = c.work_id
                    JOIN libraries l ON l.id = w.library_id
                    JOIN users u ON u.id = w.owner_user_id
                    LEFT JOIN chapter_revisions current_revision
                      ON current_revision.id = c.source_revision_id
                     AND current_revision.chapter_id = c.id
                    WHERE c.id = ? AND c.work_id = ? AND w.owner_user_id = ?
                      AND w.source_kind = 'authored' AND w.visibility IN ('private', 'public')
                      AND w.status = 'active' AND w.deleted_at IS NULL
                      AND l.owner_user_id = w.owner_user_id AND l.workspace_id IS NULL
                      AND l.visibility = 'private' AND l.deleted_at IS NULL
                      AND u.status = 'active' AND c.status = 'draft' AND c.deleted_at IS NULL
                    FOR UPDATE OF c, w, l
                    """)) {
          lock.setObject(1, chapterId);
          lock.setObject(2, workId);
          lock.setObject(3, ownerUserId);
          try (ResultSet row = lock.executeQuery()) {
            if (!row.next()) throw new ChapterNotFoundException();
            currentRevisionNo = row.getInt("current_revision_no");
          }
        }

        try (PreparedStatement prior =
            connection.prepareStatement(
                """
                    SELECT request_sha256, revision_id
                    FROM authoring_chapter_revision_writes
                    WHERE actor_user_id = ? AND chapter_id = ? AND idempotency_key = ?
                    """)) {
          prior.setObject(1, ownerUserId);
          prior.setObject(2, chapterId);
          prior.setString(3, idempotencyKey);
          try (ResultSet row = prior.executeQuery()) {
            if (row.next()) {
              if (!MessageDigest.isEqual(requestHash, row.getBytes("request_sha256"))) {
                throw new IdempotencyKeyReusedException();
              }
              RevisionRecord replay;
              try (PreparedStatement select =
                  connection.prepareStatement(
                      """
                          SELECT id, chapter_id, revision_no, source_format, content_hash, created_at
                          FROM chapter_revisions WHERE id = ? AND chapter_id = ?
                          """)) {
                select.setObject(1, row.getObject("revision_id", UUID.class));
                select.setObject(2, chapterId);
                try (ResultSet revision = select.executeQuery()) {
                  if (!revision.next()) throw new ChapterNotFoundException();
                  replay = revision(revision);
                }
              }
              connection.commit();
              return new DraftRevisionWrite(replay, true);
            }
          }
        }

        if (expectedRevisionNo != currentRevisionNo) {
          throw new DraftRevisionConflictException(currentRevisionNo);
        }
        int nextRevisionNo = currentRevisionNo + 1;
        RevisionRecord created;
        try (PreparedStatement insert =
            connection.prepareStatement(
                """
                    INSERT INTO chapter_revisions
                        (id, chapter_id, revision_no, source_format, normalized_content,
                         content_hash, created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, chapter_id, revision_no, source_format, content_hash, created_at
                    """)) {
          insert.setObject(1, revisionId);
          insert.setObject(2, chapterId);
          insert.setInt(3, nextRevisionNo);
          insert.setString(4, sourceFormat);
          insert.setString(5, normalizedContent);
          insert.setBytes(6, contentHash);
          insert.setObject(7, ownerUserId);
          try (ResultSet row = insert.executeQuery()) {
            row.next();
            created = revision(row);
          }
        }

        try (PreparedStatement insertSegment =
            connection.prepareStatement(
                """
                    INSERT INTO segments
                        (id, chapter_revision_id, ordinal, segment_type, source_text, stable_anchor, checksum)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
          for (SegmentInput segment : segments) {
            insertSegment.setObject(1, UUID.randomUUID());
            insertSegment.setObject(2, revisionId);
            insertSegment.setInt(3, segment.ordinal());
            insertSegment.setString(4, segment.type());
            insertSegment.setString(5, segment.sourceText());
            insertSegment.setString(6, segment.stableAnchor());
            insertSegment.setBytes(7, segment.checksum());
            insertSegment.addBatch();
          }
          insertSegment.executeBatch();
        }

        try (PreparedStatement update =
            connection.prepareStatement(
                """
                    UPDATE chapters
                    SET source_revision_id = ?, status = 'draft', updated_at = clock_timestamp()
                    WHERE id = ? AND work_id = ? AND status = 'draft'
                    """)) {
          update.setObject(1, revisionId);
          update.setObject(2, chapterId);
          update.setObject(3, workId);
          if (update.executeUpdate() != 1) throw new ChapterNotFoundException();
        }

        try (PreparedStatement receipt =
            connection.prepareStatement(
                """
                    INSERT INTO authoring_chapter_revision_writes
                        (actor_user_id, work_id, chapter_id, idempotency_key, request_sha256, revision_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
          receipt.setObject(1, ownerUserId);
          receipt.setObject(2, workId);
          receipt.setObject(3, chapterId);
          receipt.setString(4, idempotencyKey);
          receipt.setBytes(5, requestHash);
          receipt.setObject(6, revisionId);
          receipt.executeUpdate();
        }

        connection.commit();
        return new DraftRevisionWrite(created, false);
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (ChapterNotFoundException | DraftRevisionConflictException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new DuplicateRevisionException();
      throw new IllegalStateException("Unable to save the private authored draft", exception);
    }
  }

  public Optional<PrivateAuthoredDraftRecord> findPrivateAuthoredDraft(
      UUID ownerUserId, UUID workId, UUID chapterId) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(workId, "work id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      try (PreparedStatement select =
          connection.prepareStatement(
              """
                  SELECT c.id, c.work_id, c.title,
                         COALESCE(r.revision_no, 0) AS revision_no,
                         r.source_format, r.normalized_content,
                         COALESCE(r.created_at, c.updated_at) AS draft_updated_at
                  FROM chapters c
                  JOIN works w ON w.id = c.work_id
                  JOIN libraries l ON l.id = w.library_id
                  JOIN users u ON u.id = w.owner_user_id
                  LEFT JOIN chapter_revisions r
                    ON r.id = c.source_revision_id AND r.chapter_id = c.id
                  WHERE c.id = ? AND c.work_id = ? AND w.owner_user_id = ?
                    AND w.source_kind = 'authored' AND w.visibility IN ('private', 'public')
                    AND w.status = 'active' AND w.deleted_at IS NULL
                    AND l.owner_user_id = w.owner_user_id AND l.workspace_id IS NULL
                    AND l.visibility = 'private' AND l.deleted_at IS NULL
                    AND u.status = 'active' AND c.status = 'draft' AND c.deleted_at IS NULL
                  """)) {
        select.setObject(1, chapterId);
        select.setObject(2, workId);
        select.setObject(3, ownerUserId);
        try (ResultSet row = select.executeQuery()) {
          if (!row.next()) {
            connection.commit();
            return Optional.empty();
          }
          UUID draftChapterId = row.getObject("id", UUID.class);
          int revisionNo = row.getInt("revision_no");
          String sourceFormat = row.getString("source_format");
          String normalizedContent = row.getString("normalized_content");
          Instant updatedAt = row.getObject("draft_updated_at", OffsetDateTime.class).toInstant();
          List<DraftSegmentRecord> segments = new ArrayList<>();
          if (revisionNo > 0) {
            try (PreparedStatement selectSegments =
                connection.prepareStatement(
                    """
                        SELECT s.ordinal, s.segment_type, s.source_text, s.stable_anchor
                        FROM segments s JOIN chapter_revisions r ON r.id = s.chapter_revision_id
                        WHERE r.chapter_id = ? AND r.revision_no = ?
                        ORDER BY s.ordinal
                        """)) {
              selectSegments.setObject(1, draftChapterId);
              selectSegments.setInt(2, revisionNo);
              try (ResultSet segmentRows = selectSegments.executeQuery()) {
                while (segmentRows.next()) {
                  segments.add(
                      new DraftSegmentRecord(
                          segmentRows.getInt("ordinal"),
                          segmentRows.getString("segment_type"),
                          segmentRows.getString("source_text"),
                          segmentRows.getString("stable_anchor")));
                }
              }
            }
          }
          PrivateAuthoredDraftRecord draft =
              new PrivateAuthoredDraftRecord(
                  draftChapterId,
                  row.getObject("work_id", UUID.class),
                  row.getString("title"),
                  revisionNo,
                  sourceFormat,
                  normalizedContent == null ? "" : normalizedContent,
                  updatedAt,
                  segments);
          connection.commit();
          return Optional.of(draft);
        }
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the private authored draft", exception);
    }
  }

  public Optional<ChapterRecord> findChapter(UUID ownerUserId, UUID chapterId) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                     SELECT c.id, c.work_id, c.volume_id, c.ordinal, c.title, c.source_language,
                            c.source_revision_id, c.status, c.created_at
                     FROM chapters c JOIN works w ON w.id = c.work_id
                     WHERE c.id = ? AND can_access_library(w.library_id, ?, FALSE)
                       AND c.deleted_at IS NULL AND w.deleted_at IS NULL
                     """)) {
      select.setObject(1, chapterId);
      select.setObject(2, ownerUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next() ? Optional.of(chapter(row)) : Optional.empty();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the requested chapter", exception);
    }
  }

  public AssetRecord registerAsset(
      UUID ownerUserId,
      UUID libraryId,
      String storageKey,
      byte[] contentHash,
      String mediaType,
      long byteSize,
      String originalName) {
    Objects.requireNonNull(ownerUserId, "owner user id is required");
    Objects.requireNonNull(libraryId, "library id is required");
    UUID assetId = UUID.randomUUID();
    try (Connection connection = dataSource.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                """
                     INSERT INTO assets (id, owner_user_id, library_id, storage_key, content_hash,
                                         media_type, byte_size, original_name, status)
                     SELECT ?, l.owner_user_id, l.id, ?, ?, ?, ?, ?, 'staged'
                     FROM libraries l
                     WHERE l.id = ? AND can_access_library(l.id, ?, TRUE)
                     RETURNING id, owner_user_id, library_id, storage_key, content_hash,
                               media_type, byte_size, original_name, status, created_at
                     """)) {
      insert.setObject(1, assetId);
      insert.setString(2, storageKey);
      insert.setBytes(3, contentHash);
      insert.setString(4, mediaType);
      insert.setLong(5, byteSize);
      insert.setString(6, originalName);
      insert.setObject(7, libraryId);
      insert.setObject(8, ownerUserId);
      try (ResultSet row = insert.executeQuery()) {
        if (!row.next()) throw new LibraryNotFoundException();
        return asset(row);
      }
    } catch (LibraryNotFoundException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw new DuplicateAssetException();
      throw new IllegalStateException("Unable to register asset metadata", exception);
    }
  }

  private static String workSelect() {
    return """
                SELECT w.id, w.library_id, w.owner_user_id, w.title, w.author, w.source_language,
                       w.visibility, w.status, w.source_hash, w.created_at,
                       count(c.id) FILTER (WHERE c.deleted_at IS NULL) AS chapter_count
                FROM works w
                LEFT JOIN chapters c ON c.work_id = w.id
                """;
  }

  private static LibraryRecord library(ResultSet row) throws SQLException {
    return new LibraryRecord(
        row.getObject("id", UUID.class),
        row.getObject("owner_user_id", UUID.class),
        row.getString("name"),
        row.getString("visibility"),
        row.getBoolean("is_default"),
        instant(row, "created_at"),
        row.getObject("workspace_id", UUID.class));
  }

  private static WorkRecord work(ResultSet row) throws SQLException {
    return new WorkRecord(
        row.getObject("id", UUID.class),
        row.getObject("library_id", UUID.class),
        row.getObject("owner_user_id", UUID.class),
        row.getString("title"),
        row.getString("author"),
        row.getString("source_language"),
        row.getString("visibility"),
        row.getString("status"),
        row.getBytes("source_hash"),
        row.getInt("chapter_count"),
        instant(row, "created_at"));
  }

  private static VolumeRecord volume(ResultSet row) throws SQLException {
    return new VolumeRecord(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getInt("ordinal"),
        row.getString("title"),
        instant(row, "created_at"));
  }

  private static ChapterRecord chapter(ResultSet row) throws SQLException {
    return new ChapterRecord(
        row.getObject("id", UUID.class),
        row.getObject("work_id", UUID.class),
        row.getObject("volume_id", UUID.class),
        row.getInt("ordinal"),
        row.getString("title"),
        row.getString("source_language"),
        row.getObject("source_revision_id", UUID.class),
        row.getString("status"),
        instant(row, "created_at"));
  }

  private static ChapterOutlineRecord chapterOutline(ResultSet row) throws SQLException {
    String status = row.getString("status");
    UUID sourceRevisionId = row.getObject("source_revision_id", UUID.class);
    return new ChapterOutlineRecord(
        row.getObject("id", UUID.class),
        row.getInt("ordinal"),
        row.getString("title"),
        row.getString("source_language"),
        status,
        "ready".equals(status) && sourceRevisionId != null,
        row.getBoolean("has_saved_draft_content"),
        instant(row, "created_at"));
  }

  private static RevisionRecord revision(ResultSet row) throws SQLException {
    return new RevisionRecord(
        row.getObject("id", UUID.class),
        row.getObject("chapter_id", UUID.class),
        row.getInt("revision_no"),
        row.getString("source_format"),
        row.getBytes("content_hash"),
        instant(row, "created_at"));
  }

  private static AssetRecord asset(ResultSet row) throws SQLException {
    return new AssetRecord(
        row.getObject("id", UUID.class),
        row.getObject("owner_user_id", UUID.class),
        row.getObject("library_id", UUID.class),
        row.getString("storage_key"),
        row.getBytes("content_hash"),
        row.getString("media_type"),
        row.getLong("byte_size"),
        row.getString("original_name"),
        row.getString("status"),
        instant(row, "created_at"));
  }

  private static TagRecord tag(ResultSet row) throws SQLException {
    return new TagRecord(
        row.getObject("id", UUID.class),
        row.getObject("owner_user_id", UUID.class),
        row.getString("slug"),
        row.getString("name"),
        instant(row, "created_at"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  public record LibraryRecord(
      UUID id,
      UUID ownerUserId,
      String name,
      String visibility,
      boolean isDefault,
      Instant createdAt,
      UUID workspaceId) {}

  public record WorkRecord(
      UUID id,
      UUID libraryId,
      UUID ownerUserId,
      String title,
      String author,
      String language,
      String visibility,
      String status,
      byte[] sourceHash,
      int chapterCount,
      Instant createdAt) {
    public WorkRecord {
      sourceHash = sourceHash == null ? null : sourceHash.clone();
    }

    @Override
    public byte[] sourceHash() {
      return sourceHash == null ? null : sourceHash.clone();
    }
  }

  public record PrivateAuthoredWorkDetails(
      UUID id,
      String title,
      String author,
      String language,
      String visibility,
      int chapterCount,
      Instant createdAt,
      long metadataVersion) {}

  public record VolumeRecord(UUID id, UUID workId, int ordinal, String title, Instant createdAt) {}

  public record ChapterRecord(
      UUID id,
      UUID workId,
      UUID volumeId,
      int ordinal,
      String title,
      String language,
      UUID sourceRevisionId,
      String status,
      Instant createdAt) {}

  public record ChapterOutlineRecord(
      UUID id,
      int ordinal,
      String title,
      String language,
      String status,
      boolean contentAvailable,
      boolean hasSavedDraftContent,
      Instant createdAt) {}

  public record ChapterOutlinePage(
      List<ChapterOutlineRecord> items, boolean hasMore, Integer lastOrdinal) {
    public ChapterOutlinePage {
      items = List.copyOf(items);
    }
  }

  public record AuthoredWorkPage(
      List<WorkRecord> items, boolean hasMore, Instant lastCreatedAt, UUID lastWorkId) {
    public AuthoredWorkPage {
      items = List.copyOf(items);
      if ((lastCreatedAt == null) != (lastWorkId == null)) {
        throw new IllegalArgumentException(
            "last work cursor values must be both present or absent");
      }
    }
  }

  public record SegmentInput(
      int ordinal, String type, String sourceText, String stableAnchor, byte[] checksum) {
    public SegmentInput {
      checksum = checksum.clone();
    }

    @Override
    public byte[] checksum() {
      return checksum.clone();
    }
  }

  public record RevisionRecord(
      UUID id,
      UUID chapterId,
      int revisionNo,
      String sourceFormat,
      byte[] contentHash,
      Instant createdAt) {
    public RevisionRecord {
      contentHash = contentHash.clone();
    }

    @Override
    public byte[] contentHash() {
      return contentHash.clone();
    }
  }

  public record DraftRevisionWrite(RevisionRecord revision, boolean replayed) {}

  public record DraftSegmentRecord(
      int ordinal, String type, String sourceText, String stableAnchor) {}

  public record PrivateAuthoredDraftRecord(
      UUID chapterId,
      UUID workId,
      String title,
      int revisionNo,
      String sourceFormat,
      String normalizedContent,
      Instant updatedAt,
      List<DraftSegmentRecord> segments) {
    public PrivateAuthoredDraftRecord {
      segments = List.copyOf(segments);
    }
  }

  public record AssetRecord(
      UUID id,
      UUID ownerUserId,
      UUID libraryId,
      String storageKey,
      byte[] contentHash,
      String mediaType,
      long byteSize,
      String originalName,
      String status,
      Instant createdAt) {
    public AssetRecord {
      contentHash = contentHash.clone();
    }

    @Override
    public byte[] contentHash() {
      return contentHash.clone();
    }
  }

  public record TagRecord(UUID id, UUID ownerUserId, String slug, String name, Instant createdAt) {}

  public static class LibraryRepositoryException extends RuntimeException {}

  public static class LibraryOwnerNotFoundException extends LibraryRepositoryException {}

  public static class LibraryNameConflictException extends LibraryRepositoryException {}

  public static class LibraryNotFoundException extends LibraryRepositoryException {}

  public static class DefaultLibraryCannotMoveException extends LibraryRepositoryException {}

  public static class DuplicateImportedWorkException extends LibraryRepositoryException {}

  public static class WorkNotFoundException extends LibraryRepositoryException {}

  public static class ChapterNotFoundException extends LibraryRepositoryException {}

  public static class ChapterHasSavedContentException extends LibraryRepositoryException {}

  public static class OrderConflictException extends LibraryRepositoryException {}

  public static class DuplicateRevisionException extends LibraryRepositoryException {}

  public static class DraftRevisionConflictException extends LibraryRepositoryException {
    private final int currentRevisionNo;

    public DraftRevisionConflictException(int currentRevisionNo) {
      this.currentRevisionNo = currentRevisionNo;
    }

    public int currentRevisionNo() {
      return currentRevisionNo;
    }
  }

  public static class DuplicateAssetException extends LibraryRepositoryException {}

  public record Slice<T>(List<T> items, boolean hasMore, LibraryCursorCodec.Position nextPosition) {
    public Slice {
      items = List.copyOf(items);
    }
  }
}
