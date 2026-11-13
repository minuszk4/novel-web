package com.novelweb.progression.cultivation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
}
