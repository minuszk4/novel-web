package com.novelweb.identity.session;

import com.novelweb.payment.momo.MomoTestIpnController;
import com.novelweb.platform.security.ApiProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("postgres")
public class SessionBoundCsrfFilter extends OncePerRequestFilter {

  private static final String GOOGLE_CREDENTIAL_CALLBACK = "/api/v1/auth/google/credential";
  private static final String MOMO_TEST_IPN_CALLBACK = MomoTestIpnController.PATH;
  private static final String LOGOUT = "/api/v1/auth/logout";
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

  private final SessionCsrfTokenService csrfTokenService;
  private final ApiProblemWriter problemWriter;

  public SessionBoundCsrfFilter(
      SessionCsrfTokenService csrfTokenService, ApiProblemWriter problemWriter) {
    this.csrfTokenService = csrfTokenService;
    this.problemWriter = problemWriter;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (SAFE_METHODS.contains(request.getMethod())
        || GOOGLE_CREDENTIAL_CALLBACK.equals(request.getRequestURI())
        || MOMO_TEST_IPN_CALLBACK.equals(request.getRequestURI())) {
      filterChain.doFilter(request, response);
      return;
    }

    String sessionToken = sessionCookie(request);
    if (LOGOUT.equals(request.getRequestURI())
        && !SessionService.isCanonicalSessionToken(sessionToken)) {
      filterChain.doFilter(request, response);
      return;
    }
    String csrfToken = request.getHeader(SessionCsrfTokenService.TOKEN_HEADER_NAME);
    if (!csrfTokenService.matches(sessionToken, csrfToken)) {
      problemWriter.write(
          request,
          response,
          HttpServletResponse.SC_FORBIDDEN,
          "Forbidden",
          "CSRF_FAILED",
          "The request could not be verified. Refresh the security token and retry.");
      return;
    }
    filterChain.doFilter(request, response);
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
}
