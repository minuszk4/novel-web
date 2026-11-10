package com.novelweb.library.imports;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/** Queues owner-scoped parse requests and persists completed private previews atomically. */
@Service
@Profile("postgres")
public class ImportParseService {

    private static final String PARSER_VERSION = "epub3-v1";
    private static final int MAX_PREVIEW_BYTES = 1_048_576;
    private static final int MAX_WARNINGS = 100;

    private final ImportParseJobRepository repository;
    private final ImportStagingFileStore fileStore;
    private final EpubPackageParser epubPackageParser;
    private final ObjectMapper objectMapper;

    public ImportParseService(
            ImportParseJobRepository repository,
            ImportStagingFileStore fileStore,
            EpubPackageParser epubPackageParser,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.fileStore = fileStore;
        this.epubPackageParser = epubPackageParser;
        this.objectMapper = objectMapper;
    }

    public ParseRequestResult requestParse(UUID userId, UUID importId) {
        Optional<ImportParseJobRepository.UploadSource> source = repository.findUploadSource(userId, importId);
        if (source.isEmpty()) return ParseRequestResult.notFound();
        ImportParseJobRepository.UploadSource upload = source.orElseThrow();
        if (!upload.live()) return ParseRequestResult.expired();
        if (!"uploaded".equals(upload.status())) return ParseRequestResult.notUploaded(upload.status());

        ImportParseJobRepository.StatusResult existing = repository.findStatus(userId, importId);
        if (existing.found()) {
            if (!existing.status().live() || "expired".equals(existing.status().status())) {
                return ParseRequestResult.expired();
            }
            if ("failed".equals(existing.status().status())) return ParseRequestResult.failed(existing.status());
            if (List.of("queued", "parsing", "ready_to_commit").contains(existing.status().status())) {
                return ParseRequestResult.accepted(existing.status());
            }
        }

        try {
            if (!fileStore.matchesUploadedDigest(importId, upload.sizeBytes(), upload.sha256())) {
                return ParseRequestResult.sourceInvalid();
            }
        } catch (IOException exception) {
            return ParseRequestResult.storageUnavailable();
        }

        ImportParseJobRepository.QueueResult queued = repository.enqueue(userId, importId, PARSER_VERSION);
        return switch (queued.outcome()) {
            case NOT_FOUND -> ParseRequestResult.notFound();
            case EXPIRED -> ParseRequestResult.expired();
            case NOT_UPLOADED -> ParseRequestResult.notUploaded(queued.uploadStatus());
            case ACCEPTED -> queued.status().status().equals("failed")
                    ? ParseRequestResult.failed(queued.status())
                    : ParseRequestResult.accepted(queued.status());
        };
    }

    public ImportParseJobRepository.StatusResult status(UUID userId, UUID importId) {
        return repository.findStatus(userId, importId);
    }

    public List<ImportWarning> statusWarnings(ImportParseJobRepository.ImportStatus status) {
        if (status.warningsJson() == null || status.warningsJson().isBlank()) return List.of();
        try {
            return List.of(objectMapper.readValue(status.warningsJson(), ImportWarning[].class));
        } catch (RuntimeException exception) {
            return List.of(new ImportWarning(
                    "IMPORT_STATUS_UNAVAILABLE", "Parser status is temporarily unavailable."));
        }
    }

    /** Claims and processes one job outside the claim/completion database transactions. */
    public boolean processNext() {
        Optional<ImportParseJobRepository.ParseClaim> maybeClaim = repository.claimNext();
        if (maybeClaim.isEmpty()) return false;
        ImportParseJobRepository.ParseClaim claim = maybeClaim.orElseThrow();
        try {
            if (!claim.filename().toLowerCase(java.util.Locale.ROOT).endsWith(".epub")) {
                throw new EpubPackageParser.EpubParseException(
                        "IMPORT_FORMAT_UNSUPPORTED", "Only EPUB 3 parsing is enabled in this worker.");
            }
            if (!fileStore.matchesUploadedDigest(claim.importId(), claim.sizeBytes(), claim.sha256())) {
                throw new EpubPackageParser.EpubParseException(
                        "IMPORT_SOURCE_CHECKSUM_MISMATCH", "Staged upload no longer matches its accepted digest.");
            }
            EpubPackageParser.Preview parsed = epubPackageParser.parse(
                    fileStore.targetPath(claim.importId()), claim.filename(), claim.contentType());
            EpubPackageParser.Preview bounded = withBoundedWarnings(parsed);
            byte[] previewBytes = objectMapper.writeValueAsBytes(bounded);
            if (previewBytes.length > MAX_PREVIEW_BYTES) {
                throw new EpubPackageParser.EpubParseException(
                        "IMPORT_PREVIEW_TOO_LARGE", "The parsed preview exceeds its storage limit.");
            }
            byte[] etag = sha256(previewBytes);
            List<ImportWarning> warnings = toApiWarnings(bounded.warnings());
            repository.completeSuccess(claim, new String(previewBytes, StandardCharsets.UTF_8), etag,
                    writeJson(warnings));
        } catch (EpubPackageParser.EpubParseException exception) {
            fail(claim, exception.code());
        } catch (ImportContentValidator.InvalidContentException exception) {
            fail(claim, "IMPORT_CONTENT_INVALID");
        } catch (IOException exception) {
            fail(claim, "IMPORT_SOURCE_UNAVAILABLE");
        } catch (RuntimeException exception) {
            fail(claim, "IMPORT_PARSE_FAILED");
        }
        return true;
    }

    private void fail(ImportParseJobRepository.ParseClaim claim, String failureCode) {
        List<ImportWarning> warnings = List.of(new ImportWarning(
                "IMPORT_PARSE_FAILED", "The private file could not be parsed. Check it or upload a supported file."));
        repository.completeFailure(claim, safeFailureCode(failureCode), writeJson(warnings));
    }

    private EpubPackageParser.Preview withBoundedWarnings(EpubPackageParser.Preview parsed) {
        Map<String, EpubPackageParser.Warning> distinct = new LinkedHashMap<>();
        for (EpubPackageParser.Warning warning : parsed.warnings()) {
            distinct.putIfAbsent(warning.code(), warning);
            if (distinct.size() >= MAX_WARNINGS) break;
        }
        List<EpubPackageParser.Warning> warnings = new ArrayList<>(distinct.values());
        if (parsed.warnings().size() > warnings.size() && warnings.size() == MAX_WARNINGS) {
            warnings.set(MAX_WARNINGS - 1, new EpubPackageParser.Warning(
                    "IMPORT_WARNINGS_TRUNCATED", "Additional parser warnings were omitted from this preview."));
        }
        return new EpubPackageParser.Preview(parsed.metadata(), parsed.chapters(), parsed.toc(), List.copyOf(warnings));
    }

    private static List<ImportWarning> toApiWarnings(List<EpubPackageParser.Warning> warnings) {
        return warnings.stream().map(warning -> new ImportWarning(warning.code(), warning.summary())).toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to encode safe parser warnings", exception);
        }
    }

    private static String safeFailureCode(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,96}")) return "IMPORT_PARSE_FAILED";
        return value;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ImportWarning(String code, String message) { }

    public record ParseRequestResult(ParseRequestOutcome outcome,
                                     ImportParseJobRepository.ImportStatus status, String uploadStatus) {
        static ParseRequestResult accepted(ImportParseJobRepository.ImportStatus status) {
            return new ParseRequestResult(ParseRequestOutcome.ACCEPTED, status, null);
        }
        static ParseRequestResult failed(ImportParseJobRepository.ImportStatus status) {
            return new ParseRequestResult(ParseRequestOutcome.FAILED, status, null);
        }
        static ParseRequestResult notFound() { return new ParseRequestResult(ParseRequestOutcome.NOT_FOUND, null, null); }
        static ParseRequestResult expired() { return new ParseRequestResult(ParseRequestOutcome.EXPIRED, null, null); }
        static ParseRequestResult notUploaded(String status) {
            return new ParseRequestResult(ParseRequestOutcome.NOT_UPLOADED, null, status);
        }
        static ParseRequestResult sourceInvalid() {
            return new ParseRequestResult(ParseRequestOutcome.SOURCE_INVALID, null, null);
        }
        static ParseRequestResult storageUnavailable() {
            return new ParseRequestResult(ParseRequestOutcome.STORAGE_UNAVAILABLE, null, null);
        }
    }

    public enum ParseRequestOutcome {
        ACCEPTED, FAILED, NOT_FOUND, EXPIRED, NOT_UPLOADED, SOURCE_INVALID, STORAGE_UNAVAILABLE
    }
}
