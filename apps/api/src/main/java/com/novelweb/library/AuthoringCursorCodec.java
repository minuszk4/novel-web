package com.novelweb.library;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Issues actor- and manuscript-bound cursors for private authoring outline pages. */
@Service
@Profile("postgres")
public class AuthoringCursorCodec {

  private static final int VERSION = 1;
  private static final int PAYLOAD_LENGTH = 37;
  private static final int SIGNATURE_LENGTH = 32;
  private static final int MAX_TOKEN_LENGTH = 2048;
  private static final String SIGNING_DOMAIN = "novel-authoring-outline-cursor-v1";
  private static final Pattern CANONICAL_BASE64URL =
      Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

  private final byte[] hmacKey;

  public AuthoringCursorCodec(@Value("${novel.library.cursor.hmac-key:}") String encodedHmacKey) {
    this.hmacKey = decodeKey(encodedHmacKey);
  }

  public void requireConfigured() {
    if (hmacKey == null) throw new SigningUnavailableException();
  }

  public String encode(UUID actorUserId, UUID workId, int afterOrdinal) {
    Objects.requireNonNull(actorUserId, "cursor actor is required");
    Objects.requireNonNull(workId, "cursor work is required");
    if (afterOrdinal < 1) throw new IllegalArgumentException("cursor ordinal must be positive");
    requireConfigured();
    byte[] payload = payload(actorUserId, workId, afterOrdinal);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        + "."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
  }

  public int decode(String token, UUID expectedActorUserId, UUID expectedWorkId) {
    Objects.requireNonNull(expectedActorUserId, "expected cursor actor is required");
    Objects.requireNonNull(expectedWorkId, "expected cursor work is required");
    requireConfigured();
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new InvalidCursorException();
    }
    if (!CANONICAL_BASE64URL.matcher(token).matches()) throw new InvalidCursorException();
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2) throw new InvalidCursorException();

    byte[] decodedPayload = decodeCanonical(parts[0]);
    byte[] signature = decodeCanonical(parts[1]);
    if (decodedPayload.length != PAYLOAD_LENGTH
        || signature.length != SIGNATURE_LENGTH
        || !MessageDigest.isEqual(sign(decodedPayload), signature)) {
      throw new InvalidCursorException();
    }

    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(decodedPayload))) {
      int version = input.readUnsignedByte();
      UUID actorUserId = readUuid(input);
      UUID workId = readUuid(input);
      int afterOrdinal = input.readInt();
      if (version != VERSION
          || input.available() != 0
          || !actorUserId.equals(expectedActorUserId)
          || !workId.equals(expectedWorkId)
          || afterOrdinal < 1) {
        throw new InvalidCursorException();
      }
      return afterOrdinal;
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof InvalidCursorException invalidCursorException) {
        throw invalidCursorException;
      }
      throw new InvalidCursorException();
    }
  }

  private static byte[] payload(UUID actorUserId, UUID workId, int afterOrdinal) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(PAYLOAD_LENGTH);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        writeUuid(output, actorUserId);
        writeUuid(output, workId);
        output.writeInt(afterOrdinal);
      }
      byte[] payload = bytes.toByteArray();
      if (payload.length != PAYLOAD_LENGTH) {
        throw new IllegalStateException("Invalid authoring cursor payload size");
      }
      return payload;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode an authoring cursor", exception);
    }
  }

  private byte[] sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      mac.update(SIGNING_DOMAIN.getBytes(StandardCharsets.UTF_8));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to sign an authoring cursor", exception);
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
      if (key.length != SIGNATURE_LENGTH
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(normalized)) {
        throw new IllegalArgumentException("Authoring cursor HMAC key must be canonical Base64URL");
      }
      return key;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Authoring cursor HMAC key must be canonical Base64URL for 32 bytes", exception);
    }
  }

  private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
    output.writeLong(value.getMostSignificantBits());
    output.writeLong(value.getLeastSignificantBits());
  }

  private static UUID readUuid(DataInputStream input) throws IOException {
    return new UUID(input.readLong(), input.readLong());
  }

  public static class InvalidCursorException extends RuntimeException {}

  public static class SigningUnavailableException extends RuntimeException {}
}
