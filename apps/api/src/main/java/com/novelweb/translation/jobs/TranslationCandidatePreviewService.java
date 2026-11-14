package com.novelweb.translation.jobs;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Reads bounded source/fake-result pairs for an authorized writer review preview. */
@Service
@Profile("postgres")
public class TranslationCandidatePreviewService {

  private static final int MAX_LIMIT = 25;
  private static final int MAX_OFFSET = 5000;

  private final TranslationCandidatePreviewRepository repository;

  public TranslationCandidatePreviewService(TranslationCandidatePreviewRepository repository) {
    this.repository =
        Objects.requireNonNull(repository, "translation candidate preview repository is required");
  }

  public Optional<CandidatePreview> findCurrentReadyForReview(
      UUID actorUserId, UUID jobId, int offset, int limit) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(jobId, "job id is required");
    if (offset < 0 || offset > MAX_OFFSET || limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("candidate preview page is outside supported bounds");
    }
    return repository.findCurrentReadyForReview(actorUserId, jobId, offset, limit);
  }

  public record CandidatePreview(
      UUID jobId,
      UUID candidateId,
      UUID sourceRevisionId,
      boolean sourceRevisionCurrent,
      String sourceLanguage,
      String targetLanguage,
      String providerId,
      String candidateStatus,
      int pageOffset,
      int pageLimit,
      int totalSegments,
      List<PreviewSegment> segments) {}

  public record PreviewSegment(
      UUID sourceSegmentId,
      int ordinal,
      String sourceType,
      String sourceText,
      String fakeResultText,
      String editedText,
      int editRevision,
      String editEtag) {}
}
