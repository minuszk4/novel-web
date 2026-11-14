package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
  private TranslationCandidatePreviewService candidatePreviewService;
  private TranslationCandidateEditService candidateEditService;
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
    candidatePreviewService = mock(TranslationCandidatePreviewService.class);
    candidateEditService = mock(TranslationCandidateEditService.class);
    policyService = mock(PolicyService.class);
    controller =
        new TranslationJobController(
            jobService,
            statusService,
            retryService,
            cancelService,
            candidatePreviewService,
            candidateEditService,
            policyService);
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
    verifyNoInteractions(
        jobService,
        statusService,
        retryService,
        cancelService,
        candidatePreviewService,
        candidateEditService);
  }

  @Test
  void statusConcealsUnavailableJobsAndUsesNoStore() {
    when(statusService.findVisible(actorId, jobId)).thenReturn(Optional.empty());

    ResponseEntity<?> response = controller.status(authentication(), jobId.toString(), request());

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().getCacheControl());
  }

  @Test
  void jobListRequiresSessionAndPolicyAndReturnsPrivatePage() {
    TranslationJobStatusService.JobPage page =
        new TranslationJobStatusService.JobPage(java.util.List.of(), "signed-cursor");
    when(statusService.listManageable(actorId, null, 25)).thenReturn(page);

    ResponseEntity<?> guest = controller.listJobs(null, null, 25, request());
    ResponseEntity<?> response = controller.listJobs(authentication(), null, 25, request());

    assertEquals(HttpStatus.UNAUTHORIZED, guest.getStatusCode());
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertEquals(
        new TranslationJobController.JobListResponse(java.util.List.of(), "signed-cursor"),
        response.getBody());
    verify(statusService).listManageable(actorId, null, 25);
  }

  @Test
  void candidatePreviewRequiresSessionAndPolicyAndReturnsNoStoreWriterPage() {
    var preview =
        new TranslationCandidatePreviewService.CandidatePreview(
            jobId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            true,
            "vi",
            "en",
            "fake-local",
            "ready_for_review",
            0,
            20,
            1,
            java.util.List.of(
                new TranslationCandidatePreviewService.PreviewSegment(
                    UUID.randomUUID(),
                    1,
                    "paragraph",
                    "Source sentence.",
                    "[fake vi->en] Source sentence.",
                    null,
                    0,
                    "\"edit-0\"")));
    when(candidatePreviewService.findCurrentReadyForReview(actorId, jobId, 0, 20))
        .thenReturn(Optional.of(preview));

    ResponseEntity<?> guest = controller.candidatePreview(null, jobId.toString(), 0, 20, request());
    ResponseEntity<?> response =
        controller.candidatePreview(authentication(), jobId.toString(), 0, 20, request());

    assertEquals(HttpStatus.UNAUTHORIZED, guest.getStatusCode());
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("no-store", response.getHeaders().getCacheControl());
    assertEquals(preview, response.getBody());
    verify(candidatePreviewService).findCurrentReadyForReview(actorId, jobId, 0, 20);
  }

  @Test
  void candidateSegmentEditRequiresSessionAndPolicyBeforeCallingTheWriterService() {
    var body = new TranslationJobController.CandidateSegmentEditRequest();
    body.setTranslatedText("Manually corrected sentence.");
    ResponseEntity<?> guest =
        controller.editCandidateSegment(
            null,
            jobId.toString(),
            UUID.randomUUID().toString(),
            body,
            "\"edit-0\"",
            "candidate-edit-key-0001",
            request());

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    ResponseEntity<?> policyRequired =
        controller.editCandidateSegment(
            authentication(),
            jobId.toString(),
            UUID.randomUUID().toString(),
            body,
            "\"edit-0\"",
            "candidate-edit-key-0001",
            request());

    assertEquals(HttpStatus.UNAUTHORIZED, guest.getStatusCode());
    assertEquals(HttpStatus.FORBIDDEN, policyRequired.getStatusCode());
    verifyNoInteractions(candidateEditService);
  }

  @Test
  void candidateSegmentEditUsesEtagIdempotencyAndNoStoreResponse() {
    UUID segmentId = UUID.randomUUID();
    UUID candidateId = UUID.randomUUID();
    var body = new TranslationJobController.CandidateSegmentEditRequest();
    body.setTranslatedText("Manually corrected sentence.");
    var saved =
        new TranslationCandidateEditRepository.EditResult(
            jobId, candidateId, segmentId, 1, body.translatedText(), Instant.now(), false);
    when(candidateEditService.save(
            actorId, jobId, segmentId, 0, "candidate-edit-key-0001", body.translatedText()))
        .thenReturn(saved);

    ResponseEntity<?> response =
        controller.editCandidateSegment(
            authentication(),
            jobId.toString(),
            segmentId.toString(),
            body,
            "\"edit-0\"",
            "candidate-edit-key-0001",
            request());

    assertEquals(HttpStatus.CREATED, response.getStatusCode());
    assertEquals("\"edit-1\"", response.getHeaders().getETag());
    assertEquals("no-store", response.getHeaders().getCacheControl());
    verify(candidateEditService)
        .save(actorId, jobId, segmentId, 0, "candidate-edit-key-0001", body.translatedText());
  }

  @Test
  void candidateSegmentEditRejectsMissingOrInvalidWritePreconditionsBeforeServiceCall() {
    UUID segmentId = UUID.randomUUID();
    var body = new TranslationJobController.CandidateSegmentEditRequest();
    body.setTranslatedText("Manually corrected sentence.");

    ResponseEntity<?> missingEtag =
        controller.editCandidateSegment(
            authentication(),
            jobId.toString(),
            segmentId.toString(),
            body,
            null,
            "candidate-edit-key-0001",
            request());
    ResponseEntity<?> invalidEtag =
        controller.editCandidateSegment(
            authentication(),
            jobId.toString(),
            segmentId.toString(),
            body,
            "W/\"edit-0\"",
            "candidate-edit-key-0001",
            request());
    ResponseEntity<?> invalidKey =
        controller.editCandidateSegment(
            authentication(),
            jobId.toString(),
            segmentId.toString(),
            body,
            "\"edit-0\"",
            "short",
            request());

    assertEquals(HttpStatus.PRECONDITION_REQUIRED, missingEtag.getStatusCode());
    assertEquals(HttpStatus.BAD_REQUEST, invalidEtag.getStatusCode());
    assertEquals(HttpStatus.BAD_REQUEST, invalidKey.getStatusCode());
    verifyNoInteractions(candidateEditService);
  }

  @Test
  void staleCandidateEditReturnsCurrentStrongEtag() {
    ResponseEntity<?> response =
        controller.staleCandidateEdit(
            new TranslationCandidateEditRepository.EditRevisionConflictException(3), request());

    assertEquals(HttpStatus.PRECONDITION_FAILED, response.getStatusCode());
    var problem = (org.springframework.http.ProblemDetail) response.getBody();
    assertEquals("\"edit-3\"", problem.getProperties().get("currentEtag"));
    assertEquals("TRANSLATION_EDIT_STALE", problem.getProperties().get("code"));
  }

  @Test
  void candidatePreviewRejectsInvalidPageBeforeReadingAnyCandidate() {
    assertThrows(
        IllegalArgumentException.class,
        () -> controller.candidatePreview(authentication(), jobId.toString(), 5001, 20, request()));
    verifyNoInteractions(candidatePreviewService);
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
