package com.novelweb.translation.jobs;

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

/** Signs actor-bound keyset cursors for the translation job desk. */
@Service
@Profile("postgres")
public class TranslationJobCursorCodec {

  private static final int VERSION = 1;
  private static final int PAYLOAD_LENGTH = 45;
  private static final int SIGNATURE_LENGTH = 32;
  private static final int MAX_TOKEN_LENGTH = 256;
  private static final byte[] DOMAIN =
      "novel-web:translation-job-cursor:v1\0".getBytes(StandardCharsets.UTF_8);
  private static final Pattern CANONICAL_BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");

  private final byte[] hmacKey;

  public TranslationJobCursorCodec(
      @Value("${novel.library.cursor.hmac-key:}") String encodedHmacKey) {
    this.hmacKey = decodeKey(encodedHmacKey);
  }

  public void requireConfigured() {
    if (hmacKey == null) throw new SigningUnavailableException();
  }

  public String encode(UUID actorUserId, Position position) {
    Objects.requireNonNull(actorUserId, "actor user id is required");
    Objects.requireNonNull(position, "cursor position is required");
    requireConfigured();
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(PAYLOAD_LENGTH);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        writeUuid(output, actorUserId);
        output.writeLong(position.createdAt().getEpochSecond());
        output.writeInt(position.createdAt().getNano());
        writeUuid(output, position.jobId());
      }
      byte[] payload = bytes.toByteArray();
      if (payload.length != PAYLOAD_LENGTH) {
        throw new IllegalStateException("Invalid translation job cursor payload size");
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode a translation job cursor", exception);
    }
  }

  public Position decode(UUID expectedActorUserId, String token) {
    Objects.requireNonNull(expectedActorUserId, "actor user id is required");
    requireConfigured();
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new InvalidCursorException();
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2
        || !CANONICAL_BASE64URL.matcher(parts[0]).matches()
        || !CANONICAL_BASE64URL.matcher(parts[1]).matches()) {
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
      UUID actorUserId = readUuid(input);
      long epochSecond = input.readLong();
      int nano = input.readInt();
      UUID jobId = readUuid(input);
      if (version != VERSION
          || input.available() != 0
          || !actorUserId.equals(expectedActorUserId)
          || nano < 0
          || nano > 999_999_999) {
        throw new InvalidCursorException();
      }
      return new Position(Instant.ofEpochSecond(epochSecond, nano), jobId);
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof InvalidCursorException invalidCursorException) {
        throw invalidCursorException;
      }
      throw new InvalidCursorException();
    }
  }

  private byte[] sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      mac.update(DOMAIN);
      return mac.doFinal(payload);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to sign a translation job cursor", exception);
    }
  }

  private static byte[] decodeKey(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    try {
      byte[] key = Base64.getUrlDecoder().decode(normalized);
      if (key.length != 32
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(normalized)) {
        throw new IllegalArgumentException("Translation job cursor HMAC key must be 32 bytes");
      }
      return key;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Translation job cursor HMAC key must be canonical Base64URL for 32 bytes", exception);
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

  private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
    output.writeLong(value.getMostSignificantBits());
    output.writeLong(value.getLeastSignificantBits());
  }

  private static UUID readUuid(DataInputStream input) throws IOException {
    return new UUID(input.readLong(), input.readLong());
  }

  public record Position(Instant createdAt, UUID jobId) {
    public Position {
      Objects.requireNonNull(createdAt, "cursor timestamp is required");
      Objects.requireNonNull(jobId, "cursor job id is required");
    }
  }

  public static class InvalidCursorException extends RuntimeException {}

  public static class SigningUnavailableException extends RuntimeException {}
}
