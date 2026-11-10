package com.novelweb.identity.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelweb.platform.security.ApiProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class SessionAuthenticationFilterTests {

  private static final String SESSION_TOKEN = "A".repeat(43);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void resolvesValidSessionIntoPrincipalAndSessionBoundCsrfRequestAttribute() throws Exception {
    SessionService sessionService = mock(SessionService.class);
    SessionCsrfTokenService csrfService = mock(SessionCsrfTokenService.class);
    UUID userId = UUID.randomUUID();
    SessionService.SessionPrincipal principal =
        new SessionService.SessionPrincipal(userId, Instant.now().plusSeconds(3600));
    when(sessionService.validateAndTouch(SESSION_TOKEN)).thenReturn(Optional.of(principal));
    when(csrfService.tokenFor(SESSION_TOKEN)).thenReturn("csrf-token");

    SessionAuthenticationFilter filter =
        new SessionAuthenticationFilter(sessionService, csrfService, new ApiProblemWriter());
    MockHttpServletRequest request = requestWithSession(SESSION_TOKEN);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<Authentication> reachedAuthentication = new AtomicReference<>();
    FilterChain chain =
        (servletRequest, servletResponse) ->
            reachedAuthentication.set(SecurityContextHolder.getContext().getAuthentication());

    filter.doFilter(request, response, chain);

    assertTrue(reachedAuthentication.get().isAuthenticated());
    assertEquals(principal, reachedAuthentication.get().getPrincipal());
    assertEquals(principal, request.getAttribute(SessionAuthenticationFilter.PRINCIPAL_ATTRIBUTE));
    assertEquals(
        "csrf-token", request.getAttribute(SessionAuthenticationFilter.CSRF_TOKEN_ATTRIBUTE));
    assertEquals(200, response.getStatus());
  }

  @Test
  void missingOrDuplicateSessionCookieDoesNotCreateAnAuthentication() throws Exception {
    SessionService sessionService = mock(SessionService.class);
    SessionAuthenticationFilter filter =
        new SessionAuthenticationFilter(
            sessionService, mock(SessionCsrfTokenService.class), new ApiProblemWriter());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(
        new Cookie(SessionCookieFactory.COOKIE_NAME, SESSION_TOKEN),
        new Cookie(SessionCookieFactory.COOKIE_NAME, "another-token"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<Authentication> reachedAuthentication = new AtomicReference<>();
    FilterChain chain =
        (servletRequest, servletResponse) ->
            reachedAuthentication.set(SecurityContextHolder.getContext().getAuthentication());

    filter.doFilter(request, response, chain);

    assertNull(reachedAuthentication.get());
    verify(sessionService, never()).validateAndTouch(SESSION_TOKEN);
    assertEquals(200, response.getStatus());
  }

  @Test
  void databaseFailureReturnsGenericServiceUnavailableProblem() throws Exception {
    SessionService sessionService = mock(SessionService.class);
    when(sessionService.validateAndTouch(SESSION_TOKEN))
        .thenThrow(new IllegalStateException("database detail"));
    SessionAuthenticationFilter filter =
        new SessionAuthenticationFilter(
            sessionService, mock(SessionCsrfTokenService.class), new ApiProblemWriter());
    MockHttpServletRequest request = requestWithSession(SESSION_TOKEN);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<Boolean> reachedChain = new AtomicReference<>(false);
    FilterChain chain = (servletRequest, servletResponse) -> reachedChain.set(true);

    filter.doFilter(request, response, chain);

    assertFalse(reachedChain.get());
    assertEquals(503, response.getStatus());
    assertTrue(response.getContentAsString().contains("SESSION_STORE_UNAVAILABLE"));
    assertFalse(response.getContentAsString().contains("database detail"));
  }

  private static MockHttpServletRequest requestWithSession(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(SessionCookieFactory.COOKIE_NAME, token));
    return request;
  }
}
