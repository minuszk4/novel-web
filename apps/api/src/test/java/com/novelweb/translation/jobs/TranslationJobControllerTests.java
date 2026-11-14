package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class TranslationJobControllerTests {

  private TranslationJobService jobService;
  private TranslationJobStatusService statusService;
  private TranslationJobRetryService retryService;
  private TranslationJobCancelService cancelService;
  private PolicyService policyService;
  private TranslationJobController controller;
  private UUID actorId;
  private UUID jobId;

  @BeforeEach
  void setUp() {
    jobService = mock(TranslationJobService.class);
    statusService = mock(TranslationJobStatusService.class);
    retryService = mock(TranslationJobRetryService.class);
    cancelService = mock(TranslationJobCancelService.class);
    policyService = mock(PolicyService.class);
    controller =
        new TranslationJobController(
            jobService, statusService, retryService, cancelService, policyService);
    actorId = UUID.randomUUID();
    jobId = UUID.randomUUID();
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
  }

  @Test
  void enqueueReturnsAcceptedLocationAndNoStoreForANewFakeLocalJob() {
    TranslationJobRepository.Job job = job("queued");
    when(jobService.enqueue(
            actorId, job.chapterId(), job.sourceRevisionId(), "en", "api-key-0000000001"))
        .thenReturn(new TranslationJobService.EnqueueResult(job, false));

    ResponseEntity<?> response =
        controller.enqueue(
            authentication(),
            "api-key-0000000001",
            new TranslationJobController.EnqueueRequest(
                job.chapterId().toString(), job.sourceRevisionId().toString(), "en"),
            request());

    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    assertEquals("/api/v1/translation/jobs/" + jobId, response.getHeaders().getFirst("Location"));
    assertEquals("no-store", response.getHeaders().getCacheControl());
    TranslationJobController.EnqueueResponse body =
        assertInstanceOf(TranslationJobController.EnqueueResponse.class, response.getBody());
    assertEquals(jobId, body.jobId());
    assertEquals("queued", body.status());
    assertEquals("fake-local", body.providerId());
    assertEquals(2, body.totalUnits());
    assertTrue(response.getHeaders().getFirst("Retry-After") != null);
    verify(jobService)
        .enqueue(actorId, job.chapterId(), job.sourceRevisionId(), "en", "api-key-0000000001");
  }

  @Test
  void missingSessionAndUnacceptedPolicyStopBeforeEnqueue() {
    ResponseEntity<?> unauthenticated =
        controller.enqueue(
            null,
            "api-key-0000000001",
            new TranslationJobController.EnqueueRequest("bad", "bad", "en"),
            request());
    assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.getStatusCode());

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    ResponseEntity<?> policyRequired =
        controller.enqueue(
            authentication(),
            "api-key-0000000001",
            new TranslationJobController.EnqueueRequest("bad", "bad", "en"),
            request());
    assertEquals(HttpStatus.FORBIDDEN, policyRequired.getStatusCode());
    verifyNoInteractions(jobService, statusService, retryService, cancelService);
  }

  @Test
  void statusConcealsUnavailableJobsAndUsesNoStore() {
    when(statusService.findVisible(actorId, jobId)).thenReturn(Optional.empty());

    ResponseEntity<?> response = controller.status(authentication(), jobId.toString(), request());

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().getCacheControl());
  }

  @Test
  void retryAndCancelForwardTheSessionActorAndIdempotencyKey() {
    TranslationJobRetryRepository.RetryResult retry =
        new TranslationJobRetryRepository.RetryResult(jobId, "queued", 1, false, UUID.randomUUID());
    when(retryService.retryFailedUnits(actorId, jobId, "retry-api-key-000001")).thenReturn(retry);
    TranslationJobCancelRepository.CancelResult cancel =
        new TranslationJobCancelRepository.CancelResult(
            jobId, "cancelled", 1, 0, false, UUID.randomUUID());
    when(cancelService.cancel(actorId, jobId, "cancel-api-key-000001")).thenReturn(cancel);

    ResponseEntity<?> retryResponse =
        controller.retry(authentication(), jobId.toString(), "retry-api-key-000001", request());
    ResponseEntity<?> cancelResponse =
        controller.cancel(authentication(), jobId.toString(), "cancel-api-key-000001", request());

    assertEquals(HttpStatus.ACCEPTED, retryResponse.getStatusCode());
    assertEquals(HttpStatus.OK, cancelResponse.getStatusCode());
    assertEquals(retry, retryResponse.getBody());
    assertEquals(cancel, cancelResponse.getBody());
    verify(retryService).retryFailedUnits(actorId, jobId, "retry-api-key-000001");
    verify(cancelService).cancel(actorId, jobId, "cancel-api-key-000001");
  }

  private TranslationJobRepository.Job job(String status) {
    return new TranslationJobRepository.Job(
        jobId,
        actorId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "vi",
        "en",
        "fake-local",
        new byte[32],
        new byte[32],
        status,
        2,
        Instant.now());
  }

  private Authentication authentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        new SessionService.SessionPrincipal(actorId, Instant.now().plusSeconds(300)),
        null,
        java.util.List.of());
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, UUID.randomUUID().toString());
    return request;
  }
}
