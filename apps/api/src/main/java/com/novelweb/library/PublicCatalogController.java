package com.novelweb.library;

import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Guest-readable, metadata-only public catalog; disabled until publication is approved. */
@RestController
@RequestMapping("/api/v1/catalog")
@Profile("postgres")
public class PublicCatalogController {

  private final PublicCatalogService catalogService;
  private final boolean enabled;

  public PublicCatalogController(
      PublicCatalogService catalogService,
      @Value("${novel.features.public-catalog-enabled:false}") boolean enabled) {
    this.catalogService = catalogService;
    this.enabled = enabled;
  }

  @GetMapping("/works")
  public ResponseEntity<?> listWorks(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String language,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") String limit,
      HttpServletRequest request) {
    if (!enabled) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PUBLIC_CATALOG_DISABLED",
          "The public catalog is not available.",
          request);
    }
    return ok(catalogService.list(q, language, cursor, parseLimit(limit)));
  }

  @ExceptionHandler(LibraryCursorCodec.InvalidCursorException.class)
  public ResponseEntity<ProblemDetail> invalidCursor(HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST,
        "INVALID_CURSOR",
        "The cursor is malformed or belongs to another catalog query.",
        request);
  }

  @ExceptionHandler(PublicCatalogService.InvalidCatalogQueryException.class)
  public ResponseEntity<ProblemDetail> invalidQuery(
      PublicCatalogService.InvalidCatalogQueryException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, "INVALID_CATALOG_QUERY", exception.getMessage(), request);
  }

  @ExceptionHandler(LibraryCursorCodec.SigningUnavailableException.class)
  public ResponseEntity<ProblemDetail> cursorUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CATALOG_CURSOR_UNAVAILABLE",
        "Catalog pagination is temporarily unavailable.",
        request);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ProblemDetail> catalogUnavailable(HttpServletRequest request) {
    return problem(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CATALOG_QUERY_UNAVAILABLE",
        "Catalog results could not be loaded. Please try again.",
        request);
  }

  private static int parseLimit(String rawLimit) {
    try {
      return Integer.parseInt(rawLimit);
    } catch (NumberFormatException exception) {
      throw new PublicCatalogService.InvalidCatalogQueryException(
          "limit must be an integer between 1 and 50");
    }
  }

  private static ResponseEntity<?> ok(Object body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }

  private static ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(status.getReasonPhrase());
    body.setProperty("code", code);
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    body.setProperty("correlationId", value instanceof String id ? id : "unavailable");
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(body);
  }
}
