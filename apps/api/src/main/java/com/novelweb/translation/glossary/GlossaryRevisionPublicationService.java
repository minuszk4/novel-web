package com.novelweb.translation.glossary;

import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Applies pre-publication validation before asking the repository to promote a snapshot. */
@Service
@Profile("postgres")
public class GlossaryRevisionPublicationService {

  private static final int SHORT_TERM_WARNING_CODE_POINTS = 3;

  private final GlossaryRevisionPublicationRepository repository;
  private final GlossaryRevisionPreviewService previewService;

  public GlossaryRevisionPublicationService(
      GlossaryRevisionPublicationRepository repository,
      GlossaryRevisionPreviewService previewService) {
    this.repository = Objects.requireNonNull(repository, "publication repository is required");
    this.previewService = Objects.requireNonNull(previewService, "preview service is required");
  }

  public GlossaryRevisionPublicationRepository.Published publish(
      UUID actorUserId, UUID glossaryId, UUID revisionId, long expectedVersion) {
    GlossaryRevisionPublicationRepository.Candidate candidate =
        repository.loadCandidate(actorUserId, glossaryId, revisionId);
    if (revisionId.equals(candidate.activeRevisionId())) {
      return repository.publishRevision(actorUserId, glossaryId, revisionId, expectedVersion);
    }
    GlossaryRevisionPreviewService.Preview preview =
        buildPreview(candidate, revisionId, expectedVersion, "");
    if (!preview.canPublish()) throw new NotPublishableException(preview);
    return repository.publishRevision(actorUserId, glossaryId, revisionId, expectedVersion);
  }

  /** Returns the current latest draft's diff, lint and optional sample matches without writing. */
  public GlossaryRevisionPreviewService.Preview preview(
      UUID actorUserId,
      UUID glossaryId,
      UUID revisionId,
      long expectedVersion,
      String sampleSource) {
    GlossaryRevisionPublicationRepository.Candidate candidate =
        repository.loadCandidate(actorUserId, glossaryId, revisionId);
    if (revisionId.equals(candidate.activeRevisionId())) {
      throw new RevisionAlreadyActiveException(candidate.glossaryVersion());
    }
    return buildPreview(candidate, revisionId, expectedVersion, sampleSource);
  }

  private GlossaryRevisionPreviewService.Preview buildPreview(
      GlossaryRevisionPublicationRepository.Candidate candidate,
      UUID revisionId,
      long expectedVersion,
      String sampleSource) {
    if (!revisionId.equals(candidate.proposed().revisionId())) {
      throw new IllegalArgumentException(
          "candidate revision does not match the requested revision");
    }
    if (candidate.glossaryVersion() != expectedVersion) {
      throw new GlossaryRevisionPublicationRepository.VersionConflictException(
          candidate.glossaryVersion());
    }
    return previewService.preview(
        candidate.base(), candidate.proposed(), sampleSource, SHORT_TERM_WARNING_CODE_POINTS);
  }

  public static final class NotPublishableException extends RuntimeException {
    private final GlossaryRevisionPreviewService.Preview preview;

    public NotPublishableException(GlossaryRevisionPreviewService.Preview preview) {
      this.preview = Objects.requireNonNull(preview, "preview is required");
    }

    public GlossaryRevisionPreviewService.Preview preview() {
      return preview;
    }
  }

  public static final class RevisionAlreadyActiveException extends RuntimeException {
    private final long currentVersion;

    public RevisionAlreadyActiveException(long currentVersion) {
      this.currentVersion = currentVersion;
    }

    public long currentVersion() {
      return currentVersion;
    }
  }
}
