package com.novelweb.identity.privacy;

import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Authenticated per-account preferences; optional choices never replace mandatory policy records.
 */
@RestController
@RequestMapping("/api/v1/me/privacy-preferences")
@Profile("postgres")
public class AccountPrivacyPreferencesController {

  private static final Set<String> BODY_FIELDS =
      Set.of("analyticsEnabled", "contentProviderSharingEnabled");
  private static final Pattern ETAG =
      Pattern.compile("\\\"privacy-preferences-v(0|[1-9][0-9]{0,18})\\\"");

  private final AccountPrivacyPreferencesRepository repository;

  public AccountPrivacyPreferencesController(AccountPrivacyPreferencesRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  public ResponseEntity<?> get(Authentication authentication, HttpServletRequest request) {
    UUID actorId = actorId(authentication);
    if (actorId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to view your optional privacy preferences.",
          request);
    }
    try {
      return preferences(HttpStatus.OK, repository.find(actorId));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PRIVACY_PREFERENCES_UNAVAILABLE",
          "Optional privacy preferences could not be loaded. Please try again.",
          request);
    }
  }

  @PutMapping
  public ResponseEntity<?> put(
      Authentication authentication,
      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) JsonNode body,
      HttpServletRequest request) {
    UUID actorId = actorId(authentication);
    if (actorId == null) {
      return problem(
          HttpStatus.UNAUTHORIZED,
          "UNAUTHENTICATED",
          "Sign in to change your optional privacy preferences.",
          request);
    }
    if (ifMatch == null || ifMatch.isBlank()) {
      return problem(
          HttpStatus.valueOf(428),
          "IF_MATCH_REQUIRED",
          "Reload your privacy preferences and send the current ETag in If-Match.",
          request);
    }
    Long expectedVersion = parseVersion(ifMatch);
    if (expectedVersion == null) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "IF_MATCH_INVALID",
          "If-Match must contain the strong privacy-preferences ETag.",
          request);
    }
    if (body == null || !body.isObject() || hasUnknownFields(body)) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PRIVACY_PREFERENCES_INVALID",
          "Provide only analyticsEnabled and contentProviderSharingEnabled as boolean values.",
          request);
    }
    JsonNode analyticsEnabled = body.get("analyticsEnabled");
    JsonNode contentProviderSharingEnabled = body.get("contentProviderSharingEnabled");
    if (analyticsEnabled == null
        || !analyticsEnabled.isBoolean()
        || contentProviderSharingEnabled == null
        || !contentProviderSharingEnabled.isBoolean()) {
      return problem(
          HttpStatus.BAD_REQUEST,
          "PRIVACY_PREFERENCES_INVALID",
          "Provide only analyticsEnabled and contentProviderSharingEnabled as boolean values.",
          request);
    }
    try {
      var updated =
          repository.update(
              actorId,
              analyticsEnabled.booleanValue(),
              contentProviderSharingEnabled.booleanValue(),
              expectedVersion);
      if (updated.isPresent()) return preferences(HttpStatus.OK, updated.orElseThrow());
      return preferences(HttpStatus.PRECONDITION_FAILED, repository.find(actorId));
    } catch (IllegalStateException exception) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PRIVACY_PREFERENCES_UNAVAILABLE",
          "Optional privacy preferences could not be saved. Please try again.",
          request);
    }
  }

  private static ResponseEntity<AccountPrivacyPreferencesRepository.Preferences> preferences(
      HttpStatus status, AccountPrivacyPreferencesRepository.Preferences preferences) {
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header(HttpHeaders.ETAG, etag(preferences.version()))
        .body(preferences);
  }

  private static boolean hasUnknownFields(JsonNode body) {
    for (String field : body.propertyNames()) {
      if (!BODY_FIELDS.contains(field)) return true;
    }
    return false;
  }

  private static Long parseVersion(String etag) {
    Matcher matcher = ETAG.matcher(etag);
    if (!matcher.matches()) return null;
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static String etag(long version) {
    return "\"privacy-preferences-v" + version + "\"";
  }

  private static UUID actorId(Authentication authentication) {
    if (authentication == null
        || !(authentication.getPrincipal() instanceof SessionService.SessionPrincipal principal)) {
      return null;
    }
    return principal.userId();
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    Object correlationId = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    problem.setProperty(
        "correlationId", correlationId instanceof String id ? id : UUID.randomUUID());
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
