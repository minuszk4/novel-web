package com.novelweb.library;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

    public LibraryRecord createLibrary(UUID ownerUserId, String name) {
        Objects.requireNonNull(ownerUserId, "owner user id is required");
        UUID libraryId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                boolean isDefault;
                try (PreparedStatement owner = connection.prepareStatement("""
                        SELECT id FROM users WHERE id = ? AND status = 'active' FOR UPDATE
                        """)) {
                    owner.setObject(1, ownerUserId);
                    try (ResultSet row = owner.executeQuery()) {
                        if (!row.next()) throw new LibraryOwnerNotFoundException();
                    }
                }
                try (PreparedStatement existing = connection.prepareStatement("""
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
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO libraries (id, owner_user_id, name, is_default)
                        VALUES (?, ?, ?, ?)
                        RETURNING id, owner_user_id, name, visibility, is_default, created_at
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

    public Slice<LibraryRecord> findLibraries(
            UUID ownerUserId, int limit, LibraryCursorCodec.Position cursor) {
        Objects.requireNonNull(ownerUserId, "owner user id is required");
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.owner_user_id, l.name, l.visibility, l.is_default, l.created_at
                FROM libraries l
                WHERE l.owner_user_id = ? AND l.deleted_at IS NULL
                """);
        if (cursor != null) {
            sql.append("""
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
                select.setObject(parameter++, OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
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
                    nextPosition = new LibraryCursorCodec.Position(
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
             PreparedStatement select = connection.prepareStatement("""
                     SELECT id, owner_user_id, name, visibility, is_default, created_at
                     FROM libraries
                     WHERE owner_user_id = ? AND id = ? AND deleted_at IS NULL
                     """)) {
            select.setObject(1, ownerUserId);
            select.setObject(2, libraryId);
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
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO works (id, library_id, owner_user_id, title, author, source_language,
                                        source_kind, visibility, status, source_hash)
                     SELECT ?, l.id, l.owner_user_id, ?, ?, ?, 'imported', 'private', 'active', ?
                     FROM libraries l
                     WHERE l.id = ? AND l.owner_user_id = ? AND l.deleted_at IS NULL
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

    public Optional<WorkRecord> findWork(UUID ownerUserId, UUID workId) {
        Objects.requireNonNull(ownerUserId, "owner user id is required");
        Objects.requireNonNull(workId, "work id is required");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(workSelect() + """
                     WHERE w.owner_user_id = ? AND w.id = ? AND w.deleted_at IS NULL
                     GROUP BY w.id
                     """)) {
            select.setObject(1, ownerUserId);
            select.setObject(2, workId);
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
                try (PreparedStatement work = connection.prepareStatement("""
                        SELECT id FROM works
                        WHERE id = ? AND owner_user_id = ? AND deleted_at IS NULL
                        FOR UPDATE
                        """)) {
                    work.setObject(1, workId);
                    work.setObject(2, ownerUserId);
                    try (ResultSet row = work.executeQuery()) {
                        if (!row.next()) throw new WorkNotFoundException();
                    }
                }

                TagRecord tag;
                try (PreparedStatement insertTag = connection.prepareStatement("""
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

                try (PreparedStatement link = connection.prepareStatement("""
                        INSERT INTO work_tags (owner_user_id, work_id, tag_id)
                        VALUES (?, ?, ?)
                        ON CONFLICT (owner_user_id, work_id, tag_id) DO NOTHING
                        """)) {
                    link.setObject(1, ownerUserId);
                    link.setObject(2, workId);
                    link.setObject(3, tag.id());
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
        StringBuilder workSql = new StringBuilder(workSelect()).append("""
                WHERE w.owner_user_id = ? AND w.library_id = ? AND w.deleted_at IS NULL
                """);
        if (!normalizedQuery.isEmpty()) workSql.append("AND (w.title ILIKE ? OR w.author ILIKE ?) ");
        if (!normalizedStatus.isEmpty()) workSql.append("AND w.status = ? ");
        if (!normalizedTag.isEmpty()) workSql.append("""
                AND EXISTS (
                    SELECT 1 FROM work_tags wt
                    JOIN tags t ON t.owner_user_id = wt.owner_user_id AND t.id = wt.tag_id
                    WHERE wt.owner_user_id = w.owner_user_id AND wt.work_id = w.id
                      AND t.slug = ? AND t.deleted_at IS NULL)
                """);
        if (cursor != null) workSql.append("""
                AND (w.created_at, w.id) < (CAST(? AS TIMESTAMPTZ), CAST(? AS UUID))
                """);
        workSql.append("GROUP BY w.id ORDER BY w.created_at DESC, w.id DESC LIMIT ?");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement(workSql.toString())) {
            int parameter = 1;
            select.setObject(parameter++, ownerUserId);
            select.setObject(parameter++, libraryId);
            if (!normalizedQuery.isEmpty()) {
                select.setString(parameter++, "%" + escapedQuery + "%");
                select.setString(parameter++, "%" + escapedQuery + "%");
            }
            if (!normalizedStatus.isEmpty()) select.setString(parameter++, normalizedStatus);
            if (!normalizedTag.isEmpty()) select.setString(parameter++, normalizedTag);
            if (cursor != null) {
                select.setObject(parameter++, OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
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
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO volumes (id, work_id, ordinal, title)
                     SELECT ?, w.id, ?, ? FROM works w
                     WHERE w.id = ? AND w.owner_user_id = ? AND w.deleted_at IS NULL
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
            UUID ownerUserId, UUID workId, UUID volumeId, int ordinal, String title, String sourceLanguage) {
        Objects.requireNonNull(ownerUserId, "owner user id is required");
        Objects.requireNonNull(workId, "work id is required");
        UUID chapterId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO chapters (id, work_id, volume_id, ordinal, title, source_language)
                     SELECT ?, w.id, CAST(? AS UUID), ?, ?, ? FROM works w
                     WHERE w.id = ? AND w.owner_user_id = ? AND w.deleted_at IS NULL
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
                try (PreparedStatement lock = connection.prepareStatement("""
                        SELECT c.work_id FROM chapters c
                        JOIN works w ON w.id = c.work_id
                        WHERE c.id = ? AND w.owner_user_id = ? AND w.deleted_at IS NULL
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
                try (PreparedStatement next = connection.prepareStatement(
                        "SELECT COALESCE(MAX(revision_no), 0) + 1 FROM chapter_revisions WHERE chapter_id = ?")) {
                    next.setObject(1, chapterId);
                    try (ResultSet row = next.executeQuery()) {
                        row.next();
                        revisionNo = row.getInt(1);
                    }
                }

                RevisionRecord revision;
                try (PreparedStatement insert = connection.prepareStatement("""
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

                try (PreparedStatement insertSegment = connection.prepareStatement("""
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

                try (PreparedStatement update = connection.prepareStatement("""
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

    public Optional<ChapterRecord> findChapter(UUID ownerUserId, UUID chapterId) {
        Objects.requireNonNull(ownerUserId, "owner user id is required");
        Objects.requireNonNull(chapterId, "chapter id is required");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("""
                     SELECT c.id, c.work_id, c.volume_id, c.ordinal, c.title, c.source_language,
                            c.source_revision_id, c.status, c.created_at
                     FROM chapters c JOIN works w ON w.id = c.work_id
                     WHERE c.id = ? AND w.owner_user_id = ? AND c.deleted_at IS NULL AND w.deleted_at IS NULL
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
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO assets (id, owner_user_id, library_id, storage_key, content_hash,
                                         media_type, byte_size, original_name, status)
                     SELECT ?, l.owner_user_id, l.id, ?, ?, ?, ?, ?, 'staged'
                     FROM libraries l
                     WHERE l.id = ? AND l.owner_user_id = ? AND l.deleted_at IS NULL
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
                instant(row, "created_at"));
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
        return new VolumeRecord(row.getObject("id", UUID.class), row.getObject("work_id", UUID.class),
                row.getInt("ordinal"), row.getString("title"), instant(row, "created_at"));
    }

    private static ChapterRecord chapter(ResultSet row) throws SQLException {
        return new ChapterRecord(row.getObject("id", UUID.class), row.getObject("work_id", UUID.class),
                row.getObject("volume_id", UUID.class), row.getInt("ordinal"), row.getString("title"),
                row.getString("source_language"), row.getObject("source_revision_id", UUID.class),
                row.getString("status"), instant(row, "created_at"));
    }

    private static RevisionRecord revision(ResultSet row) throws SQLException {
        return new RevisionRecord(row.getObject("id", UUID.class), row.getObject("chapter_id", UUID.class),
                row.getInt("revision_no"), row.getString("source_format"), row.getBytes("content_hash"),
                instant(row, "created_at"));
    }

    private static AssetRecord asset(ResultSet row) throws SQLException {
        return new AssetRecord(row.getObject("id", UUID.class), row.getObject("owner_user_id", UUID.class),
                row.getObject("library_id", UUID.class), row.getString("storage_key"), row.getBytes("content_hash"),
                row.getString("media_type"), row.getLong("byte_size"), row.getString("original_name"),
                row.getString("status"), instant(row, "created_at"));
    }

    private static TagRecord tag(ResultSet row) throws SQLException {
        return new TagRecord(row.getObject("id", UUID.class), row.getObject("owner_user_id", UUID.class),
                row.getString("slug"), row.getString("name"), instant(row, "created_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record LibraryRecord(
            UUID id, UUID ownerUserId, String name, String visibility, boolean isDefault, Instant createdAt) {
    }

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

    public record VolumeRecord(UUID id, UUID workId, int ordinal, String title, Instant createdAt) {
    }

    public record ChapterRecord(
            UUID id,
            UUID workId,
            UUID volumeId,
            int ordinal,
            String title,
            String language,
            UUID sourceRevisionId,
            String status,
            Instant createdAt) {
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
            UUID id, UUID chapterId, int revisionNo, String sourceFormat, byte[] contentHash, Instant createdAt) {
        public RevisionRecord {
            contentHash = contentHash.clone();
        }

        @Override
        public byte[] contentHash() {
            return contentHash.clone();
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

    public record TagRecord(UUID id, UUID ownerUserId, String slug, String name, Instant createdAt) {
    }

    public static class LibraryOwnerNotFoundException extends RuntimeException {
    }

    public static class LibraryNameConflictException extends RuntimeException {
    }

    public static class LibraryNotFoundException extends RuntimeException {
    }

    public static class DuplicateImportedWorkException extends RuntimeException {
    }

    public static class WorkNotFoundException extends RuntimeException {
    }

    public static class ChapterNotFoundException extends RuntimeException {
    }

    public static class OrderConflictException extends RuntimeException {
    }

    public static class DuplicateRevisionException extends RuntimeException {
    }

    public static class DuplicateAssetException extends RuntimeException {
    }

    public record Slice<T>(List<T> items, boolean hasMore, LibraryCursorCodec.Position nextPosition) {
        public Slice {
            items = List.copyOf(items);
        }
    }
}
