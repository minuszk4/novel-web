package com.novelweb.identity.session;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.novelweb.platform.security.ApiProblemWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Profile("postgres")
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String CSRF_TOKEN_ATTRIBUTE = SessionAuthenticationFilter.class.getName() + ".csrfToken";
    public static final String PRINCIPAL_ATTRIBUTE = SessionAuthenticationFilter.class.getName() + ".principal";

    private final SessionService sessionService;
    private final SessionCsrfTokenService csrfTokenService;
    private final ApiProblemWriter problemWriter;

    public SessionAuthenticationFilter(
            SessionService sessionService,
            SessionCsrfTokenService csrfTokenService,
            ApiProblemWriter problemWriter) {
        this.sessionService = sessionService;
        this.csrfTokenService = csrfTokenService;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String sessionToken = sessionCookie(request);
        if (sessionToken == null) {
            filterChain.doFilter(request, response);
            return;
        }

        final Optional<SessionService.SessionPrincipal> principal;
        try {
            principal = sessionService.validateAndTouch(sessionToken);
        } catch (IllegalStateException exception) {
            problemWriter.write(request, response, HttpStatus.SERVICE_UNAVAILABLE.value(),
                    HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), "SESSION_STORE_UNAVAILABLE",
                    "The application session could not be checked. Please try again.");
            return;
        }
        if (principal.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        SessionService.SessionPrincipal sessionPrincipal = principal.orElseThrow();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                sessionPrincipal, null, List.of()));
        SecurityContextHolder.setContext(securityContext);
        request.setAttribute(PRINCIPAL_ATTRIBUTE, sessionPrincipal);
        request.setAttribute(CSRF_TOKEN_ATTRIBUTE, csrfTokenService.tokenFor(sessionToken));
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
