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
    if (candidate.glossaryVersion() != expectedVersion) {
      throw new GlossaryRevisionPublicationRepository.VersionConflictException(
          candidate.glossaryVersion());
    }
    GlossaryRevisionPreviewService.Preview preview =
        previewService.preview(
            candidate.base(), candidate.proposed(), "", SHORT_TERM_WARNING_CODE_POINTS);
    if (!preview.canPublish()) throw new NotPublishableException(preview);
    return repository.publishRevision(actorUserId, glossaryId, revisionId, expectedVersion);
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
}
