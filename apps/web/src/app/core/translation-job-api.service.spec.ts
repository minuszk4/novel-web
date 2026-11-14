import { provideHttpClient } from '@angular/common/http';
import { HttpHeaders } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TranslationJobApiError, TranslationJobApiService } from './translation-job-api.service';

const jobId = '66666666-6666-4666-8666-666666666666';
const workId = '33333333-3333-4333-8333-333333333333';
const chapterId = '77777777-7777-4777-8777-777777777777';
const revisionId = '88888888-8888-4888-8888-888888888888';
const candidateId = '99999999-9999-4999-8999-999999999999';
const sourceSegmentId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const idempotencyKey = 'translation-preview-key-0001';

const status = {
  job: {
    id: jobId,
    workId,
    chapterId,
    sourceRevisionId: revisionId,
    sourceRevisionCurrent: true,
    workTitle: 'Tác phẩm riêng tư',
    chapterTitle: 'Chương một',
    chapterOrdinal: 1,
    canManage: true,
    sourceLanguage: 'vi',
    targetLanguage: 'en',
    providerId: 'fake-local',
    status: 'translating',
    totalUnits: 2,
    completedUnits: 1,
    failedUnits: 0,
    queuedUnits: 1,
    runningUnits: 0,
    retryWaitUnits: 0,
    cancelledUnits: 0,
    failureCode: null,
    candidateId: null,
    candidateStatus: null,
    createdAt: '2026-11-14T10:00:00Z',
    updatedAt: '2026-11-14T10:00:01Z',
    startedAt: '2026-11-14T10:00:01Z',
    completedAt: null,
    cancelRequestedAt: null,
  },
  terminalUnits: 1,
  progressPercent: 50,
};

describe('TranslationJobApiService', () => {
  let api: TranslationJobApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(TranslationJobApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('enqueues with session CSRF and one idempotency key, returning the opaque job location', async () => {
    const result = api.enqueue(chapterId, revisionId, 'en', idempotencyKey);
    await flushCsrf(http);

    const request = http.expectOne('/api/v1/translation/jobs');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      chapterId,
      sourceRevisionId: revisionId,
      targetLanguage: 'en',
    });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('Idempotency-Key')).toBe(idempotencyKey);
    request.flush(
      {
        jobId,
        status: 'queued',
        chapterId,
        sourceRevisionId: revisionId,
        sourceLanguage: 'vi',
        targetLanguage: 'en',
        providerId: 'fake-local',
        totalUnits: 2,
        replayed: false,
      },
      { headers: new HttpHeaders({ Location: `/api/v1/translation/jobs/${jobId}` }) },
    );

    await expect(result).resolves.toEqual({
      jobId,
      replayed: false,
      location: `/api/v1/translation/jobs/${jobId}`,
    });
  });

  it('validates metadata-only status and accepts the server poll interval', async () => {
    const result = api.getStatus(jobId);
    const request = http.expectOne(`/api/v1/translation/jobs/${jobId}`);
    expect(request.request.method).toBe('GET');
    request.flush(status, { headers: new HttpHeaders({ 'Retry-After': '2' }) });

    await expect(result).resolves.toEqual({ status, retryAfterSeconds: 2 });
  });

  it('loads signed-cursor pages of writer-only job metadata and requires no-store', async () => {
    const nextCursor = `${'A'.repeat(60)}.${'B'.repeat(43)}`;
    const firstPage = api.getManageableJobs(null, 1);
    const firstRequest = http.expectOne(
      (request) =>
        request.url === '/api/v1/translation/jobs' && request.params.get('limit') === '1',
    );
    expect(firstRequest.request.method).toBe('GET');
    expect(firstRequest.request.params.has('cursor')).toBe(false);
    firstRequest.flush(
      { items: [status], nextCursor },
      { headers: new HttpHeaders({ 'Cache-Control': 'no-store' }) },
    );
    await expect(firstPage).resolves.toEqual({ items: [status], nextCursor });

    const secondPage = api.getManageableJobs(nextCursor, 1);
    const secondRequest = http.expectOne(
      (request) =>
        request.url === '/api/v1/translation/jobs' &&
        request.params.get('limit') === '1' &&
        request.params.get('cursor') === nextCursor,
    );
    secondRequest.flush(
      { items: [], nextCursor: null },
      { headers: new HttpHeaders({ 'Cache-Control': 'no-store' }) },
    );
    await expect(secondPage).resolves.toEqual({ items: [], nextCursor: null });
  });

  it('rejects body-bearing, non-writer, or cacheable list responses', async () => {
    const leaked = structuredClone(status) as typeof status & { job: { sourceText?: string } };
    leaked.job.sourceText = 'private chapter text';
    const bodyBearing = api.getManageableJobs();
    http
      .expectOne('/api/v1/translation/jobs?limit=25')
      .flush(
        { items: [leaked], nextCursor: null },
        { headers: new HttpHeaders({ 'Cache-Control': 'no-store' }) },
      );
    await expect(bodyBearing).rejects.toMatchObject({ code: 'INVALID_TRANSLATION_RESPONSE' });

    const nonWriter = api.getManageableJobs();
    http
      .expectOne('/api/v1/translation/jobs?limit=25')
      .flush(
        { items: [{ ...status, job: { ...status.job, canManage: false } }], nextCursor: null },
        { headers: new HttpHeaders({ 'Cache-Control': 'no-store' }) },
      );
    await expect(nonWriter).rejects.toMatchObject({ code: 'INVALID_TRANSLATION_RESPONSE' });

    const duplicate = api.getManageableJobs(null, 2);
    http
      .expectOne('/api/v1/translation/jobs?limit=2')
      .flush(
        { items: [status, status], nextCursor: null },
        { headers: new HttpHeaders({ 'Cache-Control': 'no-store' }) },
      );
    await expect(duplicate).rejects.toMatchObject({ code: 'INVALID_TRANSLATION_RESPONSE' });

    const cacheable = api.getManageableJobs();
    http.expectOne('/api/v1/translation/jobs?limit=25').flush({ items: [], nextCursor: null });
    await expect(cacheable).rejects.toMatchObject({ code: 'INVALID_TRANSLATION_RESPONSE' });
  });

  it('rejects malformed local cursors and out-of-range page sizes without a request', async () => {
    await expect(api.getManageableJobs('not-signed')).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_TRANSLATION_REQUEST',
    });
    await expect(api.getManageableJobs(null, 51)).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_TRANSLATION_REQUEST',
    });
    http.expectNone('/api/v1/translation/jobs');
  });

  it('loads a bounded exact-source fake candidate page and requires no-store', async () => {
    const result = api.getCandidatePreview(jobId, 10, 10);
    const request = http.expectOne(
      (candidate) =>
        candidate.url === `/api/v1/translation/jobs/${jobId}/candidate` &&
        candidate.params.get('offset') === '10' &&
        candidate.params.get('limit') === '10',
    );
    expect(request.request.method).toBe('GET');
    const body = {
      jobId,
      candidateId,
      sourceRevisionId: revisionId,
      sourceRevisionCurrent: true,
      sourceLanguage: 'vi',
      targetLanguage: 'en',
      providerId: 'fake-local',
      candidateStatus: 'ready_for_review',
      pageOffset: 10,
      pageLimit: 10,
      totalSegments: 11,
      segments: [
        {
          sourceSegmentId,
          ordinal: 11,
          sourceType: 'paragraph',
          sourceText: 'Nội dung nguyên tác.',
          fakeResultText: '[fake vi->en] Nội dung nguyên tác.',
          editedText: null,
          editRevision: 0,
          editEtag: '"edit-0"',
        },
      ],
    };
    request.flush(body, { headers: new HttpHeaders({ 'Cache-Control': 'no-store' }) });

    await expect(result).resolves.toEqual(body);
  });

  it('rejects stale, unmarked or body-bearing candidate responses and invalid pages', async () => {
    const invalidPage = api.getCandidatePreview(jobId, 5001, 10);
    await expect(invalidPage).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_TRANSLATION_REQUEST',
    });
    http.expectNone((candidate) => candidate.url.includes('/candidate'));

    const stale = api.getCandidatePreview(jobId);
    http
      .expectOne((candidate) => candidate.url.includes('/candidate'))
      .flush(
        {
          jobId,
          candidateId,
          sourceRevisionId: revisionId,
          sourceRevisionCurrent: false,
          sourceLanguage: 'vi',
          targetLanguage: 'en',
          providerId: 'fake-local',
          candidateStatus: 'ready_for_review',
          pageOffset: 0,
          pageLimit: 10,
          totalSegments: 1,
          segments: [
            {
              sourceSegmentId,
              ordinal: 1,
              sourceType: 'paragraph',
              sourceText: 'Nội dung nguyên tác.',
              fakeResultText: '[fake vi->en] Nội dung nguyên tác.',
              editedText: null,
              editRevision: 0,
              editEtag: '"edit-0"',
            },
          ],
        },
        { headers: new HttpHeaders({ 'Cache-Control': 'no-store' }) },
      );
    await expect(stale).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_TRANSLATION_RESPONSE',
    });

    const bodyBearing = api.getCandidatePreview(jobId);
    http
      .expectOne((candidate) => candidate.url.includes('/candidate'))
      .flush(
        {
          jobId,
          candidateId,
          sourceRevisionId: revisionId,
          sourceRevisionCurrent: true,
          sourceLanguage: 'vi',
          targetLanguage: 'en',
          providerId: 'fake-local',
          candidateStatus: 'ready_for_review',
          pageOffset: 0,
          pageLimit: 10,
          totalSegments: 1,
          ownerId: 'must-not-appear',
          segments: [
            {
              sourceSegmentId,
              ordinal: 1,
              sourceType: 'paragraph',
              sourceText: 'Nội dung nguyên tác.',
              fakeResultText: '[fake vi->en] Nội dung nguyên tác.',
              editedText: null,
              editRevision: 0,
              editEtag: '"edit-0"',
            },
          ],
        },
        { headers: new HttpHeaders({ 'Cache-Control': 'no-store' }) },
      );
    await expect(bodyBearing).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_TRANSLATION_RESPONSE',
    });

    const unmarked = api.getCandidatePreview(jobId);
    http
      .expectOne((candidate) => candidate.url.includes('/candidate'))
      .flush(
        {
          jobId,
          candidateId,
          sourceRevisionId: revisionId,
          sourceRevisionCurrent: true,
          sourceLanguage: 'vi',
          targetLanguage: 'en',
          providerId: 'fake-local',
          candidateStatus: 'ready_for_review',
          pageOffset: 0,
          pageLimit: 10,
          totalSegments: 1,
          segments: [
            {
              sourceSegmentId,
              ordinal: 1,
              sourceType: 'paragraph',
              sourceText: 'Nội dung nguyên tác.',
              fakeResultText: 'Nội dung nguyên tác.',
              editedText: null,
              editRevision: 0,
              editEtag: '"edit-0"',
            },
          ],
        },
        { headers: new HttpHeaders({ 'Cache-Control': 'no-store' }) },
      );
    await expect(unmarked).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_TRANSLATION_RESPONSE',
    });

    const cacheable = api.getCandidatePreview(jobId);
    http
      .expectOne((candidate) => candidate.url.includes('/candidate'))
      .flush({
        jobId,
        candidateId,
        sourceRevisionId: revisionId,
        sourceRevisionCurrent: true,
        sourceLanguage: 'vi',
        targetLanguage: 'en',
        providerId: 'fake-local',
        candidateStatus: 'ready_for_review',
        pageOffset: 0,
        pageLimit: 10,
        totalSegments: 1,
        segments: [
          {
            sourceSegmentId,
            ordinal: 1,
            sourceType: 'paragraph',
            sourceText: 'Nội dung nguyên tác.',
            fakeResultText: '[fake vi->en] Nội dung nguyên tác.',
            editedText: null,
            editRevision: 0,
            editEtag: '"edit-0"',
          },
        ],
      });
    await expect(cacheable).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_TRANSLATION_RESPONSE',
    });
  });

  it('saves a private candidate edit with fresh CSRF, ETag and idempotency headers', async () => {
    const translatedText = 'Bản biên tập của tác giả.';
    const result = api.saveCandidateSegmentEdit(
      jobId,
      sourceSegmentId,
      translatedText,
      '"edit-0"',
      idempotencyKey,
    );
    await flushCsrf(http);
    const request = http.expectOne(
      `/api/v1/translation/jobs/${jobId}/candidate/segments/${sourceSegmentId}/edit`,
    );
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ translatedText });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('If-Match')).toBe('"edit-0"');
    expect(request.request.headers.get('Idempotency-Key')).toBe(idempotencyKey);
    request.flush(
      {
        jobId,
        candidateId,
        sourceSegmentId,
        editRevision: 1,
        translatedText,
        createdAt: '2026-11-15T10:00:00Z',
        replayed: false,
      },
      {
        status: 201,
        statusText: 'Created',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', ETag: '"edit-1"' }),
      },
    );

    await expect(result).resolves.toEqual({
      jobId,
      candidateId,
      sourceSegmentId,
      editRevision: 1,
      translatedText,
      createdAt: '2026-11-15T10:00:00Z',
      replayed: false,
      editEtag: '"edit-1"',
    });

    const replay = api.saveCandidateSegmentEdit(
      jobId,
      sourceSegmentId,
      translatedText,
      '"edit-0"',
      idempotencyKey,
    );
    await flushCsrf(http);
    const replayRequest = http.expectOne(
      `/api/v1/translation/jobs/${jobId}/candidate/segments/${sourceSegmentId}/edit`,
    );
    replayRequest.flush(
      {
        jobId,
        candidateId,
        sourceSegmentId,
        editRevision: 1,
        translatedText,
        createdAt: '2026-11-15T10:00:00Z',
        replayed: true,
      },
      {
        status: 200,
        statusText: 'OK',
        headers: new HttpHeaders({ 'Cache-Control': 'no-store', ETag: '"edit-1"' }),
      },
    );
    await expect(replay).resolves.toMatchObject({ replayed: true, editEtag: '"edit-1"' });
  });

  it('rejects invalid edit input locally and exposes the server ETag for stale resolution', async () => {
    await expect(
      api.saveCandidateSegmentEdit(jobId, sourceSegmentId, ' \n ', '"edit-0"', idempotencyKey),
    ).rejects.toMatchObject({ code: 'INVALID_TRANSLATION_EDIT' });
    await expect(
      api.saveCandidateSegmentEdit(jobId, sourceSegmentId, 'text', 'W/"edit-0"', idempotencyKey),
    ).rejects.toMatchObject({ code: 'INVALID_TRANSLATION_EDIT' });
    await expect(
      api.saveCandidateSegmentEdit(
        jobId,
        sourceSegmentId,
        '😀'.repeat(262_145),
        '"edit-0"',
        idempotencyKey,
      ),
    ).rejects.toMatchObject({ code: 'INVALID_TRANSLATION_EDIT' });
    http.expectNone((request) => request.url.includes('/edit'));

    const result = api.saveCandidateSegmentEdit(
      jobId,
      sourceSegmentId,
      'Bản sửa',
      '"edit-0"',
      idempotencyKey,
    );
    await flushCsrf(http);
    http
      .expectOne((request) => request.url.includes('/edit'))
      .flush(
        {
          code: 'TRANSLATION_EDIT_STALE',
          currentEtag: '"edit-2"',
        },
        { status: 412, statusText: 'Precondition Failed' },
      );
    await expect(result).rejects.toMatchObject({
      status: 412,
      code: 'TRANSLATION_EDIT_STALE',
      currentEtag: '"edit-2"',
    });
  });

  it('uses CSRF and the same idempotency contract for writer retry and cancellation', async () => {
    const retry = api.retry(jobId, idempotencyKey);
    await flushCsrf(http);
    const retryRequest = http.expectOne(`/api/v1/translation/jobs/${jobId}/retry`);
    expect(retryRequest.request.method).toBe('POST');
    expect(retryRequest.request.body).toBeNull();
    expect(retryRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(retryRequest.request.headers.get('Idempotency-Key')).toBe(idempotencyKey);
    const retryReceipt = {
      jobId,
      jobStatus: 'queued',
      retriedUnits: 1,
      replayed: false,
      outboxEventId: revisionId,
    };
    retryRequest.flush(retryReceipt);

    const cancel = api.cancel(jobId, idempotencyKey);
    await flushCsrf(http);
    const cancelRequest = http.expectOne(`/api/v1/translation/jobs/${jobId}/cancel`);
    expect(cancelRequest.request.method).toBe('POST');
    expect(cancelRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(cancelRequest.request.headers.get('Idempotency-Key')).toBe(idempotencyKey);
    const cancelReceipt = {
      jobId,
      jobStatus: 'cancelled',
      cancelledUnits: 1,
      cancelledAttempts: 0,
      replayed: false,
      outboxEventId: workId,
    };
    cancelRequest.flush(cancelReceipt);

    await expect(retry).resolves.toMatchObject(retryReceipt);
    await expect(cancel).resolves.toMatchObject(cancelReceipt);
  });

  it('rejects malformed response data and maps stable API problem codes', async () => {
    const malformed = api.getStatus(jobId);
    http
      .expectOne(`/api/v1/translation/jobs/${jobId}`)
      .flush({ ...status, job: { ...status.job, canManage: 'yes' } });
    await expect(malformed).rejects.toMatchObject({ code: 'INVALID_TRANSLATION_RESPONSE' });

    const unavailable = api.getStatus(jobId);
    http
      .expectOne(`/api/v1/translation/jobs/${jobId}`)
      .flush({ code: 'TRANSLATION_JOB_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await expect(unavailable).rejects.toBeInstanceOf(TranslationJobApiError);
    await expect(unavailable).rejects.toMatchObject({
      status: 404,
      code: 'TRANSLATION_JOB_NOT_FOUND',
    });
  });
});

async function flushCsrf(http: HttpTestingController): Promise<void> {
  http.expectOne('/api/v1/auth/csrf').flush({ token: csrfToken });
  await Promise.resolve();
  await Promise.resolve();
}
