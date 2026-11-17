package com.novelweb.translation.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelweb.identity.auth.PolicyService;
import com.novelweb.identity.session.SessionService;
import com.novelweb.platform.web.CorrelationIdFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

class TranslationCandidateReviewControllerTests {

  private TranslationCandidateReviewService reviewService;
  private PolicyService policyService;
  private TranslationCandidateReviewController controller;
  private UUID actorId;
  private UUID jobId;

  @BeforeEach
  void setUp() {
    reviewService = mock(TranslationCandidateReviewService.class);
    policyService = mock(PolicyService.class);
    controller = new TranslationCandidateReviewController(reviewService, policyService);
    actorId = UUID.randomUUID();
    jobId = UUID.randomUUID();
    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.COMPLETE);
  }

  @Test
  void readinessReturnsOnlyCountsAndNoStoreSnapshotEtag() {
    byte[] digest = new byte[32];
    TranslationCandidateReviewRepository.Readiness readiness =
        new TranslationCandidateReviewRepository.Readiness(
            TranslationCandidateReviewRepository.Outcome.AVAILABLE,
            jobId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "vi",
            "en",
            2,
            2,
            List.of(),
            false,
            digest);
    when(reviewService.readiness(actorId, jobId)).thenReturn(readiness);

    ResponseEntity<?> response =
        controller.readiness(authentication(), jobId.toString(), request());

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(TranslationReviewSnapshotHasher.etag(digest), response.getHeaders().getETag());
    assertEquals("no-store", response.getHeaders().getCacheControl());
    TranslationCandidateReviewController.ReadinessResponse body =
        assertInstanceOf(
            TranslationCandidateReviewController.ReadinessResponse.class, response.getBody());
    assertEquals(2, body.editedSegments());
    assertEquals(2, body.totalSegments());
    assertTrue(body.readyForSubmission());
    assertFalse(body.blockers().contains("INCOMPLETE_MANUAL_EDITS"));
  }

  @Test
  void missingSessionAndPolicyStopBeforeReadingPrivateCandidate() {
    ResponseEntity<?> unauthenticated = controller.readiness(null, jobId.toString(), request());
    assertEquals(HttpStatus.UNAUTHORIZED, unauthenticated.getStatusCode());

    when(policyService.requiredAcceptance(actorId))
        .thenReturn(PolicyService.RequiredAcceptance.REQUIRED);
    ResponseEntity<?> policyRequired =
        controller.readiness(authentication(), jobId.toString(), request());
    assertEquals(HttpStatus.FORBIDDEN, policyRequired.getStatusCode());
    assertEquals(
        "POLICY_ACCEPTANCE_REQUIRED",
        ((ProblemDetail) policyRequired.getBody()).getProperties().get("code"));
    verifyNoInteractions(reviewService);
  }

  @Test
  void submissionRejectsProseAndRequiresBothConcurrencyHeaders() {
    MockHttpServletRequest bodyRequest = request();
    bodyRequest.setContent("chapter text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ResponseEntity<?> proseRejected =
        controller.submit(authentication(), jobId.toString(), bodyRequest);
    assertEquals(HttpStatus.BAD_REQUEST, proseRejected.getStatusCode());
    assertEquals(
        "TRANSLATION_REVIEW_BODY_NOT_ALLOWED",
        ((ProblemDetail) proseRejected.getBody()).getProperties().get("code"));

    ResponseEntity<?> missingHeaders =
        controller.submit(authentication(), jobId.toString(), request());
    assertEquals(HttpStatus.BAD_REQUEST, missingHeaders.getStatusCode());
    assertEquals(
        "IDEMPOTENCY_KEY_INVALID",
        ((ProblemDetail) missingHeaders.getBody()).getProperties().get("code"));
    verifyNoInteractions(reviewService);
  }

  @Test
  void createdAndReplayedReceiptsUseExpectedStatusEtagAndNoStore() {
    byte[] digest = new byte[32];
    String snapshotDigest = TranslationReviewSnapshotHasher.digestHex(digest);
    TranslationCandidateReviewRepository.SubmissionReceipt created = receipt(snapshotDigest, false);
    TranslationCandidateReviewRepository.SubmissionReceipt replayed = receipt(snapshotDigest, true);
    when(reviewService.submit(
            actorId,
            jobId,
            TranslationReviewSnapshotHasher.etag(digest),
            "translation-review-controller-test-01"))
        .thenReturn(
            new TranslationCandidateReviewRepository.SubmissionResult(
                TranslationCandidateReviewRepository.Outcome.CREATED, created, 2, 2, null),
            new TranslationCandidateReviewRepository.SubmissionResult(
                TranslationCandidateReviewRepository.Outcome.REPLAYED, replayed, 2, 2, null));

    ResponseEntity<?> first =
        controller.submit(
            authentication(),
            jobId.toString(),
            submitRequest(TranslationReviewSnapshotHasher.etag(digest)));
    ResponseEntity<?> second =
        controller.submit(
            authentication(),
            jobId.toString(),
            submitRequest(TranslationReviewSnapshotHasher.etag(digest)));

    assertEquals(HttpStatus.CREATED, first.getStatusCode());
    assertEquals(HttpStatus.OK, second.getStatusCode());
    assertEquals(TranslationReviewSnapshotHasher.etag(digest), first.getHeaders().getETag());
    assertEquals("no-store", first.getHeaders().getCacheControl());
    assertFalse(
        ((TranslationCandidateReviewController.SubmissionResponse) first.getBody()).replayed());
    assertTrue(
        ((TranslationCandidateReviewController.SubmissionResponse) second.getBody()).replayed());
    verify(reviewService, times(2))
        .submit(
            actorId,
            jobId,
            TranslationReviewSnapshotHasher.etag(digest),
            "translation-review-controller-test-01");
  }

  @Test
  void staleSnapshotReturnsCurrentStrongEtagWithoutSnapshotText() {
    byte[] digest = new byte[32];
    String currentEtag = TranslationReviewSnapshotHasher.etag(digest);
    when(reviewService.submit(
            actorId,
            jobId,
            "\"translation-review-" + "a".repeat(64) + "\"",
            "translation-review-controller-test-01"))
        .thenReturn(
            new TranslationCandidateReviewRepository.SubmissionResult(
                TranslationCandidateReviewRepository.Outcome.STALE_ETAG, null, 0, 0, currentEtag));

    ResponseEntity<?> response =
        controller.submit(
            authentication(),
            jobId.toString(),
            submitRequest("\"translation-review-" + "a".repeat(64) + "\""));

    assertEquals(HttpStatus.PRECONDITION_FAILED, response.getStatusCode());
    assertEquals(currentEtag, response.getHeaders().getETag());
    assertEquals(
        "TRANSLATION_REVIEW_SNAPSHOT_STALE",
        ((ProblemDetail) response.getBody()).getProperties().get("code"));
  }

  private TranslationCandidateReviewRepository.SubmissionReceipt receipt(
      String snapshotDigest, boolean replayed) {
    return new TranslationCandidateReviewRepository.SubmissionReceipt(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "pending_review",
        snapshotDigest,
        2,
        Instant.now(),
        replayed);
  }

  private MockHttpServletRequest submitRequest(String etag) {
    MockHttpServletRequest request = request();
    request.addHeader("If-Match", etag);
    request.addHeader("Idempotency-Key", "translation-review-controller-test-01");
    return request;
  }

  private MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, UUID.randomUUID().toString());
    return request;
  }

  private Authentication authentication() {
    return UsernamePasswordAuthenticationToken.authenticated(
        new SessionService.SessionPrincipal(actorId, Instant.now().plusSeconds(300)),
        null,
        List.of());
  }
}
