package com.novelweb.library;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.novelweb.identity.session.SessionService;
import com.novelweb.identity.auth.PolicyService;
import com.novelweb.platform.web.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/me")
@Profile("postgres")
public class LibraryUserStateController {

    private final LibraryUserStateService service;
    private final PolicyService policyService;

    public LibraryUserStateController(LibraryUserStateService service, PolicyService policyService) {
        this.service = service;
        this.policyService = policyService;
    }

    @GetMapping("/library/collections")
    public ResponseEntity<?> listCollections(Authentication authentication, HttpServletRequest request) {
        Optional<UUID> actor = actorUserId(authentication);
        if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to view your collections.", request);
        ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
        if (policyGate != null) return policyGate;
        return ok(service.listCollections(actor.orElseThrow()));
    }

    @PostMapping("/library/collections")
    public ResponseEntity<?> createCollection(
            Authentication authentication, @RequestBody CreateCollectionRequest body,
            HttpServletRequest request) {
        Optional<UUID> actor = actorUserId(authentication);
        if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to create a collection.", request);
        ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
        if (policyGate != null) return policyGate;
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(service.createCollection(actor.orElseThrow(), body == null ? null : body.name()));
    }

    @DeleteMapping("/library/collections/{collectionId}")
    public ResponseEntity<?> deleteCollection(
            Authentication authentication, @PathVariable UUID collectionId, HttpServletRequest request) {
        Optional<UUID> actor = actorUserId(authentication);
        if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to delete a collection.", request);
        ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
        if (policyGate != null) return policyGate;
        service.deleteCollection(actor.orElseThrow(), collectionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").build();
    }

    @GetMapping("/works/{workId}/library-state")
    public ResponseEntity<?> getWorkState(
            Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
        Optional<UUID> actor = actorUserId(authentication);
        if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to view your reading state.", request);
        ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
        if (policyGate != null) return policyGate;
        return workStateResponse(service.getWorkState(actor.orElseThrow(), workId));
    }

    @PutMapping("/works/{workId}/library-state")
    public ResponseEntity<?> replaceWorkState(
            Authentication authentication, @PathVariable UUID workId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ReplaceWorkStateRequest body, HttpServletRequest request) {
        Optional<UUID> actor = actorUserId(authentication);
        if (actor.isEmpty()) return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Sign in to update your reading state.", request);
        ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
        if (policyGate != null) return policyGate;
        if (body == null) return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "Provide a readingStatus and collectionIds list.", request);
        if (ifMatch == null) return problem(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED",
                "Reload the current library state before replacing it.", request);
        long expectedVersion = parseEtagVersion(ifMatch);
        LibraryUserStateRepository.WorkStateRecord state = service.replaceWorkState(
                actor.orElseThrow(), workId, expectedVersion, body.readingStatus(), body.collectionIds());
        return workStateResponse(state);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(
            IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_LIBRARY_STATE", exception.getMessage(), request);
    }

    @ExceptionHandler(LibraryUserStateRepository.CollectionLimitReachedException.class)
    public ResponseEntity<ProblemDetail> collectionLimit(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "LIBRARY_COLLECTION_LIMIT",
                "You can have up to 12 active collections.", request);
    }

    @ExceptionHandler(LibraryUserStateRepository.CollectionNameConflictException.class)
    public ResponseEntity<ProblemDetail> collectionNameConflict(HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "LIBRARY_COLLECTION_NAME_CONFLICT",
                "A collection with that name already exists.", request);
    }

    @ExceptionHandler(LibraryUserStateRepository.LibraryStateVersionConflictException.class)
    public ResponseEntity<ProblemDetail> staleVersion(
            LibraryUserStateRepository.LibraryStateVersionConflictException exception,
            HttpServletRequest request) {
        LibraryUserStateRepository.WorkStateRecord latest = exception.latestState();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Library state changed elsewhere. Reload the latest state before saving.");
        problem.setTitle(HttpStatus.CONFLICT.getReasonPhrase());
        problem.setProperty("code", "LIBRARY_STATE_VERSION_STALE");
        problem.setProperty("correlationId", correlationId(request));
        problem.setProperty("latest", latest);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.ETAG, etag(latest.version()))
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(problem);
    }

    @ExceptionHandler(LibraryUserStateRepository.LibraryUserStateNotFoundException.class)
    public ResponseEntity<ProblemDetail> resourceNotFound(HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "The requested work or collection is unavailable.", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadableBody(HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                "The request body is missing or malformed.", request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "LIBRARY_STATE_UNAVAILABLE",
                "Library state could not be loaded. Please try again.", request);
    }

    private static Optional<UUID> actorUserId(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal.userId());
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

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(body);
    }

    private static ResponseEntity<LibraryUserStateRepository.WorkStateRecord> workStateResponse(
            LibraryUserStateRepository.WorkStateRecord state) {
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(state.version()))
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(state);
    }

    private static String etag(long version) {
        return "\"v" + version + "\"";
    }

    private static long parseEtagVersion(String ifMatch) {
        if (!ifMatch.matches("\"v(?:0|[1-9][0-9]{0,18})\"")) {
            throw new IllegalArgumentException("If-Match must contain the current library-state ETag");
        }
        try {
            return Long.parseLong(ifMatch.substring(2, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("If-Match must contain the current library-state ETag");
        }
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("correlationId", correlationId(request));
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache").body(problem);
    }

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : "unavailable";
    }

    public record CreateCollectionRequest(String name) {
    }

    public record ReplaceWorkStateRequest(String readingStatus, List<UUID> collectionIds) {
    }
}
