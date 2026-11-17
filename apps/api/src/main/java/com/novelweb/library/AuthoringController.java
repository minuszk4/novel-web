package com.novelweb.library;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Owner authoring supports private manuscripts and private drafts on published works. */
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

  @GetMapping
  public ResponseEntity<?> listManuscripts(
      Authentication authentication,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "25") String limit,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to view your manuscripts.", request);
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
    AuthoringCursorCodec.WorkListPosition position =
        cursor == null ? null : cursorCodec.decodeWorkList(cursor, actorUserId);
    LibraryRepository.AuthoredWorkPage page =
        libraryService.listPrivateAuthoredWorks(
            actorUserId,
            position == null ? null : position.createdAt(),
            position == null ? null : position.workId(),
            pageSize);
    String nextCursor =
        page.hasMore()
            ? cursorCodec.encodeWorkList(actorUserId, page.lastCreatedAt(), page.lastWorkId())
            : null;
    List<ManuscriptListItem> items =
        page.items().stream()
            .map(
                work ->
                    new ManuscriptListItem(
                        work.id(),
                        work.title(),
                        work.author(),
                        work.language(),
                        work.visibility(),
                        "draft",
                        work.chapterCount(),
                        work.createdAt()))
            .toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(new ManuscriptListResponse(items, nextCursor));
  }

  @GetMapping("/{workId}")
  public ResponseEntity<?> getManuscript(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to view this manuscript.", request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;

    LibraryRepository.PrivateAuthoredWorkDetails details =
        libraryService.getPrivateAuthoredWorkDetails(actor.orElseThrow(), workId);
    return manuscriptDetailsResponse(details);
  }

  @GetMapping("/{workId}/public-drafting-context")
  public ResponseEntity<?> getPublicDraftingContext(
      Authentication authentication, @PathVariable UUID workId, HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to view this manuscript.", request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;

    LibraryRepository.PrivateAuthoredWorkDetails details =
        libraryService.getPublicAuthoredWorkDraftingContext(actor.orElseThrow(), workId);
    return publicDraftingContextResponse(details);
  }

  @PutMapping("/{workId}")
  public ResponseEntity<?> updateManuscript(
      Authentication authentication,
      @PathVariable UUID workId,
      @RequestBody UpdateManuscriptRequest body,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to edit this manuscript.", request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null || body.hasUnknownFields())
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_MANUSCRIPT",
          "Request body is missing or contains unsupported fields.",
          request);
    if (ifMatch == null)
      return problem(
          HttpStatus.PRECONDITION_REQUIRED,
          "MANUSCRIPT_METADATA_PRECONDITION_REQUIRED",
          "Read the current manuscript metadata and provide its ETag in If-Match before saving.",
          request);
    Long expectedVersion = parseManuscriptMetadataEtag(ifMatch);
    if (expectedVersion == null)
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_MANUSCRIPT_METADATA_ETAG",
          "If-Match must be the strong manuscript metadata ETag returned by the detail route.",
          request);

    LibraryRepository.PrivateAuthoredWorkDetails details =
        libraryService.updatePrivateAuthoredWorkDetails(
            actor.orElseThrow(),
            workId,
            expectedVersion,
            body.title(),
            body.author(),
            body.language());
    return manuscriptDetailsResponse(details);
  }

  private static ResponseEntity<PrivateManuscriptDetailsResponse> manuscriptDetailsResponse(
      LibraryRepository.PrivateAuthoredWorkDetails details) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.ETAG, manuscriptMetadataEtag(details.metadataVersion()))
        .body(
            new PrivateManuscriptDetailsResponse(
                details.id(),
                details.title(),
                details.author(),
                details.language(),
                "private",
                "draft",
                details.chapterCount(),
                details.createdAt(),
                details.metadataVersion()));
  }

  private static ResponseEntity<PublicAuthoredWorkDraftContextResponse>
      publicDraftingContextResponse(LibraryRepository.PrivateAuthoredWorkDetails details) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.ETAG, manuscriptMetadataEtag(details.metadataVersion()))
        .body(
            new PublicAuthoredWorkDraftContextResponse(
                details.id(),
                details.title(),
                details.author(),
                details.language(),
                "public",
                "published",
                details.chapterCount(),
                details.createdAt(),
                details.metadataVersion()));
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
                        chapter.contentAvailable(),
                        chapter.hasSavedDraftContent(),
                        chapter.createdAt()))
            .toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(new ChapterOutlineResponse(items, nextCursor));
  }

  @DeleteMapping("/{workId}/chapters/{chapterId}")
  public ResponseEntity<?> deleteEmptyDraftChapter(
      Authentication authentication,
      @PathVariable UUID workId,
      @PathVariable UUID chapterId,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to remove this chapter.", request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;

    libraryService.deletePrivateEmptyAuthoredChapter(actor.orElseThrow(), workId, chapterId);
    return ResponseEntity.noContent()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .build();
  }

  @GetMapping("/{workId}/chapters/{chapterId}/draft")
  public ResponseEntity<?> getDraftContent(
      Authentication authentication,
      @PathVariable UUID workId,
      @PathVariable UUID chapterId,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to open this chapter draft.",
          request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;

    LibraryRepository.PrivateAuthoredDraftRecord draft =
        libraryService.getPrivateAuthoredDraft(actor.orElseThrow(), workId, chapterId);
    List<DraftSegment> segments =
        draft.segments().stream()
            .map(
                segment ->
                    new DraftSegment(
                        segment.ordinal(),
                        segment.type(),
                        segment.sourceText(),
                        segment.stableAnchor()))
            .toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.ETAG, draftEtag(draft.revisionNo()))
        .body(
            new DraftContentResponse(
                draft.chapterId(),
                draft.workId(),
                draft.title(),
                "draft",
                draft.revisionNo(),
                draft.sourceFormat(),
                draft.normalizedContent(),
                segments,
                draft.updatedAt()));
  }

  @PostMapping("/{workId}/chapters/{chapterId}/revisions")
  public ResponseEntity<?> saveDraftRevision(
      Authentication authentication,
      @PathVariable UUID workId,
      @PathVariable UUID chapterId,
      @RequestBody SaveDraftRevisionRequest body,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    Optional<UUID> actor = actorUserId(authentication);
    if (actor.isEmpty())
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to save this chapter draft.",
          request);
    ResponseEntity<?> policyGate = policyGate(actor.orElseThrow(), request);
    if (policyGate != null) return policyGate;
    if (body == null || body.hasUnknownFields() || body.segments() == null)
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_DRAFT_REVISION",
          "Request body must include a segment list and contain only supported fields.",
          request);
    if (ifMatch == null)
      return problem(
          HttpStatus.PRECONDITION_REQUIRED,
          "DRAFT_REVISION_PRECONDITION_REQUIRED",
          "Read the current draft and provide its ETag in If-Match before saving.",
          request);
    Integer expectedRevisionNo = parseDraftEtag(ifMatch);
    if (expectedRevisionNo == null)
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_DRAFT_REVISION_ETAG",
          "If-Match must be the strong draft ETag returned by the draft read endpoint.",
          request);
    if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._~-]{16,128}"))
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_IDEMPOTENCY_KEY",
          "Provide a 16 to 128 character Idempotency-Key for this save attempt.",
          request);

    List<LibraryService.NewSegment> segments = new ArrayList<>();
    if (body.segments() != null) {
      for (SaveDraftSegment segment : body.segments()) {
        if (segment == null || segment.hasUnknownFields()) {
          return problem(
              HttpStatus.BAD_REQUEST,
              "INVALID_DRAFT_REVISION",
              "Each segment must contain only the supported segment fields.",
              request);
        }
        segments.add(
            new LibraryService.NewSegment(
                segment.ordinal(), segment.type(), segment.sourceText(), segment.stableAnchor()));
      }
    }
    LibraryRepository.DraftRevisionWrite saved =
        libraryService.savePrivateAuthoredDraftRevision(
            actor.orElseThrow(),
            workId,
            chapterId,
            expectedRevisionNo,
            idempotencyKey,
            body.sourceFormat(),
            body.normalizedContent(),
            segments);
    LibraryRepository.RevisionRecord revision = saved.revision();
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.ETAG, draftEtag(revision.revisionNo()))
        .body(
            new CreatedDraftRevisionResponse(
                revision.id(),
                revision.chapterId(),
                revision.revisionNo(),
                revision.sourceFormat(),
                revision.createdAt()));
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

  @ExceptionHandler(LibraryRepository.ChapterNotFoundException.class)
  public ResponseEntity<ProblemDetail> chapterNotFound(HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "DRAFT_CHAPTER_NOT_FOUND",
        "The private authored draft chapter is not available.",
        request);
  }

  @ExceptionHandler(LibraryRepository.ChapterHasSavedContentException.class)
  public ResponseEntity<ProblemDetail> chapterHasSavedContent(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "DRAFT_CHAPTER_HAS_CONTENT",
        "This chapter has a saved draft and cannot be removed from the outline.",
        request);
  }

  @ExceptionHandler(LibraryRepository.DraftRevisionConflictException.class)
  public ResponseEntity<ProblemDetail> staleDraftRevision(
      LibraryRepository.DraftRevisionConflictException exception, HttpServletRequest request) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.PRECONDITION_FAILED,
            "The draft changed after it was read. Reload it before saving again.");
    body.setTitle(HttpStatus.PRECONDITION_FAILED.getReasonPhrase());
    body.setProperty("code", "DRAFT_REVISION_STALE");
    body.setProperty("currentEtag", draftEtag(exception.currentRevisionNo()));
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    body.setProperty("correlationId", value instanceof String id ? id : "unavailable");
    return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  @ExceptionHandler(LibraryRepository.ManuscriptMetadataConflictException.class)
  public ResponseEntity<ProblemDetail> staleManuscriptMetadata(
      LibraryRepository.ManuscriptMetadataConflictException exception, HttpServletRequest request) {
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.PRECONDITION_FAILED,
            "The manuscript metadata changed after it was read. Review the latest version before saving.");
    body.setTitle(HttpStatus.PRECONDITION_FAILED.getReasonPhrase());
    body.setProperty("code", "MANUSCRIPT_METADATA_STALE");
    body.setProperty("currentEtag", manuscriptMetadataEtag(exception.currentVersion()));
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    body.setProperty("correlationId", value instanceof String id ? id : "unavailable");
    return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.ETAG, manuscriptMetadataEtag(exception.currentVersion()))
        .body(body);
  }

  @ExceptionHandler(LibraryRepository.DuplicateRevisionException.class)
  public ResponseEntity<ProblemDetail> duplicateDraftRevision(HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "DUPLICATE_DRAFT_REVISION",
        "An identical normalized chapter revision already exists.",
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

  private static String draftEtag(int revisionNo) {
    return "\"draft-" + revisionNo + "\"";
  }

  private static String manuscriptMetadataEtag(long version) {
    return "\"manuscript-metadata-" + version + "\"";
  }

  private static Long parseManuscriptMetadataEtag(String value) {
    if (value == null || !value.matches("\\\"manuscript-metadata-[1-9][0-9]*\\\"")) return null;
    try {
      return Long.parseLong(value.substring(21, value.length() - 1));
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static Integer parseDraftEtag(String value) {
    if (value == null || !value.matches("\\\"draft-(0|[1-9][0-9]*)\\\"")) return null;
    try {
      return Integer.parseInt(value.substring(7, value.length() - 1));
    } catch (NumberFormatException exception) {
      return null;
    }
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

  public static final class UpdateManuscriptRequest {
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

  public static final class SaveDraftRevisionRequest {
    private String sourceFormat;
    private String normalizedContent;
    private List<SaveDraftSegment> segments;
    private boolean unknownFields;

    public String sourceFormat() {
      return sourceFormat;
    }

    public String normalizedContent() {
      return normalizedContent;
    }

    public List<SaveDraftSegment> segments() {
      return segments;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setSourceFormat(String sourceFormat) {
      this.sourceFormat = sourceFormat;
    }

    public void setNormalizedContent(String normalizedContent) {
      this.normalizedContent = normalizedContent;
    }

    public void setSegments(List<SaveDraftSegment> segments) {
      this.segments = segments;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public static final class SaveDraftSegment {
    private int ordinal;
    private String type;
    private String sourceText;
    private String stableAnchor;
    private boolean unknownFields;

    public int ordinal() {
      return ordinal;
    }

    public String type() {
      return type;
    }

    public String sourceText() {
      return sourceText;
    }

    public String stableAnchor() {
      return stableAnchor;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    public void setOrdinal(int ordinal) {
      this.ordinal = ordinal;
    }

    public void setType(String type) {
      this.type = type;
    }

    public void setSourceText(String sourceText) {
      this.sourceText = sourceText;
    }

    public void setStableAnchor(String stableAnchor) {
      this.stableAnchor = stableAnchor;
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

  public record ManuscriptListItem(
      UUID id,
      String title,
      String author,
      String language,
      String visibility,
      String publicationState,
      int chapterCount,
      Instant createdAt) {}

  public record ManuscriptListResponse(List<ManuscriptListItem> items, String nextCursor) {
    public ManuscriptListResponse {
      items = List.copyOf(items);
    }
  }

  public record PrivateManuscriptDetailsResponse(
      UUID id,
      String title,
      String author,
      String language,
      String visibility,
      String publicationState,
      int chapterCount,
      Instant createdAt,
      long metadataVersion) {}

  public record PublicAuthoredWorkDraftContextResponse(
      UUID id,
      String title,
      String author,
      String language,
      String visibility,
      String publicationState,
      int chapterCount,
      Instant createdAt,
      long metadataVersion) {}

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
      boolean hasSavedDraftContent,
      Instant createdAt) {}

  public record ChapterOutlineResponse(List<ChapterOutlineItem> items, String nextCursor) {
    public ChapterOutlineResponse {
      items = List.copyOf(items);
    }
  }

  public record DraftSegment(int ordinal, String type, String sourceText, String stableAnchor) {}

  public record DraftContentResponse(
      UUID id,
      UUID workId,
      String title,
      String status,
      int revisionNo,
      String sourceFormat,
      String normalizedContent,
      List<DraftSegment> segments,
      Instant updatedAt) {
    public DraftContentResponse {
      segments = List.copyOf(segments);
    }
  }

  public record CreatedDraftRevisionResponse(
      UUID id, UUID chapterId, int revisionNo, String sourceFormat, Instant createdAt) {}
}
