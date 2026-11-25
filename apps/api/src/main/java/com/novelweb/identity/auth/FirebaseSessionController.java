package com.novelweb.identity.auth;

import com.novelweb.identity.session.SessionCookieFactory;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.security.CorsProperties;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/auth/firebase")
@Profile("postgres")
public class FirebaseSessionController {

  private static final int MAX_JSON_BODY_BYTES = 24 * 1024;
  private static final int MAX_ID_TOKEN_LENGTH = 16_384;
  private static final String SESSION_COOKIE = SessionCookieFactory.COOKIE_NAME;

  private final ObjectMapper objectMapper;
  private final GoogleCredentialVerificationService verificationService;
  private final GoogleIdentityService identityService;
  private final SessionService sessionService;
  private final CorsProperties corsProperties;
  private final Duration absoluteSessionTtl;

  public FirebaseSessionController(
      ObjectMapper objectMapper,
      GoogleCredentialVerificationService verificationService,
      GoogleIdentityService identityService,
      SessionService sessionService,
      CorsProperties corsProperties,
      @Value("${novel.identity.session.absolute-ttl:30d}") Duration absoluteSessionTtl) {
    this.objectMapper = objectMapper;
    this.verificationService = verificationService;
    this.identityService = identityService;
    this.sessionService = sessionService;
    this.corsProperties = corsProperties;
    this.absoluteSessionTtl = absoluteSessionTtl;
  }

  @PostMapping("/session")
  public ResponseEntity<?> createSession(HttpServletRequest servletRequest) {
    UUID correlationId = correlationId(servletRequest);
    if (!hasExpectedOrigin(servletRequest)) {
      return problem(
          HttpStatus.FORBIDDEN,
          "INVALID_ORIGIN",
          "The sign-in request could not be verified.",
          correlationId);
    }
    if (!isJson(servletRequest.getContentType())) {
      return problem(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "UNSUPPORTED_MEDIA_TYPE",
          "The sign-in request could not be processed.",
          correlationId);
    }
    if (servletRequest.getContentLengthLong() > MAX_JSON_BODY_BYTES) {
      return problem(
          HttpStatus.CONTENT_TOO_LARGE,
          "REQUEST_TOO_LARGE",
          "The sign-in request could not be processed.",
          correlationId);
    }

    final FirebaseSessionRequest boundedRequest;
    try {
      byte[] body = servletRequest.getInputStream().readNBytes(MAX_JSON_BODY_BYTES + 1);
      if (body.length == 0 || body.length > MAX_JSON_BODY_BYTES) {
        return problem(
            body.length > MAX_JSON_BODY_BYTES
                ? HttpStatus.CONTENT_TOO_LARGE
                : HttpStatus.BAD_REQUEST,
            body.length > MAX_JSON_BODY_BYTES ? "REQUEST_TOO_LARGE" : "INVALID_REQUEST",
            "The sign-in request could not be processed.",
            correlationId);
      }
      boundedRequest = objectMapper.readValue(body, FirebaseSessionRequest.class);
    } catch (IOException | JacksonException exception) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_REQUEST",
          "The sign-in request could not be processed.",
          correlationId);
    }
    if (boundedRequest == null
        || boundedRequest.idToken() == null
        || boundedRequest.idToken().isBlank()
        || boundedRequest.idToken().length() > MAX_ID_TOKEN_LENGTH) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "INVALID_REQUEST",
          "The sign-in request could not be processed.",
          correlationId);
    }

    try {
      GoogleCredentialVerificationService.VerifiedGoogleSignIn verified =
          verificationService.verify(boundedRequest.idToken(), boundedRequest.state());
      GoogleIdentityService.IdentityAccount account =
          identityService.findOrCreate(verified.identity());
      SessionService.IssuedSession session = issueSession(servletRequest, account.userId());
      ResponseCookie cookie =
          SessionCookieFactory.issue(session.cookieValue(), cookieLifetime(session.expiresAt()));

      return ResponseEntity.ok()
          .header(HttpHeaders.SET_COOKIE, cookie.toString())
          .cacheControl(CacheControl.noStore())
          .header(HttpHeaders.PRAGMA, "no-cache")
          .header("Referrer-Policy", "no-referrer")
          .body(new FirebaseSessionResponse(SafeReturnPath.sanitize(verified.returnTo())));
    } catch (InvalidGoogleCredentialException
        | GoogleAccountLinkRequiredException
        | UnavailableGoogleIdentityException exception) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "SIGN_IN_FAILED",
          "The sign-in could not be completed. Start again and retry.",
          correlationId);
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "AUTH_PROVIDER_UNAVAILABLE",
          "Sign-in is not available right now. Please try again.",
          correlationId);
    }
  }

  private Duration cookieLifetime(Instant expiresAt) {
    Duration remainingAbsoluteLifetime = Duration.between(Instant.now(), expiresAt);
    if (remainingAbsoluteLifetime.isZero() || remainingAbsoluteLifetime.isNegative()) {
      throw new IllegalStateException("The new session is already expired");
    }
    return absoluteSessionTtl.compareTo(remainingAbsoluteLifetime) <= 0
        ? absoluteSessionTtl
        : remainingAbsoluteLifetime;
  }

  private SessionService.IssuedSession issueSession(HttpServletRequest request, UUID userId) {
    String existingToken = sessionCookie(request);
    Optional<SessionService.SessionPrincipal> existingPrincipal =
        existingToken == null ? Optional.empty() : sessionService.validateAndTouch(existingToken);
    if (existingPrincipal.isPresent() && existingPrincipal.orElseThrow().userId().equals(userId)) {
      return sessionService
          .rotate(existingToken)
          .or(() -> sessionService.create(userId))
          .orElseThrow(InvalidGoogleCredentialException::new);
    }
    if (existingPrincipal.isPresent()) {
      sessionService.revoke(existingToken);
    }
    return sessionService.create(userId).orElseThrow(InvalidGoogleCredentialException::new);
  }

  private boolean hasExpectedOrigin(HttpServletRequest request) {
    var origins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
    return origins.size() == 1
        && corsProperties.allowedOrigins() != null
        && corsProperties.allowedOrigins().contains(origins.getFirst());
  }

  private static boolean isJson(String contentType) {
    if (contentType == null) {
      return false;
    }
    try {
      MediaType parsed = MediaType.parseMediaType(contentType);
      return parsed.isCompatibleWith(MediaType.APPLICATION_JSON)
          && (parsed.getCharset() == null || StandardCharsets.UTF_8.equals(parsed.getCharset()));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static String sessionCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    String value = null;
    for (Cookie cookie : cookies) {
      if (SESSION_COOKIE.equals(cookie.getName())) {
        if (value != null) {
          return null;
        }
        value = cookie.getValue();
      }
    }
    return value;
  }

  private ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, UUID correlationId) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId.toString());
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header("Referrer-Policy", "no-referrer")
        .body(problem);
  }

  private static UUID correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    if (value instanceof String id) {
      try {
        return UUID.fromString(id);
      } catch (IllegalArgumentException ignored) {
        // Use a generated identifier if a different filter supplied invalid context.
      }
    }
    return UUID.randomUUID();
  }

  public record FirebaseSessionRequest(String idToken, String state) {}

  public record FirebaseSessionResponse(String returnTo) {}
}
