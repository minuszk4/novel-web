package com.novelweb.translation.jobs;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Produces a versioned, length-delimited identity for an exact set of manual edits. */
public final class TranslationReviewSnapshotHasher {

  private static final byte[] SNAPSHOT_DOMAIN =
      "novel-translation-review-snapshot-v1\0".getBytes(StandardCharsets.UTF_8);
  private static final byte[] REQUEST_DOMAIN =
      "novel-translation-review-submit-v1\0".getBytes(StandardCharsets.UTF_8);
  private static final byte[] IDEMPOTENCY_DOMAIN =
      "novel-translation-review-idempotency-v1\0".getBytes(StandardCharsets.UTF_8);

  private TranslationReviewSnapshotHasher() {}

  public static byte[] snapshotDigest(
      SnapshotContext context,
      List<TranslationCandidateReviewRepository.SnapshotSegment> segments) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.write(SNAPSHOT_DOMAIN);
        writeUuid(output, context.candidateId());
        writeUuid(output, context.jobId());
        writeUuid(output, context.workId());
        writeUuid(output, context.chapterId());
        writeUuid(output, context.sourceRevisionId());
        writeString(output, context.sourceLanguage());
        writeString(output, context.targetLanguage());
        writeBytes(output, context.sourceSha256());
        output.writeInt(segments.size());
        for (TranslationCandidateReviewRepository.SnapshotSegment segment : segments) {
          writeUuid(output, segment.sourceSegmentId());
          output.writeInt(segment.ordinal());
          output.writeInt(segment.editRevision());
          writeBytes(output, segment.translatedSha256());
        }
      }
      return newDigest().digest(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode translation review snapshot", exception);
    }
  }

  public static byte[] requestDigest(UUID candidateId, UUID jobId, String expectedEtag) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.write(REQUEST_DOMAIN);
        writeUuid(output, candidateId);
        writeUuid(output, jobId);
        writeString(output, expectedEtag);
      }
      return newDigest().digest(bytes.toByteArray());
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode translation review request", exception);
    }
  }

  public static byte[] idempotencyDigest(String key) {
    MessageDigest digest = newDigest();
    digest.update(IDEMPOTENCY_DOMAIN);
    digest.update(key.getBytes(StandardCharsets.US_ASCII));
    return digest.digest();
  }

  public static String etag(byte[] digest) {
    return "\"translation-review-" + HexFormat.of().formatHex(digest) + "\"";
  }

  public static String digestHex(byte[] digest) {
    return HexFormat.of().formatHex(digest);
  }

  private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
    output.writeLong(value.getMostSignificantBits());
    output.writeLong(value.getLeastSignificantBits());
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
    output.writeInt(value.length);
    output.write(value);
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record SnapshotContext(
      UUID candidateId,
      UUID jobId,
      UUID workId,
      UUID chapterId,
      UUID sourceRevisionId,
      String sourceLanguage,
      String targetLanguage,
      byte[] sourceSha256) {
    public SnapshotContext {
      sourceSha256 = sourceSha256.clone();
    }

    @Override
    public byte[] sourceSha256() {
      return sourceSha256.clone();
    }
  }
}
