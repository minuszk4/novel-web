package com.novelweb.progression.cultivation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class CultivationReadingSessionControllerTests {

  @Test
  void rejectsStartHeartbeatAndCloseUntilGlobalCultivationFlagIsEnabled() {
    CultivationReadingSessionService service = mock(CultivationReadingSessionService.class);
    PolicyService policyService = mock(PolicyService.class);
    CultivationReadingSessionController controller =
        new CultivationReadingSessionController(service, policyService, false);
    Authentication authentication =
        new UsernamePasswordAuthenticationToken(
            new SessionService.SessionPrincipal(
                UUID.randomUUID(), Instant.parse("2027-01-01T00:00:00Z")),
            "unused");
    HttpServletRequest request = new MockHttpServletRequest();
    UUID sessionId = UUID.randomUUID();

    var start = controller.start(authentication, null, "start-idempotency-0001", request);
    var heartbeat =
        controller.heartbeat(
            authentication, sessionId, null, "heartbeat-idempotency-0001", request);
    var close = controller.close(authentication, sessionId, request);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, start.getStatusCode());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, heartbeat.getStatusCode());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, close.getStatusCode());
    assertEquals(
        "CULTIVATION_NOT_ENABLED", ((ProblemDetail) start.getBody()).getProperties().get("code"));
    verifyNoInteractions(service, policyService);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void unexpectedReadingSessionFailureDoesNotWriteExceptionContentToLogs(CapturedOutput output) {
    CultivationReadingSessionController controller =
        new CultivationReadingSessionController(
            mock(CultivationReadingSessionService.class), mock(PolicyService.class), true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    String correlationId = "446a3aed-005d-417c-a7d6-808e8b8d5812";
    String privateSentinel = "private-reading-token-9c43fe21";
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, correlationId);

    var response =
        controller.unavailable(
            new IllegalStateException("Database diagnostic included " + privateSentinel), request);

    String logOutput = output.getAll();
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertEquals(
        "The reading session could not be processed. Please try again.",
        ((ProblemDetail) response.getBody()).getDetail());
    assertTrue(logOutput.contains(correlationId));
    assertTrue(logOutput.contains("exceptionType=IllegalStateException"));
    assertFalse(logOutput.contains(privateSentinel));
    assertFalse(logOutput.contains("Database diagnostic included"));
  }
}
