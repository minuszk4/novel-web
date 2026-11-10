package com.novelweb.identity.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class GoogleCsrfTokenValidator {

    private static final int MAX_TOKEN_LENGTH = 512;

    private GoogleCsrfTokenValidator() {
    }

    public static void requireMatchingTokens(String cookieToken, String formToken) {
        if (cookieToken == null || formToken == null
                || cookieToken.isBlank() || formToken.isBlank()
                || cookieToken.length() > MAX_TOKEN_LENGTH || formToken.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidGoogleCredentialException();
        }

        byte[] cookieBytes = cookieToken.getBytes(StandardCharsets.UTF_8);
        byte[] formBytes = formToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(cookieBytes, formBytes)) {
            throw new InvalidGoogleCredentialException();
        }
    }
}
