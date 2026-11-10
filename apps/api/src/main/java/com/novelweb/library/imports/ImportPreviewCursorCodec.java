package com.novelweb.library.imports;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Signs preview offsets to one owner, import and immutable preview revision. */
@Service
@Profile("postgres")
public class ImportPreviewCursorCodec {

    private static final int PAYLOAD_LENGTH = 69;
    private static final int SIGNATURE_LENGTH = 32;
    private static final int MAX_TOKEN_LENGTH = 2048;

    private final byte[] hmacKey;

    public ImportPreviewCursorCodec(@Value("${novel.library.cursor.hmac-key:}") String encodedHmacKey) {
        this.hmacKey = decodeKey(encodedHmacKey);
    }

    public String encode(UUID ownerId, UUID importId, byte[] etag, int offset) {
        if (offset < 1 || etag == null || etag.length != 32) throw new IllegalArgumentException();
        requireConfigured();
        byte[] payload;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(PAYLOAD_LENGTH);
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(1);
            writeUuid(output, ownerId);
            writeUuid(output, importId);
            output.write(etag);
            output.writeInt(offset);
            payload = bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode an import preview cursor", exception);
        }
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        if (token.length() > MAX_TOKEN_LENGTH) throw new IllegalStateException("Import preview cursor is too large");
        return token;
    }

    public int decode(String token, UUID ownerId, UUID importId, byte[] etag, int chapterCount) {
        requireConfigured();
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) throw new InvalidCursorException();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new InvalidCursorException();
        byte[] payload = decodeCanonical(parts[0]);
        byte[] signature = decodeCanonical(parts[1]);
        if (payload.length != PAYLOAD_LENGTH || signature.length != SIGNATURE_LENGTH
                || !MessageDigest.isEqual(sign(payload), signature)) throw new InvalidCursorException();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = input.readUnsignedByte();
            UUID tokenOwner = readUuid(input);
            UUID tokenImport = readUuid(input);
            byte[] tokenEtag = input.readNBytes(32);
            int offset = input.readInt();
            if (version != 1 || input.available() != 0 || !ownerId.equals(tokenOwner)
                    || !importId.equals(tokenImport) || !MessageDigest.isEqual(etag, tokenEtag)
                    || offset < 1 || offset >= chapterCount) throw new InvalidCursorException();
            return offset;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof InvalidCursorException invalid) throw invalid;
            throw new InvalidCursorException();
        }
    }

    private void requireConfigured() {
        if (hmacKey == null) throw new SigningUnavailableException();
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign an import preview cursor", exception);
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
        try {
            byte[] key = Base64.getUrlDecoder().decode(value.trim());
            if (key.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(value.trim())) {
                throw new IllegalArgumentException("Library cursor HMAC key must be canonical Base64URL for 32 bytes");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Library cursor HMAC key must be canonical Base64URL for 32 bytes", exception);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    public static class InvalidCursorException extends RuntimeException { }

    public static class SigningUnavailableException extends RuntimeException { }
}
