package com.novelweb.progression.cultivation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Signs account-bound positions for the private daily cultivation activity feed. */
@Service
@Profile("postgres")
public class CultivationActivityCursorCodec {

  private static final byte VERSION = 1;
  private static final int PAYLOAD_LENGTH = 25;
  private static final int SIGNATURE_LENGTH = 32;
  private static final int MAX_TOKEN_LENGTH = 256;
  private static final String DOMAIN = "novel-cultivation-activity-cursor-v1";
  private static final Pattern CANONICAL_BASE64URL =
      Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

  private final byte[] hmacKey;

  public CultivationActivityCursorCodec(
      @Value("${novel.library.cursor.hmac-key:}") String encodedHmacKey) {
    this.hmacKey = decodeKey(encodedHmacKey);
  }

  public String encode(UUID actorUserId, LocalDate beforeDate) {
    if (actorUserId == null || beforeDate == null) {
      throw new IllegalArgumentException("activity cursor actor and date are required");
    }
    requireConfigured();
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(PAYLOAD_LENGTH);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        output.writeLong(actorUserId.getMostSignificantBits());
        output.writeLong(actorUserId.getLeastSignificantBits());
        output.writeLong(beforeDate.toEpochDay());
      }
      byte[] payload = bytes.toByteArray();
      if (payload.length != PAYLOAD_LENGTH) {
        throw new IllegalStateException("Invalid cultivation activity cursor payload size");
      }
      return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode a cultivation activity cursor", exception);
    }
  }

  public LocalDate decode(String token, UUID expectedActorUserId) {
    if (expectedActorUserId == null) {
      throw new IllegalArgumentException("activity cursor actor is required");
    }
    requireConfigured();
    if (token == null
        || token.isBlank()
        || token.length() > MAX_TOKEN_LENGTH
        || !CANONICAL_BASE64URL.matcher(token).matches()) {
      throw new InvalidCursorException();
    }
    String[] parts = token.split("\\.", -1);
    byte[] payload = decodeCanonical(parts[0]);
    byte[] signature = decodeCanonical(parts[1]);
    if (payload.length != PAYLOAD_LENGTH
        || signature.length != SIGNATURE_LENGTH
        || !MessageDigest.isEqual(sign(payload), signature)) {
      throw new InvalidCursorException();
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      int version = input.readUnsignedByte();
      UUID actorUserId = new UUID(input.readLong(), input.readLong());
      long epochDay = input.readLong();
      if (version != VERSION
          || input.available() != 0
          || !actorUserId.equals(expectedActorUserId)) {
        throw new InvalidCursorException();
      }
      return LocalDate.ofEpochDay(epochDay);
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof InvalidCursorException invalidCursorException) {
        throw invalidCursorException;
      }
      throw new InvalidCursorException();
    }
  }

  public void requireConfigured() {
    if (hmacKey == null) throw new SigningUnavailableException();
  }

  private byte[] sign(byte[] payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      mac.update(DOMAIN.getBytes(StandardCharsets.UTF_8));
      return mac.doFinal(payload);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to sign a cultivation activity cursor", exception);
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
        throw new IllegalArgumentException("Cursor HMAC key must be canonical Base64URL");
      }
      return key;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Cultivation activity cursor HMAC key must be canonical Base64URL for 32 bytes",
          exception);
    }
  }

  public static class InvalidCursorException extends RuntimeException {}

  public static class SigningUnavailableException extends RuntimeException {}
}
