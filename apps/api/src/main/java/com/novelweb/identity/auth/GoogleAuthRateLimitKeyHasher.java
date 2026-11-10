package com.novelweb.identity.auth;

import java.util.Base64;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class GoogleAuthRateLimitKeyHasher {

    private static final int REQUIRED_ADDRESS_LENGTH_V4 = 4;
    private static final int REQUIRED_ADDRESS_LENGTH_V6 = 16;
    private static final int REQUIRED_KEY_LENGTH = 32;
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private GoogleAuthRateLimitKeyHasher() {
    }

    static byte[] hash(byte[] clientAddress, String base64UrlKey) {
        if (clientAddress == null
                || (clientAddress.length != REQUIRED_ADDRESS_LENGTH_V4
                        && clientAddress.length != REQUIRED_ADDRESS_LENGTH_V6)) {
            throw new IllegalArgumentException("Client address must be an IPv4 or IPv6 address");
        }
        if (base64UrlKey == null || base64UrlKey.isBlank()) {
            throw new IllegalArgumentException("Google auth rate-limit key is not configured");
        }

        byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(base64UrlKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Google auth rate-limit key is invalid", exception);
        }
        if (key.length != REQUIRED_KEY_LENGTH) {
            throw new IllegalArgumentException("Google auth rate-limit key must decode to 32 bytes");
        }

        try {
            Mac hmac = Mac.getInstance(HMAC_ALGORITHM);
            hmac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return hmac.doFinal(clientAddress);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create Google auth rate-limit key", exception);
        }
    }
}
