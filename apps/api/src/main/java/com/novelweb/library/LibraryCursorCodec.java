package com.novelweb.library;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Issues opaque, owner/filter-bound keyset cursors for private library lists. */
@Service
@Profile("postgres")
public class LibraryCursorCodec {

  private static final int VERSION = 2;
  private static final int PAYLOAD_LENGTH = 98;
  private static final int SIGNATURE_LENGTH = 32;
  private static final int MAX_TOKEN_LENGTH = 2048;
  private static final UUID NO_SCOPE = new UUID(0, 0);
  private static final Pattern CANONICAL_BASE64URL = Pattern.compile("[A-Za-z0-9_-]{43}");
  private final byte[] hmacKey;

  public LibraryCursorCodec(@Value("${novel.library.cursor.hmac-key:}") String encodedHmacKey) {
    this.hmacKey = decodeKey(encodedHmacKey);
  }

  public static CursorContext libraryContext(UUID ownerUserId) {
    return libraryContext(ownerUserId, "");
  }

  public static CursorContext libraryContext(UUID ownerUserId, String accessScopeFingerprint) {
    return new CursorContext(
        Kind.LIBRARY, ownerUserId, NO_SCOPE, "", "", "", accessScopeFingerprint);
  }

  public static CursorContext workContext(
      UUID ownerUserId, UUID libraryId, String query, String tag, String status) {
    return workContext(ownerUserId, libraryId, query, tag, status, "");
  }

  public static CursorContext workContext(
      UUID ownerUserId,
      UUID libraryId,
      String query,
      String tag,
      String status,
      String accessScopeFingerprint) {
    return new CursorContext(
        Kind.WORK,
        ownerUserId,
        libraryId,
        normalizeQuery(query),
        normalizeTag(tag),
        normalizeStatus(status),
        accessScopeFingerprint);
  }

  public static CursorContext publicCatalogContext(String query, String language) {
    return new CursorContext(
        Kind.PUBLIC_CATALOG,
        NO_SCOPE,
        NO_SCOPE,
        normalizeQuery(query),
        normalizeTag(language),
        "",
        "public-catalog-v1");
  }

  public void requireConfigured() {
    if (hmacKey == null) throw new SigningUnavailableException();
  }

  public String encode(CursorContext context, Position position) {
    Objects.requireNonNull(context, "cursor context is required");
    Objects.requireNonNull(position, "cursor position is required");
    requireConfigured();
    byte[] payload = payload(context, position);
    byte[] signature = sign(payload);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
  }

  public Position decode(String token, CursorContext expectedContext) {
    Objects.requireNonNull(expectedContext, "expected cursor context is required");
    requireConfigured();
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new InvalidCursorException();
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2) throw new InvalidCursorException();

    byte[] payload = decodeCanonical(parts[0]);
    byte[] signature = decodeCanonical(parts[1]);
    if (payload.length != PAYLOAD_LENGTH
        || signature.length != SIGNATURE_LENGTH
        || !MessageDigest.isEqual(sign(payload), signature)) {
      throw new InvalidCursorException();
    }

    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      int version = input.readUnsignedByte();
      Kind kind = Kind.fromCode(input.readUnsignedByte());
      UUID ownerUserId = readUuid(input);
      UUID scopeId = readUuid(input);
      byte[] filterHash = input.readNBytes(32);
      int rank = input.readInt();
      Instant createdAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
      UUID itemId = readUuid(input);

      if (version != VERSION
          || input.available() != 0
          || kind != expectedContext.kind()
          || !ownerUserId.equals(expectedContext.ownerUserId())
          || !scopeId.equals(expectedContext.scopeId())
          || !MessageDigest.isEqual(filterHash, expectedContext.filterHash())
          || rank < 0
          || rank > 1) {
        throw new InvalidCursorException();
      }
      return new Position(rank, createdAt, itemId);
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof InvalidCursorException invalidCursorException)
        throw invalidCursorException;
      throw new InvalidCursorException();
    }
  }

  private byte[] payload(CursorContext context, Position position) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(PAYLOAD_LENGTH);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        output.writeByte(context.kind().code());
        writeUuid(output, context.ownerUserId());
        writeUuid(output, context.scopeId());
        output.write(context.filterHash());
        output.writeInt(position.rank());
        output.writeLong(position.createdAt().getEpochSecond());
        output.writeInt(position.createdAt().getNano());
        writeUuid(output, position.itemId());
      }
      byte[] payload = bytes.toByteArray();
      if (payload.length != PAYLOAD_LENGTH)
        throw new IllegalStateException("Invalid cursor payload size");
      return payload;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode a library cursor", exception);
    }
  }

  private byte[] sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to sign a library cursor", exception);
    }
  }

  private static byte[] decodeCanonical(String encoded) {
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(encoded);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
        throw new InvalidCursorException();
      }
      return decoded;
    } catch (IllegalArgumentException exception) {
      throw new InvalidCursorException();
    }
  }

  private static byte[] decodeKey(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (!CANONICAL_BASE64URL.matcher(normalized).matches()) {
      throw new IllegalArgumentException(
          "Library cursor HMAC key must be canonical Base64URL for 32 bytes");
    }
    try {
      byte[] key = Base64.getUrlDecoder().decode(normalized);
      if (key.length != 32
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(normalized)) {
        throw new IllegalArgumentException(
            "Library cursor HMAC key must be canonical Base64URL for 32 bytes");
      }
      return key;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Library cursor HMAC key must be canonical Base64URL for 32 bytes", exception);
    }
  }

  private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
    output.writeLong(value.getMostSignificantBits());
    output.writeLong(value.getLeastSignificantBits());
  }

  private static UUID readUuid(DataInputStream input) throws IOException {
    return new UUID(input.readLong(), input.readLong());
  }

  private static String normalizeQuery(String query) {
    return query == null ? "" : query.trim();
  }

  private static String normalizeTag(String tag) {
    return tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeStatus(String status) {
    return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
  }

  public enum Kind {
    LIBRARY(1),
    WORK(2),
    PUBLIC_CATALOG(3);

    private final int code;

    Kind(int code) {
      this.code = code;
    }

    int code() {
      return code;
    }

    private static Kind fromCode(int code) {
      for (Kind kind : values()) if (kind.code == code) return kind;
      throw new InvalidCursorException();
    }
  }

  public record CursorContext(
      Kind kind,
      UUID ownerUserId,
      UUID scopeId,
      String query,
      String tag,
      String status,
      String accessScopeFingerprint) {
    public CursorContext {
      Objects.requireNonNull(kind, "cursor kind is required");
      Objects.requireNonNull(ownerUserId, "owner user id is required");
      Objects.requireNonNull(scopeId, "cursor scope is required");
      query = normalizeQuery(query);
      tag = normalizeTag(tag);
      status = normalizeStatus(status);
      Objects.requireNonNull(accessScopeFingerprint, "access scope fingerprint is required");
      if (kind == Kind.LIBRARY
          && (!scopeId.equals(NO_SCOPE)
              || !query.isEmpty()
              || !tag.isEmpty()
              || !status.isEmpty())) {
        throw new IllegalArgumentException("Library cursors do not accept work filters");
      }
      if (kind == Kind.PUBLIC_CATALOG
          && (!ownerUserId.equals(NO_SCOPE)
              || !scopeId.equals(NO_SCOPE)
              || !status.isEmpty()
              || !accessScopeFingerprint.equals("public-catalog-v1"))) {
        throw new IllegalArgumentException("Invalid public catalog cursor context");
      }
    }

    byte[] filterHash() {
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) kind.code());
        updateUuid(digest, ownerUserId);
        updateUuid(digest, scopeId);
        updateString(digest, "created_at_desc,id_desc");
        updateString(digest, query);
        updateString(digest, tag);
        updateString(digest, status);
        updateString(digest, accessScopeFingerprint);
        return digest.digest();
      } catch (GeneralSecurityException exception) {
        throw new IllegalStateException("SHA-256 is unavailable", exception);
      }
    }

    private static void updateUuid(MessageDigest digest, UUID value) {
      digest.update(
          java.nio.ByteBuffer.allocate(16)
              .putLong(value.getMostSignificantBits())
              .putLong(value.getLeastSignificantBits())
              .array());
    }

    private static void updateString(MessageDigest digest, String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
      digest.update(bytes);
    }
  }

  public record Position(int rank, Instant createdAt, UUID itemId) {
    public Position {
      Objects.requireNonNull(createdAt, "cursor timestamp is required");
      Objects.requireNonNull(itemId, "cursor item id is required");
      if (rank < 0 || rank > 1) throw new IllegalArgumentException("invalid cursor sort rank");
    }
  }

  public static class InvalidCursorException extends RuntimeException {}

  public static class SigningUnavailableException extends RuntimeException {}
}
