package com.novelweb.library.publication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Canonical opaque validators shared by readiness reads and request submission. */
final class PublicationSnapshotEtags {

  private PublicationSnapshotEtags() {}

  static String scope(String scopeCode, String snapshotJson) {
    return quoted("publication-scope-", sha256(scopeCode + "\n" + snapshotJson));
  }

  static String readiness(
      UUID workId,
      long contentVersion,
      int readyChapterCount,
      String policyVersion,
      String policyDigest,
      Instant acceptedAt,
      String currentRequestState,
      List<String> scopeEtags) {
    String canonical =
        String.join(
            "\n",
            "publication-readiness-v1",
            workId.toString(),
            Long.toString(contentVersion),
            Integer.toString(readyChapterCount),
            Objects.toString(policyVersion, "none"),
            policyDigest,
            Objects.toString(acceptedAt, "none"),
            Objects.toString(currentRequestState, "none"),
            String.join("\n", scopeEtags));
    return quoted("publication-snapshot-", sha256(canonical));
  }

  private static String quoted(String prefix, byte[] digest) {
    return "\"" + prefix + HexFormat.of().formatHex(digest) + "\"";
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }
}
