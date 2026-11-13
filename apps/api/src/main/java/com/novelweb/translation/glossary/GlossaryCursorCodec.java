package com.novelweb.translation.glossary;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
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

/** Signs actor, filter and workspace-scope-bound keyset cursors for glossary catalog reads. */
@Service
@Profile("postgres")
public class GlossaryCursorCodec {

  private static final int VERSION = 1;
  private static final int PAYLOAD_LENGTH = 61;
  private static final int SIGNATURE_LENGTH = 32;
  private static final int MAX_TOKEN_LENGTH = 512;
  private static final UUID NO_LIBRARY = new UUID(0, 0);
  private static final byte[] DOMAIN =
      "novel-web:glossary-cursor:v1\0".getBytes(StandardCharsets.UTF_8);
  private static final Pattern CANONICAL_BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

  private final byte[] hmacKey;

  public GlossaryCursorCodec(@Value("${novel.library.cursor.hmac-key:}") String encodedHmacKey) {
    this.hmacKey = decodeKey(encodedHmacKey);
  }

  public void requireConfigured() {
    if (hmacKey == null) throw new SigningUnavailableException();
  }

  public String encode(CursorContext context, Position position) {
    Objects.requireNonNull(context, "cursor context is required");
    Objects.requireNonNull(position, "cursor position is required");
    requireConfigured();
    byte[] payload = payload(context, position);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
  }

  public Position decode(String token, CursorContext expectedContext) {
    Objects.requireNonNull(expectedContext, "expected cursor context is required");
    requireConfigured();
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new InvalidCursorException();
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2) throw new InvalidCursorException();
    byte[] decodedPayload = decodeCanonical(parts[0]);
    byte[] decodedSignature = decodeCanonical(parts[1]);
    if (decodedPayload.length != PAYLOAD_LENGTH
        || decodedSignature.length != SIGNATURE_LENGTH
        || !MessageDigest.isEqual(sign(decodedPayload), decodedSignature)) {
      throw new InvalidCursorException();
    }

    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(decodedPayload))) {
      int version = input.readUnsignedByte();
      byte[] filterHash = input.readNBytes(32);
      Instant createdAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
      UUID glossaryId = new UUID(input.readLong(), input.readLong());
      if (version != VERSION
          || input.available() != 0
          || !MessageDigest.isEqual(filterHash, expectedContext.filterHash())) {
        throw new InvalidCursorException();
      }
      return new Position(createdAt, glossaryId);
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof InvalidCursorException invalidCursorException) {
        throw invalidCursorException;
      }
      throw new InvalidCursorException();
    }
  }

  private static byte[] payload(CursorContext context, Position position) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(PAYLOAD_LENGTH);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        output.write(context.filterHash());
        output.writeLong(position.createdAt().getEpochSecond());
        output.writeInt(position.createdAt().getNano());
        output.writeLong(position.glossaryId().getMostSignificantBits());
        output.writeLong(position.glossaryId().getLeastSignificantBits());
      }
      byte[] result = bytes.toByteArray();
      if (result.length != PAYLOAD_LENGTH) {
        throw new IllegalStateException("Invalid glossary cursor payload size");
      }
      return result;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode a glossary cursor", exception);
    }
  }

  private byte[] sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      mac.update(DOMAIN);
      return mac.doFinal(payload);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to sign a glossary cursor", exception);
    }
  }

  private static byte[] decodeCanonical(String value) {
    if (!CANONICAL_BASE64URL.matcher(value).matches()) throw new InvalidCursorException();
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(value);
      if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
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
      byte[] decoded = Base64.getUrlDecoder().decode(normalized);
      if (decoded.length != 32
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(normalized)) {
        throw new IllegalArgumentException("Glossary cursor HMAC key must encode 32 bytes");
      }
      return decoded;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Glossary cursor HMAC key must encode 32 bytes", exception);
    }
  }

  public record CursorContext(
      UUID actorUserId,
      GlossaryMatcher.Scope scope,
      UUID libraryId,
      String normalizedQuery,
      String accessScopeFingerprint) {
    public CursorContext {
      Objects.requireNonNull(actorUserId, "actor user id is required");
      Objects.requireNonNull(normalizedQuery, "normalized query is required");
      Objects.requireNonNull(accessScopeFingerprint, "access fingerprint is required");
    }

    private byte[] filterHash() {
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update((byte) VERSION);
        updateUuid(digest, actorUserId);
        digest.update((byte) (scope == null ? 0 : scope.ordinal() + 1));
        updateUuid(digest, libraryId == null ? NO_LIBRARY : libraryId);
        updateString(digest, normalizedQuery);
        updateString(digest, accessScopeFingerprint);
        updateString(digest, "created_at_desc,id_desc");
        return digest.digest();
      } catch (GeneralSecurityException exception) {
        throw new IllegalStateException("SHA-256 is unavailable", exception);
      }
    }

    private static void updateUuid(MessageDigest digest, UUID value) {
      digest.update(
          ByteBuffer.allocate(16)
              .putLong(value.getMostSignificantBits())
              .putLong(value.getLeastSignificantBits())
              .array());
    }

    private static void updateString(MessageDigest digest, String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
    }
  }

  public record Position(Instant createdAt, UUID glossaryId) {
    public Position {
      Objects.requireNonNull(createdAt, "cursor timestamp is required");
      Objects.requireNonNull(glossaryId, "glossary id is required");
    }
  }

  public static class InvalidCursorException extends RuntimeException {}

  public static class SigningUnavailableException extends RuntimeException {}
}
