package com.novelweb.identity.session;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

public final class SessionCookieFactory {

    public static final String COOKIE_NAME = "__Host-novel_session";
    private SessionCookieFactory() {
    }

    public static ResponseCookie issue(String sessionToken, Duration maxAge) {
        if (!SessionService.isCanonicalSessionToken(sessionToken)
                || maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("A valid session token and positive lifetime are required");
        }
        return ResponseCookie.from(COOKIE_NAME, sessionToken)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .maxAge(maxAge)
                .build();
    }

    public static ResponseCookie clear() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
    }
}
