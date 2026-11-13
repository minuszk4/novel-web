package com.novelweb.progression.cultivation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class CultivationReadingSessionService {

  public static final int HEARTBEAT_INTERVAL_SECONDS = 30;
  public static final int MAX_CREDIT_SECONDS = 60;
  public static final int IDLE_TIMEOUT_SECONDS = 90;
  private static final Pattern IDEMPOTENCY_KEY_PATTERN =
      Pattern.compile("^[A-Za-z0-9._~-]{16,128}$");

  private final CultivationReadingSessionRepository repository;

  public CultivationReadingSessionService(CultivationReadingSessionRepository repository) {
    this.repository = repository;
  }

  public StartOutcome start(UUID actorUserId, StartRequest request, String idempotencyKey) {
    if (request == null
        || request.workId() == null
        || request.chapterId() == null
        || !Boolean.TRUE.equals(request.active())
        || request.hasUnknownFields()) {
      throw new InvalidReadingSessionRequestException(
          "Provide workId, chapterId and active=true only when the Reader is visible and focused.");
    }
    String key = requireIdempotencyKey(idempotencyKey);
    byte[] requestHash =
        sha256(
            "cultivation-session-start-v1|"
                + request.workId()
                + "|"
                + request.chapterId()
                + "|active=true");
    CultivationReadingSessionRepository.StartOutcome outcome =
        repository.start(actorUserId, request.workId(), request.chapterId(), key, requestHash);
    return new StartOutcome(toStartResponse(outcome.session()), outcome.replayed());
  }

  public HeartbeatOutcome heartbeat(
      UUID actorUserId, UUID sessionId, HeartbeatRequest request, String idempotencyKey) {
    if (request == null
        || request.sequence() == null
        || request.sequence() < 1
        || request.active() == null
        || request.hasUnknownFields()) {
      throw new InvalidReadingSessionRequestException(
          "Provide a positive sequence and the current visible-and-focused active boolean only.");
    }
    String key = requireIdempotencyKey(idempotencyKey);
    byte[] requestHash =
        sha256(
            "cultivation-session-heartbeat-v1|"
                + request.sequence()
                + "|active="
                + request.active());
    CultivationReadingSessionRepository.HeartbeatOutcome outcome =
        repository.heartbeat(
            actorUserId, sessionId, request.sequence(), request.active(), key, requestHash);
    return new HeartbeatOutcome(toHeartbeatResponse(outcome.result()), outcome.replayed());
  }

  public CloseResponse close(UUID actorUserId, UUID sessionId) {
    return toCloseResponse(repository.close(actorUserId, sessionId));
  }

  @Scheduled(fixedDelayString = "\u0024{novel.progression.cultivation.session-cleanup-delay:1h}")
  public void purgeExpiredSessions() {
    repository.purgeExpiredSessions();
  }

  public static int creditableSeconds(
      Instant previousReceipt, Instant currentReceipt, boolean active) {
    if (!active || previousReceipt == null || currentReceipt == null) return 0;
    long elapsed = Duration.between(previousReceipt, currentReceipt).getSeconds();
    if (elapsed <= 0) return 0;
    return (int) Math.min(MAX_CREDIT_SECONDS, elapsed);
  }

  public static boolean isExpired(Instant previousReceipt, Instant currentReceipt) {
    return previousReceipt == null
        || currentReceipt == null
        || Duration.between(previousReceipt, currentReceipt)
                .compareTo(Duration.ofSeconds(IDLE_TIMEOUT_SECONDS))
            >= 0;
  }

  private static String requireIdempotencyKey(String value) {
    if (value == null || !IDEMPOTENCY_KEY_PATTERN.matcher(value).matches()) {
      throw new InvalidReadingSessionRequestException(
          "Idempotency-Key must contain 16 to 128 URL-safe characters.");
    }
    return value;
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static StartResponse toStartResponse(
      CultivationReadingSessionRepository.SessionSnapshot session) {
    return new StartResponse(
        session.id(),
        session.state(),
        session.startedAt(),
        session.lastHeartbeatAt().plusSeconds(IDLE_TIMEOUT_SECONDS),
        session.lastSequence(),
        session.totalActiveSeconds(),
        HEARTBEAT_INTERVAL_SECONDS,
        IDLE_TIMEOUT_SECONDS);
  }

  private static HeartbeatResponse toHeartbeatResponse(
      CultivationReadingSessionRepository.HeartbeatResult result) {
    return new HeartbeatResponse(
        result.sessionId(),
        result.state(),
        result.sequence(),
        result.creditedSeconds(),
        result.totalActiveSeconds(),
        result.receivedAt(),
        result.expiresAt());
  }

  private static CloseResponse toCloseResponse(
      CultivationReadingSessionRepository.SessionSnapshot session) {
    return new CloseResponse(
        session.id(),
        session.state(),
        session.startedAt(),
        session.closedAt(),
        session.totalActiveSeconds(),
        session.lastSequence());
  }

  public record StartOutcome(StartResponse session, boolean replayed) {}

  public record StartResponse(
      UUID sessionId,
      String state,
      Instant startedAt,
      Instant expiresAt,
      long lastSequence,
      long totalActiveSeconds,
      int heartbeatIntervalSeconds,
      int idleTimeoutSeconds) {}

  public record HeartbeatOutcome(HeartbeatResponse heartbeat, boolean replayed) {}

  public record HeartbeatResponse(
      UUID sessionId,
      String state,
      long sequence,
      int creditedSeconds,
      long totalActiveSeconds,
      Instant receivedAt,
      Instant expiresAt) {}

  public record CloseResponse(
      UUID sessionId,
      String state,
      Instant startedAt,
      Instant closedAt,
      long totalActiveSeconds,
      long lastSequence) {}

  public static final class StartRequest {
    private UUID workId;
    private UUID chapterId;
    private Boolean active;
    private boolean unknownFields;

    public UUID workId() {
      return workId;
    }

    public void setWorkId(UUID workId) {
      this.workId = workId;
    }

    public UUID chapterId() {
      return chapterId;
    }

    public void setChapterId(UUID chapterId) {
      this.chapterId = chapterId;
    }

    public Boolean active() {
      return active;
    }

    public void setActive(Boolean active) {
      this.active = active;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public static final class HeartbeatRequest {
    private Long sequence;
    private Boolean active;
    private boolean unknownFields;

    public Long sequence() {
      return sequence;
    }

    public void setSequence(Long sequence) {
      this.sequence = sequence;
    }

    public Boolean active() {
      return active;
    }

    public void setActive(Boolean active) {
      this.active = active;
    }

    boolean hasUnknownFields() {
      return unknownFields;
    }

    @JsonAnySetter
    public void captureUnknownField(String name, Object ignoredValue) {
      unknownFields = true;
    }
  }

  public static final class InvalidReadingSessionRequestException extends RuntimeException {
    public InvalidReadingSessionRequestException(String message) {
      super(message);
    }
  }
}
