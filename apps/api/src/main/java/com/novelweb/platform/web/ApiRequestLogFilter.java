package com.novelweb.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Emits bounded API request metadata without recording paths, query strings or credentials. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiRequestLogFilter extends OncePerRequestFilter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiRequestLogFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!request.getServletPath().startsWith("/api/")) {
      filterChain.doFilter(request, response);
      return;
    }

    long startedAtNanos = System.nanoTime();
    boolean requestFailed = false;
    try {
      filterChain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException | Error exception) {
      requestFailed = true;
      throw exception;
    } finally {
      int status = response.getStatus();
      if (requestFailed && status < HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
        status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
      }
      LOGGER.info(
          "API request completed; method={}, route={}, status={}, durationMs={}, correlationId={}",
          safeMethod(request.getMethod()),
          routePattern(request),
          status,
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos),
          correlationId(request));
    }
  }

  private static String safeMethod(String method) {
    return method != null && method.matches("[A-Z]{1,16}") ? method : "OTHER";
  }

  private static String routePattern(HttpServletRequest request) {
    Object value = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (!(value instanceof String pattern)
        || !pattern.startsWith("/api/")
        || pattern.length() > 256
        || pattern.indexOf('?') >= 0
        || pattern.indexOf('#') >= 0
        || pattern.chars().anyMatch(Character::isISOControl)) {
      return "unmatched";
    }
    return pattern;
  }

  private static String correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    if (!(value instanceof String id)) return "unavailable";
    try {
      return UUID.fromString(id).toString().equals(id) ? id : "unavailable";
    } catch (IllegalArgumentException exception) {
      return "unavailable";
    }
  }
}
