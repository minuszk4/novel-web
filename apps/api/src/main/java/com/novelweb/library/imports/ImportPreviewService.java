package com.novelweb.library.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/** Owner-scoped access and optimistic editing for private EPUB preview staging data. */
@Service
@Profile("postgres")
public class ImportPreviewService {

    private static final int MAX_PREVIEW_BYTES = 1_048_576;
    private static final int TITLE_LIMIT = 300;
    private static final int AUTHOR_LIMIT = 200;
    private static final int LANGUAGE_LIMIT = 35;

    private final ImportParseJobRepository repository;
    private final ImportPreviewCursorCodec cursorCodec;
    private final ObjectMapper objectMapper;

    public ImportPreviewService(
            ImportParseJobRepository repository,
            ImportPreviewCursorCodec cursorCodec,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.cursorCodec = cursorCodec;
        this.objectMapper = objectMapper;
    }

    public PreviewResult get(UUID userId, UUID importId, String cursor, int limit) {
        if (limit < 1 || limit > 100) return PreviewResult.invalidRequest();
        ImportParseJobRepository.PreviewLookup lookup = repository.findPreview(userId, importId);
        if (lookup.outcome() != ImportParseJobRepository.PreviewLookupOutcome.READY) {
            return fromLookup(lookup);
        }
        ImportParseJobRepository.PreviewRecord record = lookup.preview();
        EpubPackageParser.Preview preview = readPreview(record.previewJson());
        int offset = cursor == null ? 0 : cursorCodec.decode(
                cursor, userId, importId, record.etag(), preview.chapters().size());
        if (offset < 0 || offset > preview.chapters().size()) return PreviewResult.invalidCursor();
        int end = Math.min(preview.chapters().size(), offset + limit);
        List<ChapterPreview> chapters = new ArrayList<>(end - offset);
        for (int index = offset; index < end; index++) {
            EpubPackageParser.Chapter chapter = preview.chapters().get(index);
            chapters.add(new ChapterPreview(chapterId(importId, chapter), index + 1, chapter.title()));
        }
        String nextCursor = end < preview.chapters().size()
                ? cursorCodec.encode(userId, importId, record.etag(), end) : null;
        return PreviewResult.ready(toResponse(importId, record.version(), preview, chapters, nextCursor),
                strongEtag(record.etag()));
    }

    public PreviewResult update(UUID userId, UUID importId, String ifMatch, PatchCommand patch) {
        ImportParseJobRepository.PreviewLookup lookup = repository.findPreview(userId, importId);
        if (lookup.outcome() != ImportParseJobRepository.PreviewLookupOutcome.READY) {
            return fromLookup(lookup);
        }
        if (patch == null || patch.unknownFields()) return PreviewResult.unknownField();
        if (!patch.hasAnyField()) return PreviewResult.invalidRequest();
        byte[] expectedEtag = parseStrongEtag(ifMatch);
        if (expectedEtag == null) return ifMatch == null || ifMatch.isBlank()
                ? PreviewResult.preconditionRequired() : PreviewResult.invalidRequest();

        ImportParseJobRepository.PreviewRecord current = lookup.preview();
        if (!MessageDigest.isEqual(current.etag(), expectedEtag)) return PreviewResult.stale();
        EpubPackageParser.Preview original = readPreview(current.previewJson());
        PatchApplication applied = applyPatch(importId, original, patch);
        if (applied.outcome() != PreviewOutcome.READY) return PreviewResult.outcome(applied.outcome());
        if (!applied.changed()) {
            return PreviewResult.ready(toResponse(importId, current.version(), original,
                    firstPage(importId, original, 50), nextCursor(userId, importId, current.etag(), original, 50)),
                    strongEtag(current.etag()));
        }

        byte[] json = writePreview(applied.preview());
        if (json.length > MAX_PREVIEW_BYTES) return PreviewResult.invalidRequest();
        byte[] newEtag = sha256(json);
        ImportParseJobRepository.PreviewUpdateResult saved = repository.updatePreview(
                userId, importId, expectedEtag, new String(json, StandardCharsets.UTF_8), newEtag);
        if (saved.outcome() != ImportParseJobRepository.PreviewUpdateOutcome.UPDATED) {
            return switch (saved.outcome()) {
                case NOT_FOUND -> PreviewResult.notFound();
                case NOT_READY -> PreviewResult.notReady();
                case EXPIRED -> PreviewResult.expired();
                case STALE -> PreviewResult.stale();
                case UPDATED -> throw new IllegalStateException("Unexpected preview update result");
            };
        }
        EpubPackageParser.Preview updated = readPreview(saved.preview().previewJson());
        return PreviewResult.ready(toResponse(importId, saved.preview().version(), updated,
                firstPage(importId, updated, 50), nextCursor(userId, importId,
                        saved.preview().etag(), updated, 50)), strongEtag(saved.preview().etag()));
    }

    private PatchApplication applyPatch(UUID importId, EpubPackageParser.Preview original, PatchCommand patch) {
        if (!patch.titlePresent() && !patch.authorPresent() && !patch.languagePresent()
                && !patch.chapterOrderPresent()) return PatchApplication.invalidRequest();
        if (patch.titlePresent() && (patch.title() == null || patch.title().isBlank()
                || codePoints(patch.title()) > TITLE_LIMIT)) return PatchApplication.invalidRequest();
        if (patch.authorPresent() && (patch.author() == null || codePoints(patch.author()) > AUTHOR_LIMIT)) {
            return PatchApplication.invalidRequest();
        }
        if (patch.languagePresent() && (patch.language() == null || codePoints(patch.language()) > LANGUAGE_LIMIT)) {
            return PatchApplication.invalidRequest();
        }

        EpubPackageParser.Metadata oldMetadata = original.metadata();
        String title = patch.titlePresent() ? patch.title().trim() : oldMetadata.title();
        String language = patch.languagePresent() ? patch.language().trim() : oldMetadata.language();
        List<String> creators = new ArrayList<>(oldMetadata.creators());
        if (patch.authorPresent()) {
            String author = patch.author().trim();
            if (author.isEmpty()) {
                if (!creators.isEmpty()) creators.remove(0);
            } else if (creators.isEmpty()) {
                creators.add(author);
            } else {
                creators.set(0, author);
            }
        }
        boolean changed = !title.equals(oldMetadata.title())
                || !java.util.Objects.equals(language, oldMetadata.language())
                || !creators.equals(oldMetadata.creators());
        EpubPackageParser.Metadata metadata = new EpubPackageParser.Metadata(title, language,
                oldMetadata.identifier(), oldMetadata.publisher(), oldMetadata.description(), List.copyOf(creators));

        List<EpubPackageParser.Chapter> chapters = original.chapters();
        List<EpubPackageParser.TocEntry> toc = original.toc();
        if (patch.chapterOrderPresent()) {
            if (patch.chapterOrder() == null || patch.chapterOrder().size() != chapters.size()
                    || new HashSet<>(patch.chapterOrder()).size() != patch.chapterOrder().size()) {
                return PatchApplication.invalidMapping();
            }
            Map<UUID, EpubPackageParser.Chapter> byId = new HashMap<>();
            for (int index = 0; index < chapters.size(); index++) {
                EpubPackageParser.Chapter chapter = chapters.get(index);
                byId.put(chapterId(importId, chapter), chapter);
            }
            List<EpubPackageParser.Chapter> reordered = new ArrayList<>(chapters.size());
            for (int nextIndex = 0; nextIndex < patch.chapterOrder().size(); nextIndex++) {
                UUID id;
                try {
                    id = UUID.fromString(patch.chapterOrder().get(nextIndex));
                } catch (IllegalArgumentException | NullPointerException exception) {
                    return PatchApplication.invalidMapping();
                }
                EpubPackageParser.Chapter chapter = byId.get(id);
                if (chapter == null) return PatchApplication.invalidMapping();
                reordered.add(chapter);
            }
            changed |= !patch.chapterOrder().equals(chapters.stream()
                    .map(chapter -> chapterId(importId, chapter).toString()).toList());
            if (changed || !reordered.equals(chapters)) {
                chapters = List.copyOf(reordered);
            }
        }
        if (!changed && !chapters.equals(original.chapters())) changed = true;
        return PatchApplication.ready(new EpubPackageParser.Preview(
                metadata, chapters, toc, original.warnings()), changed);
    }

    private List<ChapterPreview> firstPage(UUID importId, EpubPackageParser.Preview preview, int limit) {
        List<ChapterPreview> chapters = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, preview.chapters().size()); index++) {
            EpubPackageParser.Chapter chapter = preview.chapters().get(index);
            chapters.add(new ChapterPreview(chapterId(importId, chapter), index + 1, chapter.title()));
        }
        return List.copyOf(chapters);
    }

    private String nextCursor(
            UUID userId, UUID importId, byte[] etag, EpubPackageParser.Preview preview, int limit) {
        int nextOffset = Math.min(limit, preview.chapters().size());
        return nextOffset < preview.chapters().size()
                ? cursorCodec.encode(userId, importId, etag, nextOffset) : null;
    }

    private ImportPreview toResponse(
            UUID importId, int version, EpubPackageParser.Preview preview,
            List<ChapterPreview> chapters, String nextCursor) {
        String author = preview.metadata().creators().isEmpty() ? "" : preview.metadata().creators().get(0);
        List<ImportParseService.ImportWarning> warnings = preview.warnings().stream()
                .map(warning -> new ImportParseService.ImportWarning(warning.code(), warning.summary())).toList();
        return new ImportPreview(importId, version, preview.metadata().title(), author,
                preview.chapters().size(), preview.metadata().language(), chapters, nextCursor, warnings);
    }

    private EpubPackageParser.Preview readPreview(String value) {
        try {
            return objectMapper.readValue(value, EpubPackageParser.Preview.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("The private import preview could not be decoded", exception);
        }
    }

    private byte[] writePreview(EpubPackageParser.Preview preview) {
        try {
            return objectMapper.writeValueAsBytes(preview);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("The private import preview could not be encoded", exception);
        }
    }

    private static UUID chapterId(UUID importId, EpubPackageParser.Chapter chapter) {
        byte[] identity = (importId + "\u0000" + chapter.index()).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(identity);
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static byte[] parseStrongEtag(String value) {
        if (value == null || value.length() != 45 || value.charAt(0) != '"'
                || value.charAt(44) != '"' || value.startsWith("W/")) return null;
        String encoded = value.substring(1, 44);
        try {
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(encoded);
            return decoded.length == 32 && java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(decoded).equals(encoded) ? decoded : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String strongEtag(byte[] value) {
        return "\"" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value) + "\"";
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static PreviewResult fromLookup(ImportParseJobRepository.PreviewLookup lookup) {
        return switch (lookup.outcome()) {
            case READY -> throw new IllegalArgumentException("A ready preview requires a response mapping");
            case NOT_FOUND -> PreviewResult.notFound();
            case NOT_READY -> PreviewResult.notReady();
            case EXPIRED -> PreviewResult.expired();
        };
    }

    public record PatchCommand(
            boolean titlePresent, String title,
            boolean authorPresent, String author,
            boolean languagePresent, String language,
            boolean chapterOrderPresent, List<String> chapterOrder,
            boolean unknownFields) {
        public boolean hasAnyField() {
            return titlePresent || authorPresent || languagePresent || chapterOrderPresent;
        }
    }

    public record ImportPreview(
            UUID importId, int version, String title, String author, int chapterCount,
            String language, List<ChapterPreview> chapters, String nextCursor,
            List<ImportParseService.ImportWarning> warnings) { }

    public record ChapterPreview(UUID id, int ordinal, String title) { }

    public enum PreviewOutcome {
        READY, NOT_FOUND, NOT_READY, EXPIRED, INVALID_CURSOR, INVALID_REQUEST, UNKNOWN_FIELD,
        PRECONDITION_REQUIRED, STALE_ETAG
    }

    public record PreviewResult(PreviewOutcome outcome, ImportPreview preview, String etag) {
        static PreviewResult ready(ImportPreview preview, String etag) {
            return new PreviewResult(PreviewOutcome.READY, preview, etag);
        }
        static PreviewResult outcome(PreviewOutcome outcome) { return new PreviewResult(outcome, null, null); }
        static PreviewResult notFound() { return outcome(PreviewOutcome.NOT_FOUND); }
        static PreviewResult notReady() { return outcome(PreviewOutcome.NOT_READY); }
        static PreviewResult expired() { return outcome(PreviewOutcome.EXPIRED); }
        static PreviewResult invalidCursor() { return outcome(PreviewOutcome.INVALID_CURSOR); }
        static PreviewResult invalidRequest() { return outcome(PreviewOutcome.INVALID_REQUEST); }
        static PreviewResult unknownField() { return outcome(PreviewOutcome.UNKNOWN_FIELD); }
        static PreviewResult preconditionRequired() { return outcome(PreviewOutcome.PRECONDITION_REQUIRED); }
        static PreviewResult stale() { return outcome(PreviewOutcome.STALE_ETAG); }
    }

    private record PatchApplication(PreviewOutcome outcome, EpubPackageParser.Preview preview, boolean changed) {
        static PatchApplication ready(EpubPackageParser.Preview preview, boolean changed) {
            return new PatchApplication(PreviewOutcome.READY, preview, changed);
        }
        static PatchApplication invalidRequest() {
            return new PatchApplication(PreviewOutcome.INVALID_REQUEST, null, false);
        }
        static PatchApplication invalidMapping() {
            return new PatchApplication(PreviewOutcome.INVALID_REQUEST, null, false);
        }
    }
}
