package com.novelweb.library;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

/** Read-only HTTP access to the caller's personal and active-workspace library scope. */
@RestController
@RequestMapping("/api/v1")
@Profile("postgres")
public class LibraryQueryController {

    private final LibraryService libraryService;
    private final PolicyService policyService;

    public LibraryQueryController(LibraryService libraryService, PolicyService policyService) {
        this.libraryService = libraryService;
        this.policyService = policyService;
    }

    @GetMapping("/libraries")
    public ResponseEntity<?> listLibraries(
            Authentication authentication,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") String limit,
            HttpServletRequest request) {
        Optional<UUID> actor = actorUserId(authentication);
        if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to view your libraries.", request);
        ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
        if (policyGate != null) return policyGate;

        LibraryService.Page<LibraryRepository.LibraryRecord> page = libraryService.listLibraries(
                actor.orElseThrow(), cursor, parseLimit(limit));
        List<LibrarySummary> items = page.items().stream().map(LibraryQueryController::summary).toList();
        return ok(new LibraryListResponse(items, page.nextCursor()));
    }

    @GetMapping("/works")
    public ResponseEntity<?> listWorks(
            Authentication authentication,
            @RequestParam String libraryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        Optional<UUID> actor = actorUserId(authentication);
        if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to view works in your library.", request);
        ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
        if (policyGate != null) return policyGate;

        UUID parsedLibraryId = parseUuid(libraryId, "libraryId");
        LibraryService.Page<LibraryRepository.WorkRecord> page = libraryService.listWorks(
                actor.orElseThrow(), parsedLibraryId, q, tag, status, cursor, parseLimit(limit));
        List<WorkSummary> items = page.items().stream().map(LibraryQueryController::summary).toList();
        return ok(new WorkListResponse(items, page.nextCursor()));
    }

    @ExceptionHandler(LibraryCursorCodec.InvalidCursorException.class)
    public ResponseEntity<ProblemDetail> invalidCursor(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_CURSOR",
                "The cursor is malformed, expired by a scope change, or belongs to another query.", request);
    }

    @ExceptionHandler(LibraryRepository.LibraryNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "The requested library is unavailable.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(
            IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_LIBRARY_QUERY", exception.getMessage(), request);
    }

    @ExceptionHandler(LibraryCursorCodec.SigningUnavailableException.class)
    public ResponseEntity<ProblemDetail> signingUnavailable(HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "LIBRARY_CURSOR_UNAVAILABLE",
                "Library pagination is temporarily unavailable.", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "LIBRARY_QUERY_UNAVAILABLE",
                "Library data could not be loaded. Please try again.", request);
    }

    private ResponseEntity<?> policyGate(UUID actorUserId, HttpServletRequest request) {
        try {
            return switch (policyService.requiredAcceptance(actorUserId)) {
                case COMPLETE -> null;
                case REQUIRED -> problem(HttpStatus.FORBIDDEN, "POLICY_ACCEPTANCE_REQUIRED",
                        "Review and accept the current Terms and Privacy Notice before using your library.", request);
                case NOT_CONFIGURED -> problem(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_NOT_CONFIGURED",
                        "Current approved policy documents are not available.", request);
            };
        } catch (IllegalStateException exception) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "POLICY_STORE_UNAVAILABLE",
                    "Current policy state could not be checked. Please try again.", request);
        }
    }

    private static int parseLimit(String rawLimit) {
        try {
            return Integer.parseInt(rawLimit);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be an integer between 1 and 100");
        }
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a valid UUID");
        }
    }

    private static Optional<UUID> actorUserId(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal.userId());
    }

    private static LibrarySummary summary(LibraryRepository.LibraryRecord library) {
        return new LibrarySummary(library.id(), library.name(), library.visibility(),
                library.isDefault(), library.createdAt());
    }

    private static WorkSummary summary(LibraryRepository.WorkRecord work) {
        return new WorkSummary(work.id(), work.libraryId(), work.title(), work.author(), work.language(),
                work.visibility(), work.chapterCount(), work.createdAt());
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        problem.setProperty("correlationId", value instanceof String id ? id : "unavailable");
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(problem);
    }

    public record LibrarySummary(UUID id, String name, String visibility, boolean isDefault, Instant createdAt) {
    }

    public record LibraryListResponse(List<LibrarySummary> items, String nextCursor) {
    }

    public record WorkSummary(
            UUID id, UUID libraryId, String title, String author, String language, String visibility,
            int chapterCount, Instant createdAt) {
    }

    public record WorkListResponse(List<WorkSummary> items, String nextCursor) {
    }
}
