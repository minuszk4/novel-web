package com.novelweb.identity.session;

import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("postgres")
public class SessionLogoutController {

  private final SessionService sessionService;

  public SessionLogoutController(SessionService sessionService) {
    this.sessionService = sessionService;
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String sessionToken = sessionCookie(request);
    if (SessionService.isCanonicalSessionToken(sessionToken)) {
      try {
        sessionService.revoke(sessionToken);
      } catch (IllegalStateException exception) {
        return problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SESSION_STORE_UNAVAILABLE",
            "The application session could not be revoked. Please try again.",
            correlationId(request));
      }
    }

    ResponseCookie clearedCookie = SessionCookieFactory.clear();
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, clearedCookie.toString())
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .build();
  }

  private static String sessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    String token = null;
    for (Cookie cookie : cookies) {
      if (SessionCookieFactory.COOKIE_NAME.equals(cookie.getName())) {
        if (token != null) {
          return null;
        }
        token = cookie.getValue();
      }
    }
    return token;
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId.toString());
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }

  private static UUID correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    if (value instanceof String id) {
      try {
        return UUID.fromString(id);
      } catch (IllegalArgumentException ignored) {
        // Generate a fallback correlation ID when normal request filters are not present.
      }
    }
    return UUID.randomUUID();
  }
}
