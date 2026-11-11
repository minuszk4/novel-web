package com.novelweb.library.reader;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class ReaderProgressService {

  private static final Pattern STABLE_ANCHOR =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$");

  private final ReaderProgressRepository repository;

  public ReaderProgressService(ReaderProgressRepository repository) {
    this.repository = repository;
  }

  public ReaderProgressRepository.ProgressRecord get(UUID actorUserId, UUID workId) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    return repository.find(actorUserId, workId);
  }

  public ReaderProgressRepository.ProgressRecord save(
      UUID actorUserId, UUID workId, SaveProgressRequest request) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(workId, "work id is required");
    if (request == null || request.expectedVersion() == null || request.scrollRatio() == null) {
      throw new IllegalArgumentException("expectedVersion and scrollRatio are required");
    }
    if (request.expectedVersion() < 0) {
      throw new IllegalArgumentException("expectedVersion must be zero or greater");
    }
    double ratio = request.scrollRatio();
    if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) {
      throw new IllegalArgumentException("scrollRatio must be between zero and one");
    }
    if (request.chapterId() == null) {
      if (request.segmentAnchor() != null || ratio != 0) {
        throw new IllegalArgumentException(
            "chapterId is required when an anchor or non-zero scrollRatio is provided");
      }
    } else if (request.segmentAnchor() != null
        && !STABLE_ANCHOR.matcher(request.segmentAnchor()).matches()) {
      throw new IllegalArgumentException("segmentAnchor must be a valid stable source anchor");
    }
    return repository.save(
        actorUserId,
        workId,
        request.expectedVersion(),
        request.chapterId(),
        request.segmentAnchor(),
        ratio);
  }

  public record SaveProgressRequest(
      Long expectedVersion, UUID chapterId, String segmentAnchor, Double scrollRatio) {}
}
