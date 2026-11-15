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

class CultivationControllerTests {

  @Test
  void hidesProfileAndSettingsUntilGlobalCultivationFlagIsEnabled() {
    CultivationService service = mock(CultivationService.class);
    CultivationActivityService activityService = mock(CultivationActivityService.class);
    PolicyService policyService = mock(PolicyService.class);
    CultivationController controller =
        new CultivationController(service, activityService, policyService, false);
    UUID userId = UUID.randomUUID();
    var authentication =
        new UsernamePasswordAuthenticationToken(
            new SessionService.SessionPrincipal(userId, Instant.parse("2027-01-01T00:00:00Z")),
            "unused");
    HttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();

    var profile = controller.getProfile(authentication, request);
    var activity = controller.getActivity(authentication, null, "30", request);
    var settings = controller.saveSettings(authentication, null, request);

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, profile.getStatusCode());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, activity.getStatusCode());
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, settings.getStatusCode());
    assertEquals(
        "CULTIVATION_NOT_ENABLED", ((ProblemDetail) profile.getBody()).getProperties().get("code"));
    verifyNoInteractions(service, activityService, policyService);
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void unexpectedProfileFailureDoesNotWriteExceptionContentToLogs(CapturedOutput output) {
    CultivationController controller =
        new CultivationController(
            mock(CultivationService.class),
            mock(CultivationActivityService.class),
            mock(PolicyService.class),
            true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    String correlationId = "1478efb0-d32a-4b3a-94be-3a7f5d3260a1";
    String privateSentinel = "private-chapter-secret-2b57d9f1";
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, correlationId);

    var response =
        controller.unavailableProfile(
            new IllegalStateException("Database diagnostic included " + privateSentinel), request);

    String logOutput = output.getAll();
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertEquals(
        "Cultivation settings could not be loaded. Please try again.",
        ((ProblemDetail) response.getBody()).getDetail());
    assertTrue(logOutput.contains(correlationId));
    assertTrue(logOutput.contains("exceptionType=IllegalStateException"));
    assertFalse(logOutput.contains(privateSentinel));
    assertFalse(logOutput.contains("Database diagnostic included"));
  }
}
