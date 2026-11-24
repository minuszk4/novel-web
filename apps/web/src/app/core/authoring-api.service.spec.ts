import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  AuthoringApiError,
  AuthoringApiService,
  PublicationRequestSubmission,
} from './authoring-api.service';

const workId = '11111111-1111-4111-8111-111111111111';
const csrfToken = 'A'.repeat(43);
const idempotencyKey = 'author-draft-create-key-0001';
const createdAt = '2026-11-14T12:00:00Z';
const chapterId = '22222222-2222-4222-8222-222222222222';
const revisionId = '44444444-4444-4444-8444-444444444444';
const policyAcceptanceId = '55555555-5555-4555-8555-555555555555';
const policyIdempotencyKey = '66666666-6666-4666-8666-666666666666';
const publicationEtag = `"publication-snapshot-${'a'.repeat(64)}"`;
const publicationScopeEtag = `"publication-scope-${'b'.repeat(64)}"`;
const releaseIdempotencyKey = 'publication-release-test-key-0001';
const chapterCreatedAt = '2026-11-14T12:30:00Z';
const rightsPolicy = {
  version: 'rights-v1',
  effectiveAt: '2026-11-15T00:00:00Z',
  displayText: 'Chính sách quyền xuất bản kiểm thử.',
  policySha256: 'a'.repeat(64),
  acceptedAt: null,
};
const publicationReadiness = {
  workId,
  canSubmit: true,
  publicationRightsPolicyAvailable: true,
  publicationRightsPolicyVersion: 'rights-v1',
  publicationRightsPolicyEffectiveAt: '2026-11-15T00:00:00Z',
  publicationRightsPolicyAcceptedAt: '2026-11-16T12:00:00Z',
  publicationContentVersion: 7,
  readyChapterCount: 3,
  currentRequestState: 'APPROVED_UNPUBLISHED',
  snapshotEtag: publicationEtag,
  scopes: [
    { scopeCode: 'PUBLIC_METADATA', ready: true, snapshotEtag: publicationScopeEtag },
    { scopeCode: 'CHAPTER_TEXT', ready: true, snapshotEtag: publicationScopeEtag },
    { scopeCode: 'COMMERCIAL_SALE', ready: true, snapshotEtag: publicationScopeEtag },
    { scopeCode: 'TRANSLATION_ADAPTATION', ready: true, snapshotEtag: publicationScopeEtag },
  ],
  blockerCodes: [],
};
const publicationReceipt = {
  eventId: '77777777-7777-4777-8777-777777777777',
  workId,
  requestId: '88888888-8888-4888-8888-888888888888',
  state: 'PUBLISHED',
  publishedAt: '2026-11-16T12:30:00Z',
  publicationContentVersion: 7,
};
const publicationRequestIdempotencyKey = 'publication-request-test-key-0001';
const publicationRequest: PublicationRequestSubmission = {
  policyVersion: 'rights-v1',
  scopeClaims: [
    { scopeCode: 'PUBLIC_METADATA', rightsBasisCode: 'ORIGINAL_WORK' },
    { scopeCode: 'CHAPTER_TEXT', rightsBasisCode: 'LICENSE_OR_PERMISSION' },
    { scopeCode: 'COMMERCIAL_SALE', rightsBasisCode: 'NEEDS_REVIEW' },
    { scopeCode: 'TRANSLATION_ADAPTATION', rightsBasisCode: 'ORIGINAL_WORK' },
  ],
};
const publicationRequestReceipt = {
  requestId: '99999999-9999-4999-8999-999999999999',
  requestVersion: 2,
  policyVersion: 'rights-v1',
  snapshotSha256: 'c'.repeat(64),
  scopeCodes: ['PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE', 'TRANSLATION_ADAPTATION'],
  submittedAt: '2026-11-17T04:00:00Z',
  state: 'PENDING_REVIEW',
};
const publicationRequestWithdrawalReceipt = {
  eventId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  requestId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  requestVersion: 2,
  state: 'WITHDRAWN',
  withdrawnAt: '2026-11-17T04:30:00Z',
};
const publicationRequestHistoryPage = {
  items: [
    {
      requestVersion: 1,
      state: 'NEEDS_INFORMATION',
      submittedAt: '2026-11-17T04:00:00Z',
      events: [
        {
          sequence: 1,
          actorType: 'OWNER',
          actionCode: 'SUBMITTED',
          fromState: null,
          toState: 'PENDING_REVIEW',
          reasonCode: null,
          createdAt: '2026-11-17T04:00:00Z',
        },
        {
          sequence: 2,
          actorType: 'REVIEWER',
          actionCode: 'NEEDS_INFORMATION',
          fromState: 'PENDING_REVIEW',
          toState: 'NEEDS_INFORMATION',
          reasonCode: 'MORE_INFORMATION',
          createdAt: '2026-11-17T04:10:00Z',
        },
      ],
    },
  ],
  nextCursor: null,
};
const createdDraft = {
  id: workId,
  title: 'Hành trình tu luyện',
  author: 'Bút danh',
  language: 'vi',
  publicationState: 'draft',
  visibility: 'private',
  createdAt,
};

describe('AuthoringApiService', () => {
  let api: AuthoringApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(AuthoringApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('creates a private draft with CSRF and the supplied idempotency key', async () => {
    const pending = api.createPrivateManuscript(
      { title: ' Hành trình tu luyện ', author: ' Bút danh ', language: ' vi ' },
      idempotencyKey,
    );
    await flushCsrf(http);

    const request = http.expectOne('/api/v1/authoring/manuscripts');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('Idempotency-Key')).toBe(idempotencyKey);
    expect(request.request.body).toEqual({
      title: 'Hành trình tu luyện',
      author: 'Bút danh',
      language: 'vi',
    });
    request.flush(createdDraft, { status: 201, statusText: 'Created' });

    await expect(pending).resolves.toEqual(createdDraft);
  });

  it('accepts an empty optional byline but validates other fields and idempotency keys', async () => {
    const pending = api.createPrivateManuscript(
      { title: 'Truyện', author: '', language: 'vi' },
      idempotencyKey,
    );
    await flushCsrf(http);
    const request = http.expectOne('/api/v1/authoring/manuscripts');
    expect(request.request.body).toEqual({ title: 'Truyện', author: '', language: 'vi' });
    request.flush(
      { ...createdDraft, title: 'Truyện', author: '' },
      { status: 201, statusText: 'Created' },
    );
    await expect(pending).resolves.toMatchObject({ title: 'Truyện', author: '' });

    await expect(
      api.createPrivateManuscript({ title: '  ', author: '', language: 'vi' }, idempotencyKey),
    ).rejects.toMatchObject({ code: 'INVALID_MANUSCRIPT', status: 400 });
    await expect(
      api.createPrivateManuscript({ title: 'Truyện', author: '', language: 'vi' }, 'short'),
    ).rejects.toMatchObject({ code: 'INVALID_MANUSCRIPT', status: 400 });
  });

  it('rejects an invalid response rather than treating a non-private or malformed draft as saved', async () => {
    const pending = api.createPrivateManuscript(
      { title: 'Truyện', author: '', language: 'vi' },
      idempotencyKey,
    );
    await flushCsrf(http);
    http
      .expectOne('/api/v1/authoring/manuscripts')
      .flush({ ...createdDraft, visibility: 'public' }, { status: 201, statusText: 'Created' });

    await expect(pending).rejects.toMatchObject({
      code: 'INVALID_MANUSCRIPT_RESPONSE',
      status: 502,
    });
  });

  it('preserves the API problem code and status for safe screen-level error copy', async () => {
    const pending = api.createPrivateManuscript(
      { title: 'Truyện', author: '', language: 'vi' },
      idempotencyKey,
    );
    await flushCsrf(http);
    http
      .expectOne('/api/v1/authoring/manuscripts')
      .flush(
        { code: 'POLICY_ACCEPTANCE_REQUIRED' },
        { status: 403, statusText: 'Forbidden', headers: new HttpHeaders() },
      );

    await expect(pending).rejects.toBeInstanceOf(AuthoringApiError);
    await expect(pending).rejects.toMatchObject({
      status: 403,
      code: 'POLICY_ACCEPTANCE_REQUIRED',
    });
  });

  it('reads a strict no-store page of private manuscript metadata with a signed cursor', async () => {
    const pending = api.listPrivateManuscripts('YWJj.ZGVm', 1);
    const request = http.expectOne('/api/v1/authoring/manuscripts?limit=1&cursor=YWJj.ZGVm');
    expect(request.request.method).toBe('GET');
    request.flush(
      {
        items: [
          {
            id: workId,
            title: 'Hành trình tu luyện',
            author: 'Bút danh',
            language: 'vi',
            visibility: 'private',
            publicationState: 'draft',
            chapterCount: 4,
            createdAt,
          },
        ],
        nextCursor: null,
      },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store' }),
      },
    );

    await expect(pending).resolves.toMatchObject({
      items: [expect.objectContaining({ id: workId, visibility: 'private', chapterCount: 4 })],
      nextCursor: null,
    });
  });

  it('reads one strict no-store private manuscript detail bound to the requested ID', async () => {
    const pending = api.getPrivateManuscript(workId);
    const request = http.expectOne(`/api/v1/authoring/manuscripts/${workId}`);
    expect(request.request.method).toBe('GET');
    request.flush(
      { ...createdDraft, chapterCount: 2, metadataVersion: 1 },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({
          'Cache-Control': 'no-store',
          ETag: '"manuscript-metadata-1"',
        }),
      },
    );

    await expect(pending).resolves.toMatchObject({
      id: workId,
      title: 'Hành trình tu luyện',
      chapterCount: 2,
      visibility: 'private',
      publicationState: 'draft',
      metadataVersion: 1,
      etag: '"manuscript-metadata-1"',
    });
  });

  it('accepts an owner-only published work detail while keeping the response strict', async () => {
    const pending = api.getPrivateManuscript(workId);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}`)
      .flush({ code: 'MANUSCRIPT_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/public-drafting-context`).flush(
      {
        ...createdDraft,
        visibility: 'public',
        publicationState: 'published',
        chapterCount: 3,
        metadataVersion: 8,
      },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({
          'Cache-Control': 'no-store',
          ETag: '"manuscript-metadata-8"',
        }),
      },
    );

    await expect(pending).resolves.toMatchObject({
      visibility: 'public',
      publicationState: 'published',
      metadataVersion: 8,
      etag: '"manuscript-metadata-8"',
    });
  });

  it('does not accept a private response from the public drafting-context route', async () => {
    const pending = api.getPrivateManuscript(workId);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}`)
      .flush({ code: 'MANUSCRIPT_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/public-drafting-context`)
      .flush({ ...createdDraft, metadataVersion: 1 });

    await expect(pending).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_AUTHORING_MANUSCRIPT_RESPONSE',
    });
  });

  it('reads only a strict no-store publication rights policy for the requested manuscript', async () => {
    const pending = api.getCurrentPublicationRightsPolicy(workId);
    const request = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/publication/rights-policy`,
    );
    expect(request.request.method).toBe('GET');
    request.flush(rightsPolicy, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store' }),
    });

    await expect(pending).resolves.toEqual(rightsPolicy);
  });

  it('reads an owner publication checklist bound to the work and strong no-store ETag', async () => {
    const pending = api.getPublicationReadiness(workId);
    const request = http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/readiness`);
    expect(request.request.method).toBe('GET');
    request.flush(publicationReadiness, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({
        'Cache-Control': 'no-store',
        Pragma: 'no-cache',
        ETag: publicationEtag,
      }),
    });

    await expect(pending).resolves.toMatchObject({
      workId,
      canSubmit: true,
      currentRequestState: 'APPROVED_UNPUBLISHED',
      snapshotEtag: publicationEtag,
      scopes: expect.arrayContaining([
        expect.objectContaining({ scopeCode: 'PUBLIC_METADATA', ready: true }),
      ]),
    });
  });

  it('rejects unexpected, incomplete or cacheable publication readiness responses', async () => {
    const unexpected = api.getPublicationReadiness(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/readiness`).flush(
      { ...publicationReadiness, chapterText: 'must not be returned' },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({
          'Cache-Control': 'no-store',
          Pragma: 'no-cache',
          ETag: publicationEtag,
        }),
      },
    );
    await expect(unexpected).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_READINESS_RESPONSE',
    });

    const mismatchedEtag = api.getPublicationReadiness(workId);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/readiness`)
      .flush(publicationReadiness, {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({
          'Cache-Control': 'no-store',
          Pragma: 'no-cache',
          ETag: `"publication-snapshot-${'b'.repeat(64)}"`,
        }),
      });
    await expect(mismatchedEtag).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_READINESS_RESPONSE',
    });

    const cacheable = api.getPublicationReadiness(workId);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/readiness`)
      .flush(publicationReadiness, {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ Pragma: 'no-cache', ETag: publicationEtag }),
      });
    await expect(cacheable).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_READINESS_RESPONSE',
    });
  });

  it('publishes only the explicit owner action with current readiness, CSRF and idempotency', async () => {
    const pending = api.publishManuscriptNow(workId, publicationEtag, releaseIdempotencyKey);
    await flushCsrf(http);
    const request = http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication-actions`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('If-Match')).toBe(publicationEtag);
    expect(request.request.headers.get('Idempotency-Key')).toBe(releaseIdempotencyKey);
    expect(request.request.body).toEqual({ actionCode: 'PUBLISH_NOW' });
    request.flush(publicationReceipt, {
      status: 201,
      statusText: 'Created',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(pending).resolves.toEqual(publicationReceipt);

    const replay = api.publishManuscriptNow(workId, publicationEtag, releaseIdempotencyKey);
    await flushCsrf(http);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication-actions`)
      .flush(publicationReceipt, {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
      });
    await expect(replay).resolves.toEqual(publicationReceipt);
  });

  it('loads, schedules and cancels an owner-only manuscript release schedule', async () => {
    const scheduledAt = '2030-11-17T04:00:00.000Z';
    const scheduleReceipt = {
      scheduleId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      workId,
      requestId: publicationReceipt.requestId,
      state: 'SCHEDULED',
      scheduledAt,
    } as const;
    const scheduleStatus = {
      ...scheduleReceipt,
      failureCode: null,
      attemptCount: 0,
      createdAt,
      updatedAt: createdAt,
    } as const;

    const active = api.getActivePublicationSchedule(workId);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication-schedules`)
      .flush(scheduleStatus, {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
      });
    await expect(active).resolves.toEqual(scheduleStatus);

    const noActive = api.getActivePublicationSchedule(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication-schedules`).flush(null, {
      status: 204,
      statusText: 'No Content',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(noActive).resolves.toBeNull();

    const key = 'publication-schedule-create-key-01';
    const scheduled = api.scheduleManuscriptRelease(workId, publicationEtag, scheduledAt, key);
    await flushCsrf(http);
    const scheduleRequest = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/publication-schedules`,
    );
    expect(scheduleRequest.request.method).toBe('POST');
    expect(scheduleRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(scheduleRequest.request.headers.get('If-Match')).toBe(publicationEtag);
    expect(scheduleRequest.request.headers.get('Idempotency-Key')).toBe(key);
    expect(scheduleRequest.request.body).toEqual({ scheduledAt });
    scheduleRequest.flush(scheduleReceipt, {
      status: 201,
      statusText: 'Created',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(scheduled).resolves.toEqual(scheduleReceipt);

    const cancelled = api.cancelPublicationSchedule(workId, scheduleReceipt.scheduleId);
    await flushCsrf(http);
    const cancelRequest = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/publication-schedules/${scheduleReceipt.scheduleId}`,
    );
    expect(cancelRequest.request.method).toBe('DELETE');
    expect(cancelRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    cancelRequest.flush(null, {
      status: 204,
      statusText: 'No Content',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(cancelled).resolves.toBeUndefined();
  });

  it('loads, submits and releases one staged chapter with strict owner validators', async () => {
    const readiness = api.getChapterPublicationReadiness(workId, chapterId);
    const readinessRequest = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/publication/readiness/chapters/${chapterId}`,
    );
    expect(readinessRequest.request.method).toBe('GET');
    readinessRequest.flush(publicationReadiness, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({
        'Cache-Control': 'no-store',
        Pragma: 'no-cache',
        ETag: publicationEtag,
      }),
    });
    await expect(readiness).resolves.toMatchObject({ workId, snapshotEtag: publicationEtag });

    const requestKey = 'chapter-publication-request-key-01';
    const submission = api.submitChapterPublicationRequest(
      workId,
      chapterId,
      publicationEtag,
      publicationRequest,
      requestKey,
    );
    await flushCsrf(http);
    const submissionRequest = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/publication/requests/chapters/${chapterId}`,
    );
    expect(submissionRequest.request.method).toBe('POST');
    expect(submissionRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(submissionRequest.request.headers.get('If-Match')).toBe(publicationEtag);
    expect(submissionRequest.request.headers.get('Idempotency-Key')).toBe(requestKey);
    expect(submissionRequest.request.body).toEqual(publicationRequest);
    submissionRequest.flush(publicationRequestReceipt, {
      status: 201,
      statusText: 'Created',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(submission).resolves.toEqual(publicationRequestReceipt);

    const releaseKey = 'chapter-publication-release-key-01';
    const releaseReceipt = {
      eventId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      workId,
      chapterId,
      requestId: publicationRequestReceipt.requestId,
      state: 'PUBLISHED',
      publishedAt: createdAt,
      publicationContentVersion: 8,
    };
    const release = api.publishChapterNow(workId, chapterId, publicationEtag, releaseKey);
    await flushCsrf(http);
    const releaseRequest = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/publication-release`,
    );
    expect(releaseRequest.request.method).toBe('POST');
    expect(releaseRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(releaseRequest.request.headers.get('If-Match')).toBe(publicationEtag);
    expect(releaseRequest.request.headers.get('Idempotency-Key')).toBe(releaseKey);
    expect(releaseRequest.request.body).toEqual({ actionCode: 'PUBLISH_NOW' });
    releaseRequest.flush(releaseReceipt, {
      status: 201,
      statusText: 'Created',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(release).resolves.toEqual(releaseReceipt);
  });

  it('submits owner rights claims with the current snapshot, CSRF and exact-key replay', async () => {
    const pending = api.submitPublicationRequest(
      workId,
      publicationEtag,
      publicationRequest,
      publicationRequestIdempotencyKey,
    );
    await flushCsrf(http);
    const request = http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/requests`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('If-Match')).toBe(publicationEtag);
    expect(request.request.headers.get('Idempotency-Key')).toBe(publicationRequestIdempotencyKey);
    expect(request.request.body).toEqual(publicationRequest);
    request.flush(publicationRequestReceipt, {
      status: 201,
      statusText: 'Created',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(pending).resolves.toEqual(publicationRequestReceipt);

    const replay = api.submitPublicationRequest(
      workId,
      publicationEtag,
      publicationRequest,
      publicationRequestIdempotencyKey,
    );
    await flushCsrf(http);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/requests`)
      .flush(publicationRequestReceipt, {
        status: 201,
        statusText: 'Created',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
      });
    await expect(replay).resolves.toEqual(publicationRequestReceipt);
  });

  it('withdraws the current request with CSRF, strong readiness and exact-key replay', async () => {
    const withdrawalKey = 'publication-withdrawal-key-0001';
    const pending = api.withdrawPublicationRequest(workId, publicationEtag, withdrawalKey);
    await flushCsrf(http);
    const request = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/publication/requests/withdrawal`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('If-Match')).toBe(publicationEtag);
    expect(request.request.headers.get('Idempotency-Key')).toBe(withdrawalKey);
    request.flush(publicationRequestWithdrawalReceipt, {
      status: 201,
      statusText: 'Created',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(pending).resolves.toEqual(publicationRequestWithdrawalReceipt);

    const replay = api.withdrawPublicationRequest(workId, publicationEtag, withdrawalKey);
    await flushCsrf(http);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/requests/withdrawal`)
      .flush(publicationRequestWithdrawalReceipt, {
        status: 201,
        statusText: 'Created',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
      });
    await expect(replay).resolves.toEqual(publicationRequestWithdrawalReceipt);
  });

  it('rejects unsafe withdrawal inputs and malformed or cacheable receipts', async () => {
    await expect(
      api.withdrawPublicationRequest(workId, 'weak-etag', idempotencyKey),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_PUBLICATION_REQUEST_WITHDRAWAL' });
    await expect(
      api.withdrawPublicationRequest(workId, publicationEtag, 'short'),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_PUBLICATION_REQUEST_WITHDRAWAL' });
    http.expectNone((request) => request.url.endsWith('/publication/requests/withdrawal'));

    const malformed = api.withdrawPublicationRequest(
      workId,
      publicationEtag,
      'publication-withdrawal-key-0001',
    );
    await flushCsrf(http);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/requests/withdrawal`).flush(
      { ...publicationRequestWithdrawalReceipt, actorUserId: 'must not be returned' },
      {
        status: 201,
        statusText: 'Created',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
      },
    );
    await expect(malformed).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_REQUEST_WITHDRAWAL_RESPONSE',
    });

    const cacheable = api.withdrawPublicationRequest(
      workId,
      publicationEtag,
      'publication-withdrawal-key-0002',
    );
    await flushCsrf(http);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/requests/withdrawal`)
      .flush(publicationRequestWithdrawalReceipt, {
        status: 201,
        statusText: 'Created',
        headers: new HttpHeaders({ 'Cache-Control': 'public', Pragma: 'no-cache' }),
      });
    await expect(cacheable).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_REQUEST_WITHDRAWAL_RESPONSE',
    });
  });

  it('loads a strict, private publication request timeline without requesting mutation CSRF', async () => {
    const pending = api.getPublicationRequestHistory(workId);
    const request = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/publication/requests/history?limit=20`,
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.headers.has('X-CSRF-Token')).toBe(false);
    request.flush(publicationRequestHistoryPage, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(pending).resolves.toEqual(publicationRequestHistoryPage);

    const invalidQuery = api.getPublicationRequestHistory(workId, 'invalid cursor');
    await expect(invalidQuery).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_PUBLICATION_HISTORY_QUERY',
    });
    http.expectNone((candidate) => candidate.url.endsWith('/publication/requests/history'));
  });

  it('rejects timeline identity leaks, unknown reasons, broken state sequences and cacheable pages', async () => {
    const invalid = api.getPublicationRequestHistory(workId);
    http
      .expectOne((request) => request.url.includes('/publication/requests/history?'))
      .flush(
        { ...publicationRequestHistoryPage, reviewerId: 'must-not-be-returned' },
        {
          status: 200,
          statusText: 'OK',
          headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
        },
      );
    await expect(invalid).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_REQUEST_HISTORY_RESPONSE',
    });

    const wrongReason = api.getPublicationRequestHistory(workId);
    http
      .expectOne((request) => request.url.includes('/publication/requests/history?'))
      .flush(
        {
          ...publicationRequestHistoryPage,
          items: [
            {
              ...publicationRequestHistoryPage.items[0],
              events: [
                publicationRequestHistoryPage.items[0].events[0],
                {
                  ...publicationRequestHistoryPage.items[0].events[1],
                  reasonCode: 'INTERNAL_NOTE',
                },
              ],
            },
          ],
        },
        {
          status: 200,
          statusText: 'OK',
          headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
        },
      );
    await expect(wrongReason).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_REQUEST_HISTORY_RESPONSE',
    });

    const cacheable = api.getPublicationRequestHistory(workId);
    http
      .expectOne((request) => request.url.includes('/publication/requests/history?'))
      .flush(publicationRequestHistoryPage, {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'public', Pragma: 'no-cache' }),
      });
    await expect(cacheable).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_REQUEST_HISTORY_RESPONSE',
    });
  });

  it('rejects invalid publication claims and malformed or cacheable request receipts', async () => {
    const missingRequiredScope: PublicationRequestSubmission = {
      policyVersion: 'rights-v1',
      scopeClaims: [{ scopeCode: 'PUBLIC_METADATA', rightsBasisCode: 'ORIGINAL_WORK' }],
    };
    await expect(
      api.submitPublicationRequest(
        workId,
        publicationEtag,
        missingRequiredScope,
        publicationRequestIdempotencyKey,
      ),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_PUBLICATION_REQUEST' });
    http.expectNone((request) => request.url.endsWith('/publication/requests'));

    const unexpectedReceipt = api.submitPublicationRequest(
      workId,
      publicationEtag,
      publicationRequest,
      publicationRequestIdempotencyKey,
    );
    await flushCsrf(http);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/requests`).flush(
      { ...publicationRequestReceipt, chapterText: 'must not be returned' },
      {
        status: 201,
        statusText: 'Created',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
      },
    );
    await expect(unexpectedReceipt).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_REQUEST_RESPONSE',
    });

    const cacheableReceipt = api.submitPublicationRequest(
      workId,
      publicationEtag,
      publicationRequest,
      publicationRequestIdempotencyKey,
    );
    await flushCsrf(http);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/requests`)
      .flush(publicationRequestReceipt, {
        status: 201,
        statusText: 'Created',
        headers: new HttpHeaders({ 'Cache-Control': 'public' }),
      });
    await expect(cacheableReceipt).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_REQUEST_RESPONSE',
    });
  });

  it('retains the safe Retry-After value from the publication request rate limit', async () => {
    const pending = api.submitPublicationRequest(
      workId,
      publicationEtag,
      publicationRequest,
      publicationRequestIdempotencyKey,
    );
    await flushCsrf(http);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/requests`).flush(
      { code: 'PUBLICATION_REQUEST_RATE_LIMITED' },
      {
        status: 429,
        statusText: 'Too Many Requests',
        headers: new HttpHeaders({ 'Retry-After': '45' }),
      },
    );
    await expect(pending).rejects.toMatchObject({
      status: 429,
      code: 'PUBLICATION_REQUEST_RATE_LIMITED',
      retryAfterSeconds: 45,
    });
  });

  it('validates release inputs and keeps API conflict codes for safe recovery', async () => {
    await expect(api.getPublicationReadiness('not-a-uuid')).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_AUTHORING_QUERY',
    });
    await expect(
      api.publishManuscriptNow(workId, 'weak-etag', releaseIdempotencyKey),
    ).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_PUBLICATION_ACTION',
    });

    const pending = api.publishManuscriptNow(workId, publicationEtag, releaseIdempotencyKey);
    await flushCsrf(http);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication-actions`)
      .flush(
        { code: 'PUBLICATION_SNAPSHOT_STALE' },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await expect(pending).rejects.toMatchObject({
      status: 412,
      code: 'PUBLICATION_SNAPSHOT_STALE',
    });
  });

  it('rejects malformed, oversized, unexpected or cacheable publication policy responses', async () => {
    const unexpected = api.getCurrentPublicationRightsPolicy(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/rights-policy`).flush(
      { ...rightsPolicy, ownerUserId: 'must not be returned' },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store' }),
      },
    );
    await expect(unexpected).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_RIGHTS_POLICY_RESPONSE',
    });

    const oversized = api.getCurrentPublicationRightsPolicy(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/rights-policy`).flush(
      { ...rightsPolicy, displayText: '界'.repeat(11_000) },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store' }),
      },
    );
    await expect(oversized).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_RIGHTS_POLICY_RESPONSE',
    });

    const cacheable = api.getCurrentPublicationRightsPolicy(workId);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/rights-policy`)
      .flush(rightsPolicy, { status: 200, statusText: 'OK' });
    await expect(cacheable).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_RIGHTS_POLICY_RESPONSE',
    });
  });

  it('posts explicit publication policy confirmation with CSRF and the supplied UUID idempotency key', async () => {
    const pending = api.acceptCurrentPublicationRightsPolicy(
      workId,
      rightsPolicy.version,
      rightsPolicy.policySha256,
      policyIdempotencyKey,
    );
    await flushCsrf(http);

    const request = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/publication/rights-policy/acceptance`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('Idempotency-Key')).toBe(policyIdempotencyKey);
    expect(request.request.body).toEqual({ policyVersion: rightsPolicy.version, confirmed: true });
    request.flush(
      {
        id: policyAcceptanceId,
        policyVersion: rightsPolicy.version,
        policySha256: rightsPolicy.policySha256,
        acceptedAt: '2026-11-15T12:30:00Z',
      },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store' }),
      },
    );

    await expect(pending).resolves.toMatchObject({
      id: policyAcceptanceId,
      policyVersion: rightsPolicy.version,
      acceptedAt: '2026-11-15T12:30:00Z',
    });
  });

  it('blocks malformed acceptance requests and rejects a receipt for a different policy digest', async () => {
    await expect(
      api.acceptCurrentPublicationRightsPolicy(
        workId,
        'bad version',
        rightsPolicy.policySha256,
        policyIdempotencyKey,
      ),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_PUBLICATION_RIGHTS_ACCEPTANCE' });
    await expect(
      api.acceptCurrentPublicationRightsPolicy(
        workId,
        rightsPolicy.version,
        'bad-digest',
        policyIdempotencyKey,
      ),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_PUBLICATION_RIGHTS_ACCEPTANCE' });
    await expect(
      api.acceptCurrentPublicationRightsPolicy(
        workId,
        rightsPolicy.version,
        rightsPolicy.policySha256,
        'not-a-uuid',
      ),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_PUBLICATION_RIGHTS_ACCEPTANCE' });
    http.expectNone((request) => request.url.includes('/publication/rights-policy'));

    const mismatch = api.acceptCurrentPublicationRightsPolicy(
      workId,
      rightsPolicy.version,
      rightsPolicy.policySha256,
      policyIdempotencyKey,
    );
    await flushCsrf(http);
    http
      .expectOne(
        `${'/api/v1/authoring/manuscripts/' + workId}/publication/rights-policy/acceptance`,
      )
      .flush(
        {
          id: policyAcceptanceId,
          policyVersion: rightsPolicy.version,
          policySha256: 'b'.repeat(64),
          acceptedAt: '2026-11-15T12:30:00Z',
        },
        {
          status: 200,
          statusText: 'OK',
          headers: new HttpHeaders({ 'Cache-Control': 'no-store' }),
        },
      );
    await expect(mismatch).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_PUBLICATION_RIGHTS_ACCEPTANCE_RESPONSE',
    });
  });

  it('preserves safe API problem codes for publication policy stale/version conflicts', async () => {
    const pending = api.acceptCurrentPublicationRightsPolicy(
      workId,
      rightsPolicy.version,
      rightsPolicy.policySha256,
      policyIdempotencyKey,
    );
    await flushCsrf(http);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/publication/rights-policy/acceptance`)
      .flush(
        { code: 'PUBLICATION_RIGHTS_POLICY_STALE', currentPolicyVersion: 'rights-v2' },
        { status: 409, statusText: 'Conflict' },
      );
    await expect(pending).rejects.toMatchObject({
      status: 409,
      code: 'PUBLICATION_RIGHTS_POLICY_STALE',
    });
  });

  it('updates only strict private manuscript metadata with the current ETag and CSRF', async () => {
    const input = { title: 'Updated Novel', author: 'New Pen', language: 'ja' };
    const pending = api.updatePrivateManuscript(workId, input, '"manuscript-metadata-1"');
    await flushCsrf(http);
    const request = http.expectOne(`/api/v1/authoring/manuscripts/${workId}`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.get('If-Match')).toBe('"manuscript-metadata-1"');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.body).toEqual(input);
    request.flush(
      { ...createdDraft, ...input, chapterCount: 3, metadataVersion: 2 },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({
          'Cache-Control': 'no-store',
          ETag: '"manuscript-metadata-2"',
        }),
      },
    );
    await expect(pending).resolves.toMatchObject({
      id: workId,
      title: input.title,
      author: input.author,
      language: input.language,
      metadataVersion: 2,
      etag: '"manuscript-metadata-2"',
    });
  });

  it('rejects malformed metadata ETags, input bounds, body fields and version headers', async () => {
    await expect(
      api.updatePrivateManuscript(
        workId,
        { title: 'Title', author: '', language: 'vi' },
        'W/"manuscript-metadata-1"',
      ),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_AUTHORING_MANUSCRIPT' });
    await expect(
      api.updatePrivateManuscript(
        workId,
        { title: '😀'.repeat(301), author: '', language: 'vi' },
        '"manuscript-metadata-1"',
      ),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_AUTHORING_MANUSCRIPT' });
    http.expectNone((request) => request.url.includes('/authoring/manuscripts/'));

    const unexpectedField = api.updatePrivateManuscript(
      workId,
      { title: 'Title', author: '', language: 'vi' },
      '"manuscript-metadata-1"',
    );
    await flushCsrf(http);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}`).flush(
      { ...createdDraft, chapterCount: 0, metadataVersion: 2, ownerUserId: 'private' },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({
          'Cache-Control': 'no-store',
          ETag: '"manuscript-metadata-2"',
        }),
      },
    );
    await expect(unexpectedField).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_AUTHORING_MANUSCRIPT_RESPONSE',
    });

    const mismatch = api.updatePrivateManuscript(
      workId,
      { title: 'Title', author: '', language: 'vi' },
      '"manuscript-metadata-1"',
    );
    await flushCsrf(http);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}`).flush(
      { ...createdDraft, chapterCount: 0, metadataVersion: 2 },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({
          'Cache-Control': 'no-store',
          ETag: '"manuscript-metadata-3"',
        }),
      },
    );
    await expect(mismatch).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_AUTHORING_MANUSCRIPT_RESPONSE',
    });
  });

  it('rejects manuscript details with an unexpected body field, wrong ID or cacheable response', async () => {
    const unexpected = api.getPrivateManuscript(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}`).flush(
      {
        ...createdDraft,
        chapterCount: 0,
        metadataVersion: 1,
        normalizedContent: 'must not be present',
      },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', ETag: '"manuscript-metadata-1"' }),
      },
    );
    await expect(unexpected).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_AUTHORING_MANUSCRIPT_RESPONSE',
    });

    const wrongId = api.getPrivateManuscript(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}`).flush(
      {
        ...createdDraft,
        id: '22222222-2222-4222-8222-222222222222',
        chapterCount: 0,
        metadataVersion: 1,
      },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', ETag: '"manuscript-metadata-1"' }),
      },
    );
    await expect(wrongId).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_AUTHORING_MANUSCRIPT_RESPONSE',
    });

    const cacheable = api.getPrivateManuscript(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}`).flush(
      { ...createdDraft, chapterCount: 0, metadataVersion: 1 },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ ETag: '"manuscript-metadata-1"' }),
      },
    );
    await expect(cacheable).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_AUTHORING_MANUSCRIPT_RESPONSE',
    });
  });

  it('rejects malformed manuscript detail IDs before networking', async () => {
    await expect(api.getPrivateManuscript('not-a-uuid')).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_AUTHORING_QUERY',
    });
    http.expectNone((request) => request.url.includes('/authoring/manuscripts/'));
  });

  it('rejects malformed manuscript list entries and non-no-store responses', async () => {
    const invalidBody = api.listPrivateManuscripts();
    http.expectOne('/api/v1/authoring/manuscripts?limit=25').flush(
      {
        items: [
          {
            ...createdDraft,
            chapterCount: 0,
            sourceHash: 'must-not-be-present',
          },
        ],
        nextCursor: null,
      },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store' }),
      },
    );
    await expect(invalidBody).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_AUTHORING_MANUSCRIPT_LIST_RESPONSE',
    });

    const cacheableResponse = api.listPrivateManuscripts();
    http
      .expectOne('/api/v1/authoring/manuscripts?limit=25')
      .flush({ items: [], nextCursor: null }, { status: 200, statusText: 'OK' });
    await expect(cacheableResponse).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_AUTHORING_MANUSCRIPT_LIST_RESPONSE',
    });
  });

  it('rejects malformed cursors and out-of-range manuscript page sizes before networking', async () => {
    await expect(api.listPrivateManuscripts('bad cursor')).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_AUTHORING_QUERY',
    });
    await expect(api.listPrivateManuscripts(undefined, 101)).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_AUTHORING_QUERY',
    });
    http.expectNone('/api/v1/authoring/manuscripts?limit=25');
  });

  it('reads a strict, ordinal-ordered private outline page with an opaque cursor', async () => {
    const pending = api.listPrivateChapters(workId, 'YWJj.ZGVm', 25);
    const request = http.expectOne(
      '/api/v1/authoring/manuscripts/' + workId + '/chapters?limit=25&cursor=YWJj.ZGVm',
    );
    expect(request.request.method).toBe('GET');
    request.flush({
      items: [
        {
          id: chapterId,
          ordinal: 7,
          title: 'Chương thứ bảy',
          language: 'vi',
          status: 'draft',
          contentAvailable: false,
          hasSavedDraftContent: false,
          createdAt: chapterCreatedAt,
        },
      ],
      nextCursor: null,
    });

    await expect(pending).resolves.toMatchObject({
      items: [expect.objectContaining({ id: chapterId, ordinal: 7, status: 'draft' })],
      nextCursor: null,
    });
  });

  it('rejects bad cursor/limit requests before making HTTP calls', async () => {
    await expect(api.listPrivateChapters(workId, 'bad cursor')).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_AUTHORING_QUERY',
    });
    await expect(api.listPrivateChapters(workId, undefined, 101)).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_AUTHORING_QUERY',
    });
    http.expectNone((request) => request.url.includes('/chapters'));
  });

  it('rejects duplicate, unordered or body-bearing chapter outline responses', async () => {
    const pending = api.listPrivateChapters(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/chapters?limit=25`).flush({
      items: [
        {
          id: chapterId,
          ordinal: 2,
          title: 'Chương hai',
          language: 'vi',
          status: 'draft',
          contentAvailable: false,
          hasSavedDraftContent: false,
          createdAt: chapterCreatedAt,
          sourceText: 'private content must not be in this DTO',
        },
      ],
      nextCursor: null,
    });

    await expect(pending).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_AUTHORING_OUTLINE_RESPONSE',
    });
  });

  it('creates a blank chapter with trimmed title, session CSRF and the supplied idempotency key', async () => {
    const pending = api.createPrivateChapter(workId, ' Chương mở đầu ', idempotencyKey);
    await flushCsrf(http);

    const request = http.expectOne(`/api/v1/authoring/manuscripts/${workId}/chapters`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('Idempotency-Key')).toBe(idempotencyKey);
    expect(request.request.body).toEqual({ title: 'Chương mở đầu' });
    request.flush(
      {
        id: chapterId,
        workId,
        ordinal: 1,
        title: 'Chương mở đầu',
        language: 'vi',
        status: 'draft',
        createdAt: chapterCreatedAt,
      },
      { status: 201, statusText: 'Created' },
    );

    await expect(pending).resolves.toMatchObject({ id: chapterId, ordinal: 1, status: 'draft' });
  });

  it('validates blank chapter title, work ID and idempotency key before networking', async () => {
    await expect(api.createPrivateChapter(workId, '  ', idempotencyKey)).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_CHAPTER',
    });
    await expect(
      api.createPrivateChapter('not-a-uuid', 'Chương', idempotencyKey),
    ).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_CHAPTER',
    });
    await expect(api.createPrivateChapter(workId, 'Chương', 'short')).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_CHAPTER',
    });
    http.expectNone((request) => request.url.includes('/chapters'));
  });

  it('deletes an empty private chapter with session CSRF and validates the no-store 204', async () => {
    const pending = api.deletePrivateEmptyChapter(workId, chapterId);
    await flushCsrf(http);
    const request = http.expectOne(`/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}`);
    expect(request.request.method).toBe('DELETE');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.body).toBeNull();
    request.flush(null, {
      status: 204,
      statusText: 'No Content',
      headers: new HttpHeaders({ 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    });
    await expect(pending).resolves.toBeUndefined();
  });

  it('rejects invalid empty-chapter delete identifiers before networking', async () => {
    await expect(api.deletePrivateEmptyChapter('not-a-uuid', chapterId)).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_CHAPTER',
    });
    await expect(api.deletePrivateEmptyChapter(workId, 'not-a-uuid')).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_CHAPTER',
    });
    http.expectNone((request) => request.url.includes('/chapters/'));
  });

  it('counts chapter title limits in Unicode code points', async () => {
    const title = '😀'.repeat(300);
    const pending = api.createPrivateChapter(workId, title, idempotencyKey);
    await flushCsrf(http);
    const request = http.expectOne(`/api/v1/authoring/manuscripts/${workId}/chapters`);
    expect(request.request.body).toEqual({ title });
    request.flush(
      {
        id: chapterId,
        workId,
        ordinal: 1,
        title,
        language: 'vi',
        status: 'draft',
        createdAt: chapterCreatedAt,
      },
      { status: 201, statusText: 'Created' },
    );
    await expect(pending).resolves.toMatchObject({ title });

    await expect(
      api.createPrivateChapter(workId, '😀'.repeat(301), idempotencyKey),
    ).rejects.toMatchObject({ code: 'INVALID_CHAPTER', status: 400 });
    http.expectNone((request) => request.url.includes('/chapters'));
  });

  it('reads a strict private draft snapshot and binds the response ETag to revision zero', async () => {
    const pending = api.getPrivateChapterDraft(workId, chapterId);
    const request = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/draft`,
    );
    expect(request.request.method).toBe('GET');
    request.flush(
      {
        id: chapterId,
        workId,
        title: 'Chương một',
        status: 'draft',
        revisionNo: 0,
        sourceFormat: null,
        normalizedContent: '',
        segments: [],
        updatedAt: chapterCreatedAt,
      },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', ETag: '"draft-0"' }),
      },
    );

    await expect(pending).resolves.toMatchObject({
      id: chapterId,
      workId,
      revisionNo: 0,
      normalizedContent: '',
      etag: '"draft-0"',
    });
  });

  it('rejects a draft response that contains private metadata or an ETag/body version mismatch', async () => {
    const pending = api.getPrivateChapterDraft(workId, chapterId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/draft`).flush(
      {
        id: chapterId,
        workId,
        title: 'Chương một',
        status: 'draft',
        revisionNo: 1,
        sourceFormat: 'plain_text',
        normalizedContent: 'Nội dung riêng tư',
        segments: [
          {
            ordinal: 1,
            type: 'paragraph',
            sourceText: 'Nội dung riêng tư',
            stableAnchor: 'chapter-one-p1',
          },
        ],
        updatedAt: chapterCreatedAt,
        contentHash: 'secret',
      },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', ETag: '"draft-2"' }),
      },
    );
    await expect(pending).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_DRAFT_RESPONSE',
    });
  });

  it('saves a complete immutable revision with the supplied ETag, CSRF and stable retry key', async () => {
    const input = {
      sourceFormat: 'plain_text' as const,
      normalizedContent: 'Đêm xuống.\n\nÁnh trăng hiện ra.',
      segments: [
        {
          ordinal: 1,
          type: 'paragraph' as const,
          sourceText: 'Đêm xuống.',
          stableAnchor: 'chapter-one-p1',
        },
        {
          ordinal: 2,
          type: 'paragraph' as const,
          sourceText: 'Ánh trăng hiện ra.',
          stableAnchor: 'chapter-one-p2',
        },
      ],
    };
    const pending = api.savePrivateChapterDraftRevision(
      workId,
      chapterId,
      '"draft-0"',
      input,
      idempotencyKey,
    );
    await flushCsrf(http);

    const request = http.expectOne(
      `/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/revisions`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('If-Match')).toBe('"draft-0"');
    expect(request.request.headers.get('Idempotency-Key')).toBe(idempotencyKey);
    expect(request.request.body).toEqual(input);
    request.flush(
      {
        id: revisionId,
        chapterId,
        revisionNo: 1,
        sourceFormat: 'plain_text',
        createdAt: chapterCreatedAt,
      },
      {
        status: 201,
        statusText: 'Created',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', ETag: '"draft-1"' }),
      },
    );

    await expect(pending).resolves.toMatchObject({
      id: revisionId,
      revisionNo: 1,
      etag: '"draft-1"',
    });
  });

  it('preserves the authoritative ETag on a stale-version response', async () => {
    const input = {
      sourceFormat: 'plain_text' as const,
      normalizedContent: 'My version',
      segments: [],
    };
    const pending = api.savePrivateChapterDraftRevision(
      workId,
      chapterId,
      '"draft-2"',
      input,
      idempotencyKey,
    );
    await flushCsrf(http);
    http
      .expectOne(`/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/revisions`)
      .flush(
        { code: 'DRAFT_REVISION_STALE', currentEtag: '"draft-3"' },
        { status: 412, statusText: 'Precondition Failed' },
      );

    await expect(pending).rejects.toMatchObject({
      status: 412,
      code: 'DRAFT_REVISION_STALE',
      currentEtag: '"draft-3"',
    });
  });

  it('validates draft revision bounds and ordered unique segment anchors before networking', async () => {
    const base = {
      sourceFormat: 'plain_text' as const,
      normalizedContent: 'Body',
      segments: [
        {
          ordinal: 1,
          type: 'paragraph' as const,
          sourceText: 'Body',
          stableAnchor: 'chapter-p1',
        },
      ],
    };
    await expect(
      api.savePrivateChapterDraftRevision(workId, chapterId, 'W/"draft-0"', base, idempotencyKey),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_DRAFT_REVISION' });
    await expect(
      api.savePrivateChapterDraftRevision(
        workId,
        chapterId,
        '"draft-0"',
        {
          ...base,
          segments: [base.segments[0], { ...base.segments[0], ordinal: 1 }],
        },
        idempotencyKey,
      ),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_DRAFT_REVISION' });
    await expect(
      api.savePrivateChapterDraftRevision(workId, chapterId, '"draft-0"', base, 'short'),
    ).rejects.toMatchObject({ status: 400, code: 'INVALID_DRAFT_REVISION' });
    http.expectNone((request) => request.url.includes('/revisions'));
  });
});

async function flushCsrf(http: HttpTestingController): Promise<void> {
  http.expectOne('/api/v1/auth/csrf').flush({ token: csrfToken });
  await Promise.resolve();
  await Promise.resolve();
}
