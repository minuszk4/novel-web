package com.novelweb.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicCatalogControllerTests {

  @Test
  void disabledCatalogFailsClosedWithoutQueryingTheRepository() {
    PublicCatalogService service = mock(PublicCatalogService.class);
    PublicCatalogRateLimiter rateLimiter = mock(PublicCatalogRateLimiter.class);
    PublicCatalogController controller = new PublicCatalogController(service, rateLimiter, false);
    HttpServletRequest request = new MockHttpServletRequest();

    var response = controller.listWorks(null, null, null, "20", request);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    verifyNoInteractions(service);
    verifyNoInteractions(rateLimiter);
  }

  @Test
  void doesNotQueryCatalogWhenRateLimitConfigurationIsMissing() {
    PublicCatalogService service = mock(PublicCatalogService.class);
    PublicCatalogRateLimiter rateLimiter = mock(PublicCatalogRateLimiter.class);
    when(rateLimiter.isConfigured()).thenReturn(false);
    PublicCatalogController controller = new PublicCatalogController(service, rateLimiter, true);
    HttpServletRequest request = new MockHttpServletRequest();

    var response = controller.listWorks(null, null, null, "20", request);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    verifyNoInteractions(service);
    verify(rateLimiter).isConfigured();
  }

  @Test
  void rateLimitedCatalogReturnsRetryAfterAndDoesNotQueryTheRepository() {
    PublicCatalogService service = mock(PublicCatalogService.class);
    PublicCatalogRateLimiter rateLimiter = mock(PublicCatalogRateLimiter.class);
    when(rateLimiter.isConfigured()).thenReturn(true);
    when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.any()))
        .thenReturn(new PublicCatalogRateLimiter.Decision(false, 7));
    PublicCatalogController controller = new PublicCatalogController(service, rateLimiter, true);
    HttpServletRequest request = new MockHttpServletRequest();

    var response = controller.listWorks(null, null, null, "20", request);

    assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
    assertEquals("7", response.getHeaders().getFirst("Retry-After"));
    assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
    verify(rateLimiter).tryAcquire(request);
    verifyNoInteractions(service);
  }

  @Test
  void limiterStorageFailureFailsClosedWithoutQueryingTheRepository() {
    PublicCatalogService service = mock(PublicCatalogService.class);
    PublicCatalogRateLimiter rateLimiter = mock(PublicCatalogRateLimiter.class);
    when(rateLimiter.isConfigured()).thenReturn(true);
    when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalStateException("private storage details"));
    PublicCatalogController controller = new PublicCatalogController(service, rateLimiter, true);
    HttpServletRequest request = new MockHttpServletRequest();

    var response = controller.listWorks(null, null, null, "20", request);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    verifyNoInteractions(service);
    verify(rateLimiter).tryAcquire(request);
  }
}
