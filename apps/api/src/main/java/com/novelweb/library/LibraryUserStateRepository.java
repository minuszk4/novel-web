package com.novelweb.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class LibraryUserStateRepository {

    public static final int MAX_COLLECTIONS = 12;

    private final DataSource dataSource;

    public LibraryUserStateRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public CollectionRecord createCollection(UUID actorUserId, String name) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        Objects.requireNonNull(name, "collection name is required");
        return inTransaction("Unable to create a library collection", connection -> {
            requireActiveUserForUpdate(connection, actorUserId);
            try (PreparedStatement count = connection.prepareStatement("""
                    SELECT count(*) FROM library_collections
                    WHERE owner_user_id = ? AND deleted_at IS NULL
                    """)) {
                count.setObject(1, actorUserId);
                try (ResultSet row = count.executeQuery()) {
                    row.next();
                    if (row.getInt(1) >= MAX_COLLECTIONS) {
                        throw new CollectionLimitReachedException();
                    }
                }
            }

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO library_collections (id, owner_user_id, name)
                    VALUES (?, ?, ?)
                    RETURNING id, name, created_at, updated_at
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, actorUserId);
                insert.setString(3, name);
                try (ResultSet row = insert.executeQuery()) {
                    row.next();
                    return collection(row, 0);
                }
            }
        });
    }

    public List<CollectionRecord> listCollections(UUID actorUserId) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT c.id, c.name, c.created_at, c.updated_at,
                            (SELECT count(*)
                             FROM library_collection_works cw
                             JOIN works w ON w.id = cw.work_id
                             WHERE cw.owner_user_id = c.owner_user_id
                               AND cw.collection_id = c.id
                               AND w.deleted_at IS NULL
                               AND can_access_library(w.library_id, c.owner_user_id, FALSE)) AS work_count
                     FROM library_collections c
                     WHERE c.owner_user_id = ? AND c.deleted_at IS NULL
                     ORDER BY c.created_at, c.id
                     """)) {
            select.setObject(1, actorUserId);
            try (ResultSet rows = select.executeQuery()) {
                List<CollectionRecord> collections = new ArrayList<>();
                while (rows.next()) collections.add(collection(rows, rows.getInt("work_count")));
                return List.copyOf(collections);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to list library collections", exception);
        }
    }

    public CollectionRecord deleteCollection(UUID actorUserId, UUID collectionId) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        Objects.requireNonNull(collectionId, "collection id is required");
        return inTransaction("Unable to delete the library collection", connection -> {
            CollectionRecord deleted;
            try (PreparedStatement bumpWorkStates = connection.prepareStatement("""
                    UPDATE library_reading_states s
                    SET version = s.version + 1, updated_at = clock_timestamp()
                    FROM library_collection_works cw
                    WHERE s.user_id = ? AND cw.owner_user_id = s.user_id
                      AND cw.collection_id = ? AND cw.work_id = s.work_id
                    """)) {
                bumpWorkStates.setObject(1, actorUserId);
                bumpWorkStates.setObject(2, collectionId);
                bumpWorkStates.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE library_collections
                    SET deleted_at = clock_timestamp(), updated_at = clock_timestamp()
                    WHERE id = ? AND owner_user_id = ? AND deleted_at IS NULL
                    RETURNING id, name, created_at, updated_at
                    """)) {
                update.setObject(1, collectionId);
                update.setObject(2, actorUserId);
                try (ResultSet row = update.executeQuery()) {
                    if (!row.next()) throw new LibraryUserStateNotFoundException();
                    deleted = collection(row, 0);
                }
            }
            try (PreparedStatement removeAssignments = connection.prepareStatement("""
                    DELETE FROM library_collection_works
                    WHERE owner_user_id = ? AND collection_id = ?
                    """)) {
                removeAssignments.setObject(1, actorUserId);
                removeAssignments.setObject(2, collectionId);
                removeAssignments.executeUpdate();
            }
            return deleted;
        });
    }

    public WorkStateRecord findWorkState(UUID actorUserId, UUID workId) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        Objects.requireNonNull(workId, "work id is required");
        try (Connection connection = dataSource.getConnection()) {
            return findWorkState(connection, actorUserId, workId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to load work reading state", exception);
        }
    }

    public WorkStateRecord replaceWorkState(
            UUID actorUserId, UUID workId, long expectedVersion, String status, List<UUID> requestedCollectionIds) {
        Objects.requireNonNull(actorUserId, "actor user id is required");
        Objects.requireNonNull(workId, "work id is required");
        Objects.requireNonNull(status, "reading status is required");
        List<UUID> collectionIds = List.copyOf(requestedCollectionIds);
        return inTransaction("Unable to update work reading state", connection -> {
            requireAccessibleWorkForUpdate(connection, actorUserId, workId);
            long currentVersion = currentVersion(connection, actorUserId, workId);
            WorkStateRecord currentState = findWorkState(connection, actorUserId, workId);
            if (currentVersion != expectedVersion) {
                if (sameState(currentState, status, collectionIds)) return currentState;
                throw new LibraryStateVersionConflictException(currentState);
            }
            requireOwnedCollections(connection, actorUserId, collectionIds);
            if (sameState(currentState, status, collectionIds)) return currentState;

            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO library_reading_states (user_id, work_id, status, version)
                    VALUES (?, ?, ?, 1)
                    ON CONFLICT (user_id, work_id) DO UPDATE
                    SET status = EXCLUDED.status, version = library_reading_states.version + 1,
                        updated_at = clock_timestamp()
                    """)) {
                upsert.setObject(1, actorUserId);
                upsert.setObject(2, workId);
                upsert.setString(3, status);
                upsert.executeUpdate();
            }

            try (PreparedStatement removeAssignments = connection.prepareStatement("""
                    DELETE FROM library_collection_works
                    WHERE owner_user_id = ? AND work_id = ?
                    """)) {
                removeAssignments.setObject(1, actorUserId);
                removeAssignments.setObject(2, workId);
                removeAssignments.executeUpdate();
            }

            if (!collectionIds.isEmpty()) {
                try (PreparedStatement insertAssignment = connection.prepareStatement("""
                        INSERT INTO library_collection_works (owner_user_id, collection_id, work_id)
                        VALUES (?, ?, ?)
                        """)) {
                    for (UUID collectionId : collectionIds) {
                        insertAssignment.setObject(1, actorUserId);
                        insertAssignment.setObject(2, collectionId);
                        insertAssignment.setObject(3, workId);
                        insertAssignment.addBatch();
                    }
                    insertAssignment.executeBatch();
                }
            }
            return findWorkState(connection, actorUserId, workId);
        });
    }

    private static void requireActiveUserForUpdate(Connection connection, UUID actorUserId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE
                """)) {
            select.setObject(1, actorUserId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) throw new LibraryUserStateNotFoundException();
            }
        }
    }

    private static void requireAccessibleWorkForUpdate(
            Connection connection, UUID actorUserId, UUID workId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT w.id
                FROM works w
                WHERE w.id = ? AND w.deleted_at IS NULL
                  AND can_access_library(w.library_id, ?, FALSE)
                FOR UPDATE OF w
                """)) {
            select.setObject(1, workId);
            select.setObject(2, actorUserId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) throw new LibraryUserStateNotFoundException();
            }
        }
    }

    private static void requireOwnedCollections(
            Connection connection, UUID actorUserId, List<UUID> collectionIds) throws SQLException {
        if (collectionIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(collectionIds.size(), "?"));
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id FROM library_collections
                WHERE owner_user_id = ? AND deleted_at IS NULL AND id IN (%s)
                ORDER BY id FOR SHARE
                """.formatted(placeholders))) {
            select.setObject(1, actorUserId);
            for (int index = 0; index < collectionIds.size(); index++) {
                select.setObject(index + 2, collectionIds.get(index));
            }
            int found = 0;
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) found++;
            }
            if (found != collectionIds.size()) throw new LibraryUserStateNotFoundException();
        }
    }

    private static long currentVersion(Connection connection, UUID actorUserId, UUID workId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT version FROM library_reading_states WHERE user_id = ? AND work_id = ? FOR UPDATE
                """)) {
            select.setObject(1, actorUserId);
            select.setObject(2, workId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? row.getLong("version") : 0;
            }
        }
    }

    private static boolean sameState(WorkStateRecord current, String status, List<UUID> collectionIds) {
        return current.readingStatus().equals(status)
                && Set.copyOf(current.collectionIds()).equals(Set.copyOf(collectionIds));
    }

    private static WorkStateRecord findWorkState(Connection connection, UUID actorUserId, UUID workId)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT w.id,
                       COALESCE(s.status, 'want-to-read') AS reading_status,
                       COALESCE(s.version, 0) AS state_version,
                       s.updated_at,
                       ARRAY(SELECT cw.collection_id
                             FROM library_collection_works cw
                             JOIN library_collections c ON c.id = cw.collection_id
                                                    AND c.owner_user_id = cw.owner_user_id
                             WHERE cw.owner_user_id = ? AND cw.work_id = w.id
                               AND c.deleted_at IS NULL
                             ORDER BY cw.collection_id) AS collection_ids
                FROM works w
                LEFT JOIN library_reading_states s ON s.work_id = w.id AND s.user_id = ?
                WHERE w.id = ? AND w.deleted_at IS NULL
                  AND can_access_library(w.library_id, ?, FALSE)
                """)) {
            select.setObject(1, actorUserId);
            select.setObject(2, actorUserId);
            select.setObject(3, workId);
            select.setObject(4, actorUserId);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) throw new LibraryUserStateNotFoundException();
                OffsetDateTime updatedAt = row.getObject("updated_at", OffsetDateTime.class);
                java.sql.Array sqlIds = row.getArray("collection_ids");
                try {
                    return new WorkStateRecord(
                            row.getObject("id", UUID.class),
                            row.getString("reading_status"),
                            collectionIds(sqlIds),
                            row.getLong("state_version"),
                            updatedAt == null ? null : updatedAt.toInstant());
                } finally {
                    if (sqlIds != null) sqlIds.free();
                }
            }
        }
    }

    private static List<UUID> collectionIds(java.sql.Array sqlIds) throws SQLException {
        if (sqlIds == null) return List.of();
        Object raw = sqlIds.getArray();
        if (!(raw instanceof Object[] values)) return List.of();
        List<UUID> ids = new ArrayList<>(values.length);
        for (Object value : values) {
            if (value instanceof UUID id) ids.add(id);
            else if (value != null) ids.add(UUID.fromString(value.toString()));
        }
        return List.copyOf(ids);
    }

    private <T> T inTransaction(String failureMessage, SqlTransaction<T> action) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = action.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (LibraryUserStateRepositoryException exception) {
            throw exception;
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) throw new CollectionNameConflictException();
            throw new IllegalStateException(failureMessage, exception);
        }
    }

    private static CollectionRecord collection(ResultSet row, int workCount) throws SQLException {
        return new CollectionRecord(
                row.getObject("id", UUID.class),
                row.getString("name"),
                workCount,
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    @FunctionalInterface
    private interface SqlTransaction<T> {
        T run(Connection connection) throws SQLException;
    }

    public record CollectionRecord(
            UUID id, String name, int workCount, java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    public record WorkStateRecord(
            UUID workId, String readingStatus, List<UUID> collectionIds,
            long version, java.time.Instant updatedAt) {
        public WorkStateRecord {
            collectionIds = List.copyOf(collectionIds);
        }
    }

    public static class LibraryUserStateRepositoryException extends RuntimeException {
    }

    public static class LibraryUserStateNotFoundException extends LibraryUserStateRepositoryException {
    }

    public static class CollectionLimitReachedException extends LibraryUserStateRepositoryException {
    }

    public static class CollectionNameConflictException extends LibraryUserStateRepositoryException {
    }

    public static class LibraryStateVersionConflictException extends LibraryUserStateRepositoryException {
        private final WorkStateRecord latestState;

        public LibraryStateVersionConflictException(WorkStateRecord latestState) {
            this.latestState = latestState;
        }

        public WorkStateRecord latestState() {
            return latestState;
        }
    }
}
