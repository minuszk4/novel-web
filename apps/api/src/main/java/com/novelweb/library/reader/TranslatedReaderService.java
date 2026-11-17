package com.novelweb.library.reader;

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
import org.springframework.stereotype.Service;

/** Reads only the current, owner-released human translation version. */
@Service
@Profile("postgres")
public class TranslatedReaderService {

  private static final int MAX_CONTENT_LIMIT = 1000;

  private final DataSource dataSource;
  private final ReaderCursorCodec cursorCodec;

  public TranslatedReaderService(DataSource dataSource, ReaderCursorCodec cursorCodec) {
    this.dataSource = Objects.requireNonNull(dataSource, "data source is required");
    this.cursorCodec = Objects.requireNonNull(cursorCodec, "reader cursor codec is required");
  }

  public TranslationContent content(
      UUID actorUserId, UUID chapterId, String targetLanguage, String cursor, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    if (targetLanguage == null || !targetLanguage.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")) {
      throw new IllegalArgumentException("target language must be a BCP 47 language tag");
    }
    if (limit < 1 || limit > MAX_CONTENT_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and 1000");
    }
    cursorCodec.requireConfigured();

    try (Connection connection = dataSource.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        TranslationBinding binding =
            findCurrentBinding(connection, actorUserId, chapterId, targetLanguage);
        if (binding == null) {
          connection.commit();
          throw new ReaderService.ReaderNotFoundException();
        }
        ReaderCursorCodec.Context context =
            ReaderCursorCodec.contentContext(actorUserId, chapterId, binding.versionId());
        int afterOrdinal;
        try {
          afterOrdinal = cursor == null ? 0 : cursorCodec.decode(cursor, context);
        } catch (ReaderCursorCodec.InvalidCursorException exception) {
          connection.commit();
          throw exception;
        }

        List<TranslationSegment> selected = new ArrayList<>(limit + 1);
        try (PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT segment.segment_ordinal, segment.segment_type,
                           segment.stable_anchor, segment.translated_text
                      FROM translation_version_segments segment
                     WHERE segment.translation_version_id = ?
                       AND segment.segment_ordinal > ?
                       AND segment.segment_type IN ('paragraph', 'heading', 'quote')
                     ORDER BY segment.segment_ordinal
                     LIMIT ?
                    """)) {
          select.setObject(1, binding.versionId());
          select.setInt(2, afterOrdinal);
          select.setInt(3, limit + 1);
          try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
              selected.add(
                  new TranslationSegment(
                      rows.getInt("segment_ordinal"),
                      rows.getString("segment_type"),
                      rows.getString("stable_anchor"),
                      rows.getString("translated_text")));
            }
          }
        }
        boolean hasMore = selected.size() > limit;
        List<TranslationSegment> items =
            List.copyOf(selected.subList(0, Math.min(limit, selected.size())));
        String nextCursor = hasMore ? cursorCodec.encode(context, items.getLast().ordinal()) : null;
        connection.commit();
        return new TranslationContent(
            chapterId,
            binding.workId(),
            binding.sourceRevisionId(),
            binding.versionId(),
            binding.versionNo(),
            binding.sourceLanguage(),
            binding.targetLanguage(),
            items.stream()
                .map(
                    segment ->
                        new TranslatedSegment(
                            segment.anchor(), segment.type(), segment.translatedText()))
                .toList(),
            nextCursor);
      } catch (SQLException exception) {
        rollback(connection, exception);
        throw exception;
      } catch (RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load the released chapter translation", exception);
    }
  }

  public List<String> availableLanguages(UUID actorUserId, UUID chapterId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(chapterId, "chapter id is required");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                """
                    SELECT version.target_language
                      FROM translation_versions version
                      JOIN chapters chapter
                        ON chapter.id = version.chapter_id
                       AND chapter.source_revision_id = version.source_revision_id
                      JOIN works work ON work.id = version.work_id AND work.id = chapter.work_id
                     WHERE chapter.id = ? AND version.status = 'current'
                       AND chapter.status = 'ready' AND chapter.deleted_at IS NULL
                       AND work.deleted_at IS NULL AND work.visibility = 'public'
                       AND work.status = 'active' AND work.source_kind = 'authored'
                       AND publication_scope_access_is_current(work.id, 'PUBLIC_METADATA')
                       AND publication_scope_access_is_current(work.id, 'CHAPTER_TEXT')
                       AND publication_scope_access_is_current(work.id, 'TRANSLATION_ADAPTATION')
                       AND (can_access_library(work.library_id, ?, FALSE)
                            OR EXISTS (SELECT 1 FROM content_entitlements entitlement
                                        WHERE entitlement.user_id = ?
                                          AND entitlement.chapter_id = chapter.id))
                       AND EXISTS (SELECT 1 FROM translation_release_events release_event
                                    WHERE release_event.translation_version_id = version.id)
                     ORDER BY version.target_language
                     LIMIT 51
                    """)) {
      select.setObject(1, chapterId);
      select.setObject(2, actorUserId);
      select.setObject(3, actorUserId);
      try (ResultSet rows = select.executeQuery()) {
        List<String> languages = new ArrayList<>();
        while (rows.next()) languages.add(rows.getString("target_language"));
        if (languages.size() > 50) {
          throw new IllegalStateException(
              "The chapter has too many released translation languages");
        }
        return List.copyOf(languages);
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Unable to load released chapter translations", exception);
    }
  }

  private static TranslationBinding findCurrentBinding(
      Connection connection, UUID actorUserId, UUID chapterId, String targetLanguage)
      throws SQLException {
    try (PreparedStatement select =
        connection.prepareStatement(
            """
                SELECT version.id, version.work_id, version.source_revision_id,
                       version.version_no, chapter.source_language
                  FROM translation_versions version
                  JOIN chapters chapter
                    ON chapter.id = version.chapter_id
                   AND chapter.source_revision_id = version.source_revision_id
                  JOIN works work ON work.id = version.work_id AND work.id = chapter.work_id
                 WHERE chapter.id = ? AND version.target_language = ?
                   AND version.status = 'current'
                   AND chapter.status = 'ready' AND chapter.deleted_at IS NULL
                   AND work.deleted_at IS NULL AND work.visibility = 'public'
                   AND work.status = 'active' AND work.source_kind = 'authored'
                   AND publication_scope_access_is_current(work.id, 'PUBLIC_METADATA')
                   AND publication_scope_access_is_current(work.id, 'CHAPTER_TEXT')
                   AND publication_scope_access_is_current(work.id, 'TRANSLATION_ADAPTATION')
                   AND (can_access_library(work.library_id, ?, FALSE)
                        OR EXISTS (SELECT 1 FROM content_entitlements entitlement
                                    WHERE entitlement.user_id = ?
                                      AND entitlement.chapter_id = chapter.id))
                   AND EXISTS (SELECT 1 FROM translation_release_events release_event
                                WHERE release_event.translation_version_id = version.id)
                 LIMIT 1
                """)) {
      select.setObject(1, chapterId);
      select.setString(2, targetLanguage);
      select.setObject(3, actorUserId);
      select.setObject(4, actorUserId);
      try (ResultSet row = select.executeQuery()) {
        return row.next()
            ? new TranslationBinding(
                row.getObject("id", UUID.class),
                row.getObject("work_id", UUID.class),
                row.getObject("source_revision_id", UUID.class),
                row.getInt("version_no"),
                row.getString("source_language"),
                targetLanguage)
            : null;
      }
    }
  }

  private static void rollback(Connection connection, RuntimeException exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private static void rollback(Connection connection, SQLException exception) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      exception.addSuppressed(rollbackException);
    }
  }

  private record TranslationBinding(
      UUID versionId,
      UUID workId,
      UUID sourceRevisionId,
      int versionNo,
      String sourceLanguage,
      String targetLanguage) {}

  private record TranslationSegment(
      int ordinal, String type, String anchor, String translatedText) {}

  public record TranslationContent(
      UUID chapterId,
      UUID workId,
      UUID sourceRevisionId,
      UUID translationVersionId,
      int translationVersionNo,
      String sourceLanguage,
      String targetLanguage,
      List<TranslatedSegment> segments,
      String nextSegmentCursor) {
    public TranslationContent {
      segments = List.copyOf(segments);
    }
  }

  public record TranslatedSegment(String anchor, String type, String translatedText) {}
}
