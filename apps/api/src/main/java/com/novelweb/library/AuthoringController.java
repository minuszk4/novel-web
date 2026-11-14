package com.novelweb.library;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Private-first creation of author-owned work. Publishing is a separate, unimplemented workflow.
 */
@RestController
@RequestMapping("/api/v1/authoring/manuscripts")
@Profile("postgres")
public class AuthoringController {

  private final LibraryService libraryService;
  private final PolicyService policyService;
  private final AuthoringCursorCodec cursorCodec;

  public AuthoringController(
      LibraryService libraryService,
      PolicyService policyService,
      AuthoringCursorCodec cursorCodec) {
    this.libraryService = libraryService;
    this.policyService = policyService;
    this.cursorCodec = cursorCodec;
  }

  @PostMapping
  public ResponseEntity<?> createManuscript(
      Authentication authentication,
      @RequestBody CreateManuscriptRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to create a manuscript.", request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null || body.hasUnknownFields())
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_MANUSCRIPT",
          "Request body is missing or contains unsupported fields.",
          request);
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{16,128}"))
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_IDEMPOTENCY_KEY",
          "Provide a 16 to 128 character Idempotency-Key for this create attempt.",
          request);

    LibraryRepository.WorkRecord work =
        libraryService.createPrivateAuthoredWork(
            actor.orElseThrow(), body.title(), body.author(), body.language(), idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(
            new CreatedManuscriptResponse(
                work.id(),
                work.title(),
                work.author(),
                work.language(),
                "draft",
                work.visibility(),
                work.createdAt()));
  }

  @PostMapping("/{workId}/chapters")
  public ResponseEntity<?> createChapter(
      Authentication authentication,
      @PathVariable UUID workId,
      @RequestBody CreateChapterRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to create a chapter.", request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null || body.hasUnknownFields())
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_CHAPTER",
          "Request body is missing or contains unsupported fields.",
          request);
    if (body.title() == null
        || body.title().trim().isEmpty()
        || body.title().trim().codePointCount(0, body.title().trim().length()) > 300) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_CHAPTER",
          "Chapter title must contain between 1 and 300 characters.",
          request);
    }
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{16,128}"))
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_IDEMPOTENCY_KEY",
          "Provide a 16 to 128 character Idempotency-Key for this create attempt.",
          request);

    LibraryRepository.ChapterRecord chapter =
        libraryService.createPrivateAuthoredChapter(
            actor.orElseThrow(), workId, body.title(), idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(
            new CreatedDraftChapterResponse(
                chapter.id(),
                chapter.workId(),
                chapter.ordinal(),
                chapter.title(),
                chapter.language(),
                chapter.status(),
                chapter.createdAt()));
  }

  @GetMapping("/{workId}/chapters")
  public ResponseEntity<?> listChapters(
      Authentication authentication,
      @PathVariable UUID workId,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") String limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view the chapter outline.",
          request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;

    int pageSize;
    try {
      pageSize = Integer.parseInt(limit);
    } catch (NumberFormatException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_AUTHORING_QUERY",
          "limit must be an integer between 1 and 100.",
          request);
    }
    if (pageSize < 1 || pageSize > 100)
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_AUTHORING_QUERY",
          "limit must be between 1 and 100.",
          request);

    UUID actorUserId = actor.orElseThrow();
    cursorCodec.requireConfigured();
    int afterOrdinal = cursor == null ? 0 : cursorCodec.decode(cursor, actorUserId, workId);
    LibraryRepository.ChapterOutlinePage page =
        libraryService.listPrivateAuthoredChapters(actorUserId, workId, afterOrdinal, pageSize);
    String nextCursor =
        page.hasMore() ? cursorCodec.encode(actorUserId, workId, page.lastOrdinal()) : null;
    List<ChapterOutlineItem> items =
        page.items().stream()
            .map(
                chapter ->
                    new ChapterOutlineItem(
                        chapter.id(),
                        chapter.ordinal(),
                        chapter.title(),
                        chapter.language(),
                        chapter.status(),
                        "ready".equals(chapter.status()) && chapter.sourceRevisionId() != null,
                        chapter.createdAt()))
            .toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(new ChapterOutlineResponse(items, nextCursor));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ProblemDetail> invalidRequest(
      IllegalArgumentException exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_MANUSCRIPT", exception.getMessage(), request);
  }

  @ExceptionHandler(LibraryRepository.IdempotencyKeyReusedException.class)
  public ResponseEntity<ProblemDetail> idempotencyKeyReused(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "This Idempotency-Key belongs to a different authoring request. Use a new key.",
        request);
  }

  @ExceptionHandler(LibraryRepository.WorkNotFoundException.class)
  public ResponseEntity<ProblemDetail> manuscriptNotFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "MANUSCRIPT_NOT_FOUND",
        "The private authored manuscript is not available.",
        request);
  }

  @ExceptionHandler(AuthoringCursorCodec.InvalidCursorException.class)
  public ResponseEntity<ProblemDetail> invalidAuthoringCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_AUTHORING_CURSOR",
        "The cursor is malformed or belongs to another account, manuscript or endpoint.",
        request);
  }

  @ExceptionHandler(AuthoringCursorCodec.SigningUnavailableException.class)
  public ResponseEntity<ProblemDetail> authoringCursorUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTHORING_CURSOR_UNAVAILABLE",
        "Chapter outline pagination is temporarily unavailable.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> unavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTHORING_UNAVAILABLE",
        "The manuscript could not be created. Please try again.",
        request);
  }

  private ResponseEntity<?> policyGate(UUID actorUserId, HttpServletRequest request) {
    try {
      return switch (policyService.requiredAcceptance(actorUserId)) {
        case COMPLETE -> null;
        case REQUIRED ->
            problem(
                HttpStatus.FORBIDDEN,
                "POLICY_ACCEPTANCE_REQUIRED",
                "Review and accept the current Terms and Privacy Notice before using Author Studio.",
                request);
        case NOT_CONFIGURED ->
            problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "POLICY_NOT_CONFIGURED",
                "Current approved policy documents are not available.",
                request);
      };
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "POLICY_STORE_UNAVAILABLE",
          "Current policy state could not be checked. Please try again.",
          request);
    }
  }

  private static Optional<UUID> actorUserId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return Optional.empty();
    }
    return Optional.of(principal.userId());
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty("correlationId", value instanceof String id ? id : "unavailable");
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }

  public static final class CreateManuscriptRequest {
    private String title;
    private String author;
    private String language;
    private boolean unknownFields;

    public String title() {
      return title;
    }

    public String author() {
      return author;
    }

    public String language() {
      return language;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public void setAuthor(String author) {
      this.author = author;
    }

    public void setLanguage(String language) {
      this.language = language;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public static final class CreateChapterRequest {
    private String title;
    private boolean unknownFields;

    public String title() {
      return title;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public record CreatedManuscriptResponse(
      UUID id,
      String title,
      String author,
      String language,
      String publicationState,
      String visibility,
      Instant createdAt) {}

  public record CreatedDraftChapterResponse(
      UUID id,
      UUID workId,
      int ordinal,
      String title,
      String language,
      String status,
      Instant createdAt) {}

  public record ChapterOutlineItem(
      UUID id,
      int ordinal,
      String title,
      String language,
      String status,
      boolean contentAvailable,
      Instant createdAt) {}

  public record ChapterOutlineResponse(List<ChapterOutlineItem> items, String nextCursor) {
    public ChapterOutlineResponse {
      items = List.copyOf(items);
    }
  }
}
