package com.novelweb.identity.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("postgres")
public class SessionCsrfTokenService {

    public static final String TOKEN_HEADER_NAME = "X-CSRF-Token";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final byte[] hmacKey;

    public SessionCsrfTokenService(@Value("${novel.security.csrf.hmac-key:}") String encodedHmacKey) {
        this.hmacKey = decodeKey(encodedHmacKey);
    }

    public boolean isConfigured() {
        return hmacKey != null;
    }

    public String tokenFor(String sessionToken) {
        if (hmacKey == null || !SessionService.isCanonicalSessionToken(sessionToken)) {
            return null;
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            byte[] digest = hmac.doFinal(sessionToken.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to derive a session CSRF token", exception);
        }
    }

    public boolean matches(String sessionToken, String candidate) {
        String expected = tokenFor(sessionToken);
        if (expected == null || candidate == null || !TOKEN_PATTERN.matcher(candidate).matches()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), candidate.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] decodeKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String value = rawKey.trim();
        if (!TOKEN_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("CSRF HMAC key must be canonical Base64URL for 32 bytes");
        }
        try {
            byte[] key = Base64.getUrlDecoder().decode(value);
            if (key.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(key).equals(value)) {
                throw new IllegalArgumentException("CSRF HMAC key must be canonical Base64URL for 32 bytes");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("CSRF HMAC key must be canonical Base64URL for 32 bytes", exception);
        }
    }
}
