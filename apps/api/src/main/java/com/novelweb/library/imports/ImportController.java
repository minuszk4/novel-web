package com.novelweb.library.imports;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.novelweb.identity.session.SessionService;
import com.novelweb.library.imports.ImportCreationService.CreateCommand;
import com.novelweb.library.imports.ImportCreationService.CreateResult;
import com.novelweb.library.imports.ImportCreationService.Job;
import com.novelweb.library.imports.ImportCreationService.SourceDescriptor;
import com.novelweb.library.imports.ImportCreationService.UploadResult;
import com.novelweb.platform.web.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/imports")
@Profile("postgres")
public class ImportController {

    private static final String UPLOAD_TOKEN_HEADER = "X-Import-Upload-Token";

    private final ImportCreationService importCreationService;

    public ImportController(ImportCreationService importCreationService) {
        this.importCreationService = importCreationService;
    }

    @PostMapping
    public ResponseEntity<?> create(
            Authentication authentication,
            @RequestBody(required = false) CreateImportRequest body,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        SessionService.SessionPrincipal principal = principal(authentication);
        if (principal == null) {
            return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Sign in before starting an import.", correlationId);
        }
        if (body == null) {
            return problem(HttpStatus.UNPROCESSABLE_CONTENT, "IMPORT_REQUEST_REQUIRED",
                    "Provide the target library, file metadata and current rights receipt.", correlationId);
        }
        if (body.hasUnknownFields() || body.source() != null && body.source().hasUnknownFields()) {
            return problem(HttpStatus.BAD_REQUEST, "IMPORT_REQUEST_UNKNOWN_FIELD",
                    "The import request contains fields that are controlled by the server.", correlationId);
        }

        SourceDescriptor source = body.source() == null ? null : new SourceDescriptor(
                body.source().kind(), body.source().filename(), body.source().contentType(), body.source().sizeBytes());
        CreateCommand command = new CreateCommand(
                body.libraryId(), body.rightsAcceptanceId(), body.rightsStatementVersion(),
                body.rightsConfirmed(), source);
        try {
            CreateResult result = importCreationService.create(principal.userId(), command);
            return switch (result.outcome()) {
                case CREATED -> created(result);
                case RIGHTS_CONFIRMATION_REQUIRED -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                        "IMPORT_RIGHTS_CONFIRMATION_REQUIRED",
                        "Review the current statement and explicitly confirm permission for private import.",
                        correlationId);
                case FILE_METADATA_INVALID -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                        "IMPORT_FILE_METADATA_INVALID",
                        "Choose a supported file type and provide a size within the configured limit.",
                        correlationId);
                case POLICY_REQUIRED -> problem(HttpStatus.FORBIDDEN, "POLICY_ACCEPTANCE_REQUIRED",
                        "Accept the current Terms and acknowledge the current Privacy Notice first.",
                        correlationId);
                case POLICY_NOT_CONFIGURED -> problem(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_NOT_CONFIGURED",
                        "Current approved policy documents are not available.", correlationId);
                case RIGHTS_NOT_CONFIGURED -> problem(HttpStatus.SERVICE_UNAVAILABLE,
                        "IMPORT_RIGHTS_STATEMENT_NOT_CONFIGURED",
                        "An owner-approved import-rights statement is not available.", correlationId);
                case RIGHTS_STALE -> problem(HttpStatus.CONFLICT, "IMPORT_RIGHTS_STATEMENT_STALE",
                        "Review and confirm the current import-rights statement before continuing.", correlationId);
                case RECEIPT_ALREADY_USED -> problem(HttpStatus.CONFLICT, "IMPORT_RIGHTS_RECEIPT_ALREADY_USED",
                        "This rights receipt has already been used for a different or completed import.",
                        correlationId);
                case LIBRARY_NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "LIBRARY_NOT_FOUND",
                        "The selected library is unavailable for this account.", correlationId);
                case STORAGE_NOT_CONFIGURED -> problem(HttpStatus.SERVICE_UNAVAILABLE,
                        "IMPORT_STORAGE_NOT_CONFIGURED",
                        "Private import storage is unavailable; no upload target was created.", correlationId);
                default -> problem(HttpStatus.INTERNAL_SERVER_ERROR, "IMPORT_CREATE_FAILED",
                        "The import could not be created.", correlationId);
            };
        } catch (IllegalStateException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "IMPORT_CREATE_UNAVAILABLE",
                    "The import service could not create a private staging job.", correlationId);
        }
    }

    @PutMapping(value = "/{importId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> upload(
            Authentication authentication,
            @PathVariable UUID importId,
            @RequestHeader(value = UPLOAD_TOKEN_HEADER, required = false) String token,
            HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        SessionService.SessionPrincipal principal = principal(authentication);
        if (principal == null) {
            return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Sign in with the account that created this import.", correlationId);
        }

        try {
            UploadResult result = importCreationService.upload(
                    principal.userId(), importId, token, request.getInputStream());
            return switch (result.outcome()) {
                case UPLOADED -> noStore(HttpStatus.NO_CONTENT).build();
                case UPLOAD_TARGET_NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "IMPORT_UPLOAD_TARGET_NOT_FOUND",
                        "This upload target is unavailable.", correlationId);
                case UPLOAD_TARGET_EXPIRED -> problem(HttpStatus.GONE, "IMPORT_UPLOAD_TARGET_EXPIRED",
                        "This upload target has expired. Start a new confirmation attempt.", correlationId);
                case FILE_TOO_LARGE -> problem(HttpStatus.PAYLOAD_TOO_LARGE, "IMPORT_FILE_TOO_LARGE",
                        "The uploaded file exceeds the configured maximum size.", correlationId);
                case FILE_SIZE_MISMATCH -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                        "IMPORT_FILE_SIZE_MISMATCH",
                        "The uploaded file size does not match the size declared when the target was created.",
                        correlationId);
                case STORAGE_NOT_CONFIGURED -> problem(HttpStatus.SERVICE_UNAVAILABLE,
                        "IMPORT_STORAGE_NOT_CONFIGURED", "Private import storage is unavailable.", correlationId);
                default -> problem(HttpStatus.INTERNAL_SERVER_ERROR, "IMPORT_UPLOAD_FAILED",
                        "The upload could not be stored.", correlationId);
            };
        } catch (java.io.IOException | IllegalStateException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "IMPORT_UPLOAD_UNAVAILABLE",
                    "Private import storage is unavailable.", correlationId);
        }
    }

    private static ResponseEntity<CreatedResponse> created(CreateResult result) {
        Job job = result.job();
        CreatedResponse response = new CreatedResponse(
                new JobResponse(job.id(), job.status(), job.createdAt(), job.expiresAt()),
                new UploadTargetResponse("PUT", "/api/v1/imports/" + job.id() + "/content",
                        Map.of("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                UPLOAD_TOKEN_HEADER, result.uploadToken()), result.targetExpiresAt()));
        return noStore(HttpStatus.CREATED)
                .header(HttpHeaders.LOCATION, "/api/v1/imports/" + job.id())
                .body(response);
    }

    private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache");
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String detail, UUID correlationId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId.toString());
        return noStore(status).body(problem);
    }

    private static SessionService.SessionPrincipal principal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
            return null;
        }
        return principal;
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        if (value instanceof String id) {
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
                // A fallback is used when normal request filters are not present in a test.
            }
        }
        return UUID.randomUUID();
    }

    public static final class CreateImportRequest {
        private UUID libraryId;
        private ImportSource source;
        private UUID rightsAcceptanceId;
        private String rightsStatementVersion;
        private Boolean rightsConfirmed;
        private boolean unknownFields;

        public UUID libraryId() { return libraryId; }
        public ImportSource source() { return source; }
        public UUID rightsAcceptanceId() { return rightsAcceptanceId; }
        public String rightsStatementVersion() { return rightsStatementVersion; }
        public Boolean rightsConfirmed() { return rightsConfirmed; }
        boolean hasUnknownFields() { return unknownFields; }

        public void setLibraryId(UUID libraryId) { this.libraryId = libraryId; }
        public void setSource(ImportSource source) { this.source = source; }
        public void setRightsAcceptanceId(UUID rightsAcceptanceId) { this.rightsAcceptanceId = rightsAcceptanceId; }
        public void setRightsStatementVersion(String rightsStatementVersion) {
            this.rightsStatementVersion = rightsStatementVersion;
        }
        public void setRightsConfirmed(Boolean rightsConfirmed) { this.rightsConfirmed = rightsConfirmed; }

        @JsonAnySetter
        public void captureUnknownField(String name, Object ignoredValue) { unknownFields = true; }
    }

    public static final class ImportSource {
        private String kind;
        private String filename;
        private String contentType;
        private Long sizeBytes;
        private boolean unknownFields;

        public String kind() { return kind; }
        public String filename() { return filename; }
        public String contentType() { return contentType; }
        public Long sizeBytes() { return sizeBytes; }
        boolean hasUnknownFields() { return unknownFields; }

        public void setKind(String kind) { this.kind = kind; }
        public void setFilename(String filename) { this.filename = filename; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

        @JsonAnySetter
        public void captureUnknownField(String name, Object ignoredValue) { unknownFields = true; }
    }

    public record JobResponse(UUID id, String status, Instant createdAt, Instant expiresAt) {
    }

    public record UploadTargetResponse(String method, String url, Map<String, String> headers, Instant expiresAt) {
    }

    public record CreatedResponse(JobResponse job, UploadTargetResponse uploadTarget) {
    }
}
