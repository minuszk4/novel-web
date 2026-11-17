package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranslationReviewSnapshotHasherTests {

  private final TranslationReviewSnapshotHasher.SnapshotContext context =
      new TranslationReviewSnapshotHasher.SnapshotContext(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          UUID.fromString("00000000-0000-0000-0000-000000000002"),
          UUID.fromString("00000000-0000-0000-0000-000000000003"),
          UUID.fromString("00000000-0000-0000-0000-000000000004"),
          UUID.fromString("00000000-0000-0000-0000-000000000005"),
          "vi",
          "en",
          new byte[32]);

  @Test
  void snapshotDigestIsDeterministicAndBindsContextAndSegmentOrder() {
    TranslationCandidateReviewRepository.SnapshotSegment first =
        new TranslationCandidateReviewRepository.SnapshotSegment(
            UUID.fromString("00000000-0000-0000-0000-000000000006"), 1, 3, new byte[32], true);
    byte[] secondHash = new byte[32];
    secondHash[0] = 1;
    TranslationCandidateReviewRepository.SnapshotSegment second =
        new TranslationCandidateReviewRepository.SnapshotSegment(
            UUID.fromString("00000000-0000-0000-0000-000000000007"), 2, 1, secondHash, true);
    List<TranslationCandidateReviewRepository.SnapshotSegment> ordered = List.of(first, second);

    byte[] original = TranslationReviewSnapshotHasher.snapshotDigest(context, ordered);
    assertArrayEquals(original, TranslationReviewSnapshotHasher.snapshotDigest(context, ordered));
    assertNotEquals(
        TranslationReviewSnapshotHasher.digestHex(original),
        TranslationReviewSnapshotHasher.digestHex(
            TranslationReviewSnapshotHasher.snapshotDigest(context, List.of(second, first))));
    assertNotEquals(
        TranslationReviewSnapshotHasher.digestHex(original),
        TranslationReviewSnapshotHasher.digestHex(
            TranslationReviewSnapshotHasher.snapshotDigest(
                new TranslationReviewSnapshotHasher.SnapshotContext(
                    context.candidateId(),
                    context.jobId(),
                    context.workId(),
                    context.chapterId(),
                    context.sourceRevisionId(),
                    "vi",
                    "fr",
                    context.sourceSha256()),
                ordered)));
    assertTrue(
        TranslationReviewSnapshotHasher.etag(original)
            .matches("\"translation-review-[a-f0-9]{64}\""));
  }

  @Test
  void requestAndIdempotencyDigestsAreDomainSeparatedAndStable() {
    String key = "translation-review-submit-test-01";
    byte[] digest = TranslationReviewSnapshotHasher.idempotencyDigest(key);
    assertArrayEquals(digest, TranslationReviewSnapshotHasher.idempotencyDigest(key));
    assertNotEquals(
        TranslationReviewSnapshotHasher.digestHex(digest),
        TranslationReviewSnapshotHasher.digestHex(
            TranslationReviewSnapshotHasher.requestDigest(
                context.candidateId(),
                context.jobId(),
                "\"translation-review-" + "a".repeat(64) + "\"")));
    assertNotEquals(
        TranslationReviewSnapshotHasher.digestHex(digest),
        TranslationReviewSnapshotHasher.digestHex(
            TranslationReviewSnapshotHasher.idempotencyDigest(key + "-changed")));
  }
}
