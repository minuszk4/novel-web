package com.novelweb.identity.roles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Validates the bounded commands used to manage publication reviewer assignments. */
@Service
@Profile("postgres")
public class SecurityRoleAssignmentService {

  private static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9._~-]{16,128}";
  private static final List<String> GRANT_REASONS =
      List.of("REVIEW_CAPACITY", "TEMPORARY_COVERAGE", "ROLE_CORRECTION");
  private static final List<String> REVOKE_REASONS =
      List.of("ACCESS_ENDED", "ROLE_CHANGE", "SECURITY_REVIEW");
  private static final Pattern ACCOUNT_SEARCH_PATTERN = Pattern.compile("[A-Za-z0-9._+@-]{3,320}");

  private final SecurityRoleAssignmentRepository repository;

  public SecurityRoleAssignmentService(SecurityRoleAssignmentRepository repository) {
    this.repository = Objects.requireNonNull(repository, "role assignment repository is required");
  }

  public List<SecurityRoleAssignmentRepository.ReviewerAssignment> listReviewerAssignments(
      UUID actorUserId, String status) {
    if (!List.of("active", "revoked", "all").contains(status)) {
      throw new IllegalArgumentException("A supported reviewer assignment status is required");
    }
    return repository.listReviewerAssignments(Objects.requireNonNull(actorUserId), status);
  }

  public List<SecurityRoleAssignmentRepository.ReviewerAccountCandidate> searchEligibleTargets(
      UUID actorUserId, String query) {
    String normalized = query == null ? "" : query.trim();
    if (normalized.length() > 320) {
      throw new IllegalArgumentException("Account search query is too long");
    }
    UUID exactUserId = null;
    try {
      exactUserId = UUID.fromString(normalized);
    } catch (IllegalArgumentException ignored) {
      if (!ACCOUNT_SEARCH_PATTERN.matcher(normalized).matches()) {
        throw new IllegalArgumentException("Account search requires an email prefix or UUID");
      }
    }
    if (exactUserId == null && normalized.length() < 3) {
      throw new IllegalArgumentException("Account search query must contain at least 3 characters");
    }
    return repository.searchEligibleReviewerTargets(
        Objects.requireNonNull(actorUserId), normalized.toLowerCase(Locale.ROOT), exactUserId);
  }

  public SecurityRoleAssignmentRepository.CommandResult grantReviewer(
      UUID actorUserId, UUID targetUserId, String reasonCode, String idempotencyKey) {
    validateIdempotencyKey(idempotencyKey);
    String reason = validateReason(reasonCode, GRANT_REASONS);
    return repository.grantReviewer(
        Objects.requireNonNull(actorUserId),
        Objects.requireNonNull(targetUserId),
        reason,
        idempotencyKeyHash(idempotencyKey),
        requestHash("GRANT", targetUserId, reason));
  }

  public SecurityRoleAssignmentRepository.CommandResult revokeReviewer(
      UUID actorUserId, UUID targetUserId, String reasonCode, String idempotencyKey) {
    validateIdempotencyKey(idempotencyKey);
    String reason = validateReason(reasonCode, REVOKE_REASONS);
    return repository.revokeReviewer(
        Objects.requireNonNull(actorUserId),
        Objects.requireNonNull(targetUserId),
        reason,
        idempotencyKeyHash(idempotencyKey),
        requestHash("REVOKE", targetUserId, reason));
  }

  private static void validateIdempotencyKey(String key) {
    if (key == null || !key.matches(IDEMPOTENCY_KEY_PATTERN)) {
      throw new IllegalArgumentException(
          "Idempotency-Key must contain 16 to 128 safe ASCII characters");
    }
  }

  private static String validateReason(String reason, List<String> allowed) {
    if (reason == null || !allowed.contains(reason)) {
      throw new IllegalArgumentException("A supported role assignment reason is required");
    }
    return reason;
  }

  private static byte[] idempotencyKeyHash(String key) {
    return sha256(key.getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] requestHash(String action, UUID targetUserId, String reason) {
    String canonical =
        String.join("\n", "publication_reviewer", action, targetUserId.toString(), reason);
    return sha256(canonical.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  static String advisoryLockKey(UUID actorUserId, byte[] idempotencyHash) {
    return actorUserId + ":" + HexFormat.of().formatHex(idempotencyHash);
  }
}
