package com.novelweb.library;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.library.WorkSubscriptionRateLimiter.Decision;
import com.novelweb.library.WorkSubscriptionRateLimiter.RateLimitStoreUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkSubscriptionControllerTests {

  private final UUID actorId = UUID.randomUUID();
  private final UUID workId = UUID.randomUUID();
  private WorkSubscriptionService service;
  private PolicyService policyService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    service = org.mockito.Mockito.mock(WorkSubscriptionService.class);
    policyService = org.mockito.Mockito.mock(PolicyService.class);
    when(service.acquireMutationLimit(actorId)).thenReturn(new Decision(true, 0));
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new WorkSubscriptionController(service, policyService))
            .build();
  }

  @Test
  void absentSubscriptionIsActorScopedAndCacheDisabled() throws Exception {
    when(service.get(actorId, workId))
        .thenReturn(WorkSubscriptionRepository.SubscriptionState.absent());

    mockMvc
        .perform(get("/api/v1/works/{workId}/subscription", workId).principal(authentication()))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"subscription-absent\""))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.following").value(false))
        .andExpect(jsonPath("$.notificationsEnabled").value(false))
        .andExpect(jsonPath("$.actorUserId").doesNotExist());
  }

  @Test
  void followingMutationRequiresStrongCurrentEtagAndReturnsNewVersion() throws Exception {
    when(service.setNotifications(actorId, workId, true, 1))
        .thenReturn(new WorkSubscriptionRepository.SubscriptionState(true, true, 2));

    mockMvc
        .perform(
            put("/api/v1/works/{workId}/subscription", workId)
                .principal(authentication())
                .header("If-Match", "\"subscription-1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationsEnabled\":true}"))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"subscription-2\""))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.following").value(true))
        .andExpect(jsonPath("$.notificationsEnabled").value(true));
    verify(service).setNotifications(actorId, workId, true, 1);
  }

  @Test
  void missingEtagIsRejectedBeforeRepositoryMutation() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/works/{workId}/subscription", workId)
                .principal(authentication())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationsEnabled\":true}"))
        .andExpect(status().isPreconditionRequired())
        .andExpect(jsonPath("$.code").value("SUBSCRIPTION_VERSION_REQUIRED"));
    verifyNoInteractions(service);
  }

  @Test
  void unsupportedRequestFieldsAreRejected() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/works/{workId}/subscription", workId)
                .principal(authentication())
                .header("If-Match", "\"subscription-absent\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationsEnabled\":true,\"actorUserId\":\"" + actorId + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_SUBSCRIPTION_REQUEST"));
    verifyNoInteractions(service);
  }

  @Test
  void deleteUsesEtagAndReturnsNoContent() throws Exception {
    when(service.unfollow(actorId, workId, 4L))
        .thenReturn(new WorkSubscriptionRepository.SubscriptionState(false, false, 5));

    mockMvc
        .perform(
            delete("/api/v1/works/{workId}/subscription", workId)
                .principal(authentication())
                .header("If-Match", "\"subscription-4\""))
        .andExpect(status().isNoContent())
        .andExpect(header().string("ETag", "\"subscription-5\""))
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(service).unfollow(actorId, workId, 4L);
  }

  @Test
  void mutationRateLimitReturnsRetryAfterWithoutCallingSubscriptionRepository() throws Exception {
    when(service.acquireMutationLimit(actorId)).thenReturn(new Decision(false, 17));

    mockMvc
        .perform(
            put("/api/v1/works/{workId}/subscription", workId)
                .principal(authentication())
                .header("If-Match", "\"subscription-absent\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationsEnabled\":true}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "17"))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.code").value("SUBSCRIPTION_RATE_LIMITED"));
    verify(service).acquireMutationLimit(actorId);
    verify(service, org.mockito.Mockito.never()).setNotifications(actorId, workId, true, 0);
  }

  @Test
  void rateLimitStoreFailureFailsClosed() throws Exception {
    when(service.acquireMutationLimit(actorId))
        .thenThrow(new RateLimitStoreUnavailableException(new IllegalStateException("offline")));

    mockMvc
        .perform(
            put("/api/v1/works/{workId}/subscription", workId)
                .principal(authentication())
                .header("If-Match", "\"subscription-absent\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationsEnabled\":true}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("SUBSCRIPTION_RATE_LIMIT_UNAVAILABLE"));
    verify(service, org.mockito.Mockito.never()).setNotifications(actorId, workId, true, 0);
  }

  @Test
  void anonymousReadNeverQueriesAnotherActorsSubscription() throws Exception {
    mockMvc
        .perform(get("/api/v1/works/{workId}/subscription", workId))
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
