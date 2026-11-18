package com.novelweb.identity.privacy;

import com.novelweb.identity.privacy.AccountDataExportArchiveWriter.Artifact;
import com.novelweb.identity.privacy.AccountDataExportRepository.ClaimedJob;
import com.novelweb.identity.privacy.AccountDataExportRepository.CreateOutcome;
import com.novelweb.identity.privacy.AccountDataExportRepository.CreateResult;
import com.novelweb.identity.privacy.AccountDataExportRepository.ExpiredArtifact;
import com.novelweb.identity.privacy.AccountDataExportRepository.ExportJob;
import com.novelweb.identity.privacy.AccountDataExportRepository.ExportStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Applies high-risk export policy, idempotency, worker state transitions and artifact expiry. */
@Service
@Profile("postgres")
public class AccountDataExportService {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._~-]{16,128}");
  private static final Duration MAXIMUM_ARTIFACT_TTL = Duration.ofDays(7);

  private final AccountDataExportRepository repository;
  private final AccountDataExportArchiveWriter archiveWriter;
  private final boolean apiEnabled;
  private final boolean workerEnabled;
  private final Duration artifactTtl;
  private final int batchSize;

  public AccountDataExportService(
      AccountDataExportRepository repository,
      AccountDataExportArchiveWriter archiveWriter,
      @Value("${novel.account-data-export.enabled:false}") boolean apiEnabled,
      @Value("${novel.account-data-export.worker-enabled:false}") boolean workerEnabled,
      @Value("${novel.account-data-export.artifact-ttl:24h}") Duration artifactTtl,
      @Value("${novel.account-data-export.batch-size:3}") int batchSize) {
    this.repository = repository;
    this.archiveWriter = archiveWriter;
    this.apiEnabled = apiEnabled;
    this.workerEnabled = workerEnabled;
    if (artifactTtl.isZero()
        || artifactTtl.isNegative()
        || artifactTtl.compareTo(MAXIMUM_ARTIFACT_TTL) > 0
        || batchSize < 1
        || batchSize > 25) {
      throw new IllegalArgumentException("Account export retention and batch settings are invalid");
    }
    this.artifactTtl = artifactTtl;
    this.batchSize = batchSize;
  }

  public boolean isAvailable() {
    return apiEnabled && workerEnabled;
  }

  public CreateResult createOrReplay(UUID userId, String idempotencyKey) {
    if (!isAvailable()) throw new IllegalStateException("Account data export is disabled");
    if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new IllegalArgumentException("A valid Idempotency-Key is required");
    }
    return repository.createOrReplay(userId, sha256(idempotencyKey));
  }

  public Optional<ExportJob> status(UUID userId, UUID exportId) {
    return repository.findOwned(userId, exportId);
  }

  public Optional<ExportJob> latest(UUID userId) {
    return repository.findLatestOwned(userId);
  }

  public Optional<Path> downloadableArtifact(UUID userId, UUID exportId) {
    Optional<ExportJob> found = repository.findOwned(userId, exportId);
    if (found.isEmpty()) return Optional.empty();
    ExportJob job = found.orElseThrow();
    if (job.status() != ExportStatus.READY
        || job.expiresAt() == null
        || !job.expiresAt().isAfter(Instant.now())
        || job.storageKey() == null) {
      return Optional.empty();
    }
    Path file = archiveWriter.path(job.storageKey());
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      repository.markArtifactUnavailable(userId, exportId, job.storageKey());
      archiveWriter.delete(job.storageKey());
      return Optional.empty();
    }
    try {
      if (Files.size(file) != job.artifactBytes()) {
        repository.markArtifactUnavailable(userId, exportId, job.storageKey());
        archiveWriter.delete(job.storageKey());
        return Optional.empty();
      }
      byte[] actualHash;
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] chunk = new byte[16 * 1024];
        int read;
        while ((read = input.read(chunk)) >= 0) {
          if (read > 0) digest.update(chunk, 0, read);
        }
        actualHash = digest.digest();
      }
      if (!MessageDigest.isEqual(actualHash, job.artifactSha256())) {
        repository.markArtifactUnavailable(userId, exportId, job.storageKey());
        archiveWriter.delete(job.storageKey());
        return Optional.empty();
      }
      return Optional.of(file);
    } catch (IOException exception) {
      repository.markArtifactUnavailable(userId, exportId, job.storageKey());
      archiveWriter.delete(job.storageKey());
      return Optional.empty();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public Optional<ClaimedJob> claimNext() {
    if (!workerEnabled) return Optional.empty();
    return repository.claimNext();
  }

  public boolean generate(ClaimedJob claim) {
    Objects.requireNonNull(claim, "claimed export job is required");
    UUID storageKey = UUID.randomUUID();
    try {
      Artifact artifact = archiveWriter.create(claim, storageKey);
      if (!repository.markReady(
          claim, storageKey, artifact.sha256(), artifact.size(), artifactTtl)) {
        archiveWriter.delete(storageKey);
        return false;
      }
      return true;
    } catch (AccountDataExportArchiveWriter.SizeLimitExceededException exception) {
      repository.markFailed(claim, "SIZE_LIMIT_EXCEEDED");
      archiveWriter.delete(storageKey);
      return false;
    } catch (IOException | RuntimeException exception) {
      repository.markFailed(claim, "GENERATION_FAILED");
      archiveWriter.delete(storageKey);
      return false;
    }
  }

  public int cleanupExpiredArtifacts() {
    if (!workerEnabled) return 0;
    int cleaned = 0;
    List<ExpiredArtifact> expired = repository.findExpiredArtifacts(batchSize * 10);
    for (ExpiredArtifact artifact : expired) {
      if (archiveWriter.delete(artifact.storageKey()) && repository.markExpired(artifact))
        cleaned++;
    }
    return cleaned;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record JobResponse(
      UUID id,
      String status,
      Instant requestedAt,
      Instant startedAt,
      Instant readyAt,
      Instant expiresAt,
      Long artifactBytes,
      String failureCode,
      String downloadUrl) {
    public static JobResponse from(ExportJob job) {
      boolean expired =
          job.status() == ExportStatus.READY
              && job.expiresAt() != null
              && !job.expiresAt().isAfter(Instant.now());
      String status = expired ? "expired" : job.status().name().toLowerCase(java.util.Locale.ROOT);
      return new JobResponse(
          job.id(),
          status,
          job.requestedAt(),
          job.startedAt(),
          job.readyAt(),
          job.expiresAt(),
          job.artifactBytes(),
          job.failureCode(),
          job.status() == ExportStatus.READY && !expired
              ? "/api/v1/me/data-exports/" + job.id() + "/download"
              : null);
    }
  }

  public record CreateResponse(JobResponse export, boolean replayed) {
    public static CreateResponse from(CreateResult result) {
      return new CreateResponse(
          JobResponse.from(result.job()), result.outcome() == CreateOutcome.REPLAYED);
    }
  }
}
