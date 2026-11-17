package com.novelweb.library;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MyWorkSubscriptionsControllerTests {

  private final UUID actorId = UUID.randomUUID();
  private final UUID subscriptionId = UUID.randomUUID();
  private final UUID workId = UUID.randomUUID();
  private WorkSubscriptionListService service;
  private PolicyService policyService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = org.mockito.Mockito.mock(WorkSubscriptionListService.class);
    policyService = org.mockito.Mockito.mock(PolicyService.class);
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new MyWorkSubscriptionsController(service, policyService))
            .build();
  }

  @Test
  void returnsActorBoundNoStorePageAndNeutralizesWithdrawnMetadata() throws Exception {
    when(service.list(actorId, null, 20))
        .thenReturn(
            new WorkSubscriptionListService.Page(
                List.of(
                    new WorkSubscriptionRepository.SubscriptionItem(
                        subscriptionId,
                        workId,
                        false,
                        false,
                        4,
                        Instant.parse("2026-11-17T12:00:00Z"),
                        null,
                        null)),
                null));

    mockMvc
        .perform(get("/api/v1/me/subscriptions").principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Pragma", "no-cache"))
        .andExpect(jsonPath("$.items[0].id").value(subscriptionId.toString()))
        .andExpect(jsonPath("$.items[0].workId").value(workId.toString()))
        .andExpect(jsonPath("$.items[0].available").value(false))
        .andExpect(jsonPath("$.items[0].title").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.items[0].author").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.actorUserId").doesNotExist());
  }

  @Test
  void rejectsInvalidPageSizeBeforeServiceRead() throws Exception {
    mockMvc
        .perform(get("/api/v1/me/subscriptions?limit=51").principal(authentication()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION_LIMIT"));
    verifyNoInteractions(service);
  }

  @Test
  void anonymousReadDoesNotQueryActorSubscriptions() throws Exception {
    mockMvc
        .perform(get("/api/v1/me/subscriptions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    verifyNoInteractions(service);
  }

  private Authentication authentication() {
    SessionService.SessionPrincipal principal =
        new SessionService.SessionPrincipal(actorId, Instant.now().plusSeconds(60));
    return new UsernamePasswordAuthenticationToken(principal, "", List.of());
  }
}
