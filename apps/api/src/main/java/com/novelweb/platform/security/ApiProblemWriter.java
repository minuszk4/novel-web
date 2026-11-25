package com.novelweb.platform.security;

import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class ApiProblemWriter {

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String title,
      String code,
      String detail)
      throws IOException {
    Object correlation = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    String correlationId = correlation instanceof String value ? value : "unavailable";
    response.setStatus(status);
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\""
                + title
                + "\",\"status\":"
                + status
                + ",\"detail\":\""
                + detail
                + "\",\"code\":\""
                + code
                + "\",\"correlationId\":\""
                + correlationId
                + "\"}");
  }
}
