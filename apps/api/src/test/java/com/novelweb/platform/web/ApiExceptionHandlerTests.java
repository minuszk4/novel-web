package com.novelweb.platform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTests {

  private static final String CORRELATION_ID = "3a4f87d2-50e1-4df8-8a68-c6c860ad6a22";
  private static final String PRIVATE_SENTINEL = "private-source-secret-7f0b29c43e";

  @Test
  void unexpectedExceptionLogsCorrelationAndTypeButNotExceptionContent(CapturedOutput output) {
    ApiExceptionHandler handler = new ApiExceptionHandler();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, CORRELATION_ID);

    var response =
        handler.handleUnexpected(
            new IllegalStateException("Provider token and chapter text: " + PRIVATE_SENTINEL),
            request);

    String logOutput = output.getAll();
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals("The request could not be completed.", response.getBody().getDetail());
    assertTrue(logOutput.contains(CORRELATION_ID));
    assertTrue(logOutput.contains("exceptionType=IllegalStateException"));
    assertFalse(logOutput.contains(PRIVATE_SENTINEL));
    assertFalse(logOutput.contains("Provider token and chapter text"));
  }
}
