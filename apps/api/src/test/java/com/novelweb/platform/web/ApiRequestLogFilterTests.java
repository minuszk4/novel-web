package com.novelweb.platform.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(OutputCaptureExtension.class)
class ApiRequestLogFilterTests {

  private static final String CORRELATION_ID = "bd5970d4-2d6a-4cbe-bd8a-529816df533e";

  @Test
  void logsRouteTemplateAndOperationalFieldsWithoutRequestSecrets(CapturedOutput output)
      throws Exception {
    ApiRequestLogFilter filter = new ApiRequestLogFilter();
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/works/private-work-id-73a8/reader-manifest");
    request.setServletPath("/api/v1/works/private-work-id-73a8/reader-manifest");
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.setQueryString("token=query-secret-a91f&email=reader@example.test");
    request.addHeader("Cookie", "SESSION=cookie-secret-b77e");
    request.addHeader("Authorization", "Bearer authorization-secret-40d1");
    request.addHeader("X-Correlation-Id", "attacker-correlation-id");
    request.setContent("private chapter body sentinel".getBytes(StandardCharsets.UTF_8));
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, CORRELATION_ID);

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) -> {
          servletRequest.setAttribute(
              HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
              "/api/v1/works/{workId}/reader-manifest");
          ((HttpServletResponse) servletResponse).setStatus(HttpServletResponse.SC_OK);
        });

    String logOutput = output.getAll();
    assertTrue(logOutput.contains("method=GET"));
    assertTrue(logOutput.contains("route=/api/v1/works/{workId}/reader-manifest"));
    assertTrue(logOutput.contains("status=200"));
    assertTrue(logOutput.contains("durationMs="));
    assertTrue(logOutput.contains("correlationId=" + CORRELATION_ID));
    assertFalse(logOutput.contains("private-work-id-73a8"));
    assertFalse(logOutput.contains("query-secret-a91f"));
    assertFalse(logOutput.contains("reader@example.test"));
    assertFalse(logOutput.contains("cookie-secret-b77e"));
    assertFalse(logOutput.contains("authorization-secret-40d1"));
    assertFalse(logOutput.contains("attacker-correlation-id"));
    assertFalse(logOutput.contains("private chapter body sentinel"));
  }

  @Test
  void usesUnmatchedLabelInsteadOfRawPathWhenNoHandlerPatternExists(CapturedOutput output)
      throws Exception {
    ApiRequestLogFilter filter = new ApiRequestLogFilter();
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/private-unmatched-path-19ef");
    request.setServletPath("/api/v1/private-unmatched-path-19ef");
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, CORRELATION_ID);
    response.setStatus(HttpServletResponse.SC_NOT_FOUND);

    filter.doFilter(request, response, (servletRequest, servletResponse) -> {});

    String logOutput = output.getAll();
    assertTrue(logOutput.contains("route=unmatched"));
    assertTrue(logOutput.contains("status=404"));
    assertFalse(logOutput.contains("private-unmatched-path-19ef"));
  }
}
