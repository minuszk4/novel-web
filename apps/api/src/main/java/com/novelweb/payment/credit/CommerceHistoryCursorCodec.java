package com.novelweb.payment.credit;

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

/** Signs actor-, list- and filter-bound cursors for private commerce history. */
@Service
@Profile("postgres")
public class CommerceHistoryCursorCodec {

  private static final int VERSION = 1;
  private static final int SIGNATURE_LENGTH = 32;
  private static final int MAX_TOKEN_LENGTH = 256;
  private static final int MAX_FILTER_LENGTH = 16;
  private static final byte[] DOMAIN =
      "novel-web:commerce-history-cursor:v1\0".getBytes(StandardCharsets.UTF_8);
  private static final Pattern CANONICAL_BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Pattern FILTER = Pattern.compile("[A-Z_]{0,16}");

  private final byte[] hmacKey;

  public CommerceHistoryCursorCodec(
      @Value("${novel.library.cursor.hmac-key:}") String encodedHmacKey) {
    this.hmacKey = decodeKey(encodedHmacKey);
  }

  public void requireConfigured() {
    if (hmacKey == null) throw new SigningUnavailableException();
  }

  public String encode(UUID actorId, Scope scope, String filter, Position position) {
    Objects.requireNonNull(actorId, "actor id is required");
    Objects.requireNonNull(scope, "history scope is required");
    Objects.requireNonNull(position, "history position is required");
    String normalizedFilter = filter == null ? "" : filter;
    if (!FILTER.matcher(normalizedFilter).matches())
      throw new IllegalArgumentException("invalid filter");
    requireConfigured();
    try {
      byte[] filterBytes = normalizedFilter.getBytes(StandardCharsets.US_ASCII);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(47 + filterBytes.length);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeByte(VERSION);
        writeUuid(output, actorId);
        output.writeByte(scope.code());
        output.writeByte(filterBytes.length);
        output.write(filterBytes);
        output.writeLong(position.createdAt().getEpochSecond());
        output.writeInt(position.createdAt().getNano());
        writeUuid(output, position.itemId());
      }
      byte[] payload = bytes.toByteArray();
      return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to encode a commerce history cursor", exception);
    }
  }

  public Position decode(UUID actorId, Scope expectedScope, String expectedFilter, String token) {
    Objects.requireNonNull(actorId, "actor id is required");
    Objects.requireNonNull(expectedScope, "history scope is required");
    String filter = expectedFilter == null ? "" : expectedFilter;
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
    if (payload.length < 47
        || payload.length > 47 + MAX_FILTER_LENGTH
        || signature.length != SIGNATURE_LENGTH
        || !MessageDigest.isEqual(sign(payload), signature)) {
      throw new InvalidCursorException();
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      int version = input.readUnsignedByte();
      UUID cursorActorId = readUuid(input);
      int scopeCode = input.readUnsignedByte();
      int filterLength = input.readUnsignedByte();
      if (filterLength > MAX_FILTER_LENGTH || input.available() != 28 + filterLength) {
        throw new InvalidCursorException();
      }
      byte[] filterBytes = input.readNBytes(filterLength);
      String cursorFilter = new String(filterBytes, StandardCharsets.US_ASCII);
      long epochSecond = input.readLong();
      int nano = input.readInt();
      UUID itemId = readUuid(input);
      if (version != VERSION
          || input.available() != 0
          || !cursorActorId.equals(actorId)
          || scopeCode != expectedScope.code()
          || !cursorFilter.equals(filter)
          || !FILTER.matcher(cursorFilter).matches()
          || nano < 0
          || nano > 999_999_999) {
        throw new InvalidCursorException();
      }
      return new Position(Instant.ofEpochSecond(epochSecond, nano), itemId);
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
      throw new IllegalStateException("Unable to sign a commerce history cursor", exception);
    }
  }

  private static byte[] decodeKey(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    try {
      byte[] key = Base64.getUrlDecoder().decode(normalized);
      if (key.length != 32
          || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(normalized)) {
        throw new IllegalArgumentException("History cursor HMAC key must be 32 bytes");
      }
      return key;
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "History cursor HMAC key must be canonical Base64URL for 32 bytes", exception);
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

  public enum Scope {
    PAYMENT_ORDERS(1),
    CHAPTER_PURCHASES(2);

    private final int code;

    Scope(int code) {
      this.code = code;
    }

    private int code() {
      return code;
    }
  }

  public record Position(Instant createdAt, UUID itemId) {
    public Position {
      Objects.requireNonNull(createdAt, "history timestamp is required");
      Objects.requireNonNull(itemId, "history item id is required");
    }
  }

  public static class InvalidCursorException extends RuntimeException {}

  public static class SigningUnavailableException extends RuntimeException {}
}
