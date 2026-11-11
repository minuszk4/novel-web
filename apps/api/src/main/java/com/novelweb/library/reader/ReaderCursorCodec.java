package com.novelweb.library.reader;

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
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Issues actor- and resource-bound cursors for the source-only Reader pages. */
@Service
@Profile("postgres")
public class ReaderCursorCodec {

  private static final int PAYLOAD_LENGTH = 54;
  private static final int KEYSET_PAYLOAD_LENGTH = 62;
  private static final int SIGNATURE_LENGTH = 32;
  private static final int MAX_TOKEN_LENGTH = 2048;
  private static final int VERSION = 1;
  private static final Pattern CANONICAL_BASE64URL =
      Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
  private static final UUID NO_REVISION = new UUID(0, 0);
  private final byte[] hmacKey;

  public ReaderCursorCodec(@Value("${novel.library.cursor.hmac-key:}") String encodedHmacKey) {
    this.hmacKey = decodeKey(encodedHmacKey);
  }

  public static Context manifestContext(UUID actorUserId, UUID workId) {
    return new Context(Kind.MANIFEST, actorUserId, workId, NO_REVISION);
  }

  public static Context contentContext(UUID actorUserId, UUID chapterId, UUID revisionId) {
    return new Context(Kind.CONTENT, actorUserId, chapterId, revisionId);
  }

  public static Context bookmarksContext(UUID actorUserId, UUID workId) {
    return new Context(Kind.BOOKMARKS, actorUserId, workId, NO_REVISION);
  }

  public static Context readingHistoryContext(UUID actorUserId) {
    return new Context(Kind.READING_HISTORY, actorUserId, actorUserId, NO_REVISION);
  }

  public void requireConfigured() {
    if (hmacKey == null) throw new SigningUnavailableException();
  }

  public String encode(Context context, int ordinal) {
    Objects.requireNonNull(context, "cursor context is required");
    if (context.kind() != Kind.MANIFEST && context.kind() != Kind.CONTENT) {
      throw new IllegalArgumentException("ordinal cursor requires a manifest or content context");
    }
    if (ordinal < 1) throw new IllegalArgumentException("cursor ordinal must be positive");
    requireConfigured();
    byte[] payload = payload(context, ordinal);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
  }

  public int decode(String token, Context expectedContext) {
    Objects.requireNonNull(expectedContext, "expected cursor context is required");
    if (expectedContext.kind() != Kind.MANIFEST && expectedContext.kind() != Kind.CONTENT) {
      throw new IllegalArgumentException("ordinal cursor requires a manifest or content context");
    }
    requireConfigured();
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new InvalidCursorException();
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2 || !CANONICAL_BASE64URL.matcher(token).matches()) {
      throw new InvalidCursorException();
    }

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
      UUID actorUserId = readUuid(input);
      UUID resourceId = readUuid(input);
      UUID revisionId = readUuid(input);
      int ordinal = input.readInt();
      if (version != VERSION
          || input.available() != 0
          || kind != expectedContext.kind()
          || !actorUserId.equals(expectedContext.actorUserId())
          || !resourceId.equals(expectedContext.resourceId())
          || !revisionId.equals(expectedContext.revisionId())
          || ordinal < 1) {
        throw new InvalidCursorException();
      }
      return ordinal;
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof InvalidCursorException invalidCursorException) {
        throw invalidCursorException;
      }
      throw new InvalidCursorException();
    }
  }

  public String encodeKeyset(Context context, KeysetPosition position) {
    Objects.requireNonNull(context, "cursor context is required");
    Objects.requireNonNull(position, "cursor position is required");
    if (context.kind() != Kind.BOOKMARKS && context.kind() != Kind.READING_HISTORY) {
      throw new IllegalArgumentException("keyset cursor requires a bookmark or history context");
    }
    requireConfigured();
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(KEYSET_PAYLOAD_LENGTH);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        output.writeByte(context.kind().code());
        writeUuid(output, context.actorUserId());
        writeUuid(output, context.resourceId());
        writeUuid(output, position.id());
        output.writeLong(position.timestamp().getEpochSecond());
        output.writeInt(position.timestamp().getNano());
      }
      byte[] payload = bytes.toByteArray();
      if (payload.length != KEYSET_PAYLOAD_LENGTH) {
        throw new IllegalStateException("Invalid Reader keyset cursor payload size");
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload, "keyset"));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode a Reader keyset cursor", exception);
    }
  }

  public KeysetPosition decodeKeyset(String token, Context expectedContext) {
    Objects.requireNonNull(expectedContext, "expected cursor context is required");
    if (expectedContext.kind() != Kind.BOOKMARKS
        && expectedContext.kind() != Kind.READING_HISTORY) {
      throw new IllegalArgumentException("keyset cursor requires a bookmark or history context");
    }
    requireConfigured();
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new InvalidCursorException();
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2 || !CANONICAL_BASE64URL.matcher(token).matches()) {
      throw new InvalidCursorException();
    }
    byte[] payload = decodeCanonical(parts[0]);
    byte[] signature = decodeCanonical(parts[1]);
    if (payload.length != KEYSET_PAYLOAD_LENGTH
        || signature.length != SIGNATURE_LENGTH
        || !MessageDigest.isEqual(sign(payload, "keyset"), signature)) {
      throw new InvalidCursorException();
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      int version = input.readUnsignedByte();
      Kind kind = Kind.fromCode(input.readUnsignedByte());
      UUID actorUserId = readUuid(input);
      UUID resourceId = readUuid(input);
      UUID positionId = readUuid(input);
      long epochSecond = input.readLong();
      int nano = input.readInt();
      if (version != VERSION
          || input.available() != 0
          || kind != expectedContext.kind()
          || !actorUserId.equals(expectedContext.actorUserId())
          || !resourceId.equals(expectedContext.resourceId())
          || nano < 0
          || nano > 999_999_999) {
        throw new InvalidCursorException();
      }
      return new KeysetPosition(Instant.ofEpochSecond(epochSecond, nano), positionId);
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof InvalidCursorException invalidCursorException) {
        throw invalidCursorException;
      }
      throw new InvalidCursorException();
    }
  }

  private static byte[] payload(Context context, int ordinal) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(PAYLOAD_LENGTH);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        output.writeByte(context.kind().code());
        writeUuid(output, context.actorUserId());
        writeUuid(output, context.resourceId());
        writeUuid(output, context.revisionId());
        output.writeInt(ordinal);
      }
      byte[] payload = bytes.toByteArray();
      if (payload.length != PAYLOAD_LENGTH) {
        throw new IllegalStateException("Invalid Reader cursor payload size");
      }
      return payload;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode a Reader cursor", exception);
    }
  }

  private byte[] sign(byte[] payload) {
    return signWithDomain(payload, "novel-reader-cursor-v1");
  }

  private byte[] sign(byte[] payload, String cursorType) {
    return signWithDomain(payload, "novel-reader-cursor-v1-" + cursorType);
  }

  private byte[] signWithDomain(byte[] payload, String domain) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      mac.update(domain.getBytes(StandardCharsets.UTF_8));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to sign a Reader cursor", exception);
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
    try {
      byte[] key = Base64.getUrlDecoder().decode(normalized);
      if (key.length != 32
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(normalized)) {
        throw new IllegalArgumentException("Reader cursor HMAC key must be canonical Base64URL");
      }
      return key;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Reader cursor HMAC key must be canonical Base64URL for 32 bytes", exception);
    }
  }

  private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
    output.writeLong(value.getMostSignificantBits());
    output.writeLong(value.getLeastSignificantBits());
  }

  private static UUID readUuid(DataInputStream input) throws IOException {
    return new UUID(input.readLong(), input.readLong());
  }

  public record Context(Kind kind, UUID actorUserId, UUID resourceId, UUID revisionId) {
    public Context {
      Objects.requireNonNull(kind, "cursor kind is required");
      Objects.requireNonNull(actorUserId, "cursor actor is required");
      Objects.requireNonNull(resourceId, "cursor resource is required");
      Objects.requireNonNull(revisionId, "cursor revision is required");
      if ((kind == Kind.MANIFEST || kind == Kind.BOOKMARKS || kind == Kind.READING_HISTORY)
          && !revisionId.equals(NO_REVISION)) {
        throw new IllegalArgumentException("manifest cursor cannot include a revision");
      }
      if (kind == Kind.CONTENT && revisionId.equals(NO_REVISION)) {
        throw new IllegalArgumentException("content cursor requires a revision");
      }
    }
  }

  public record KeysetPosition(Instant timestamp, UUID id) {
    public KeysetPosition {
      Objects.requireNonNull(timestamp, "cursor timestamp is required");
      Objects.requireNonNull(id, "cursor id is required");
    }
  }

  public enum Kind {
    MANIFEST(1),
    CONTENT(2),
    BOOKMARKS(3),
    READING_HISTORY(4);

    private final int code;

    Kind(int code) {
      this.code = code;
    }

    int code() {
      return code;
    }

    private static Kind fromCode(int code) {
      for (Kind kind : values()) {
        if (kind.code == code) return kind;
      }
      throw new InvalidCursorException();
    }
  }

  public static class InvalidCursorException extends RuntimeException {}

  public static class SigningUnavailableException extends RuntimeException {}
}
