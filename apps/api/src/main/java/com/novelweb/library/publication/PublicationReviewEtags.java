package com.novelweb.library.publication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Strong validator for a reviewer-visible request state and its current decision gates. */
final class PublicationReviewEtags {

  private PublicationReviewEtags() {}

  static String decision(
      UUID requestId,
      int requestVersion,
      int eventSequence,
      String state,
      String snapshotSha256,
      long currentContentVersion,
      String currentPolicyVersion,
      String currentPolicySha256) {
    String canonical =
        String.join(
            "\n",
            "publication-review-decision-v1",
            Objects.requireNonNull(requestId, "request id is required").toString(),
            Integer.toString(requestVersion),
            Integer.toString(eventSequence),
            Objects.requireNonNull(state, "request state is required"),
            Objects.requireNonNull(snapshotSha256, "snapshot digest is required"),
            Long.toString(currentContentVersion),
            Objects.toString(currentPolicyVersion, "none"),
            Objects.toString(currentPolicySha256, "none"));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return "\"publication-review-" + HexFormat.of().formatHex(digest) + "\"";
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
