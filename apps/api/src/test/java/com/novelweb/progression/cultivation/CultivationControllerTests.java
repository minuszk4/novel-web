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
}
