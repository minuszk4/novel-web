import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TranslationReviewApiService } from './translation-review-api.service';

const id = '11111111-1111-4111-8111-111111111111';
const candidateId = '22222222-2222-4222-8222-222222222222';
const revisionId = '33333333-3333-4333-8333-333333333333';
const segmentId = '44444444-4444-4444-8444-444444444444';
const digest = 'a'.repeat(64);
const csrf = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const etag = `"translation-review-${digest}"`;
const noStore = { 'Cache-Control': 'no-store', Pragma: 'no-cache' };

const readiness = {
  jobId: id,
  candidateId,
  sourceRevisionId: revisionId,
  sourceRevisionCurrent: true,
  sourceLanguage: 'vi',
  targetLanguage: 'en',
  editedSegments: 1,
  totalSegments: 1,
  missingSegmentOrdinals: [],
  pendingReview: false,
  snapshotDigest: digest,
  readyForSubmission: true,
  blockers: [],
};

const queueItem = {
  submissionId: id,
  workTitle: 'Tác phẩm mẫu',
  chapterTitle: 'Chương một',
  sourceLanguage: 'vi',
  targetLanguage: 'en',
  segmentCount: 1,
  submitterDisplayName: 'Người viết',
  submittedAt: '2026-11-17T07:00:00Z',
  sourceCurrent: true,
};

const detail = {
  submissionId: id,
  status: 'pending_review',
  workTitle: 'Tác phẩm mẫu',
  chapterTitle: 'Chương một',
  submitterDisplayName: 'Người viết',
  sourceLanguage: 'vi',
  targetLanguage: 'en',
  snapshotDigest: digest,
  segmentCount: 1,
  submittedAt: '2026-11-17T07:00:00Z',
  canDecide: true,
  segments: [
    {
      ordinal: 1,
      editRevision: 1,
      translatedSha256: digest,
      sourceText: 'Nguyên tác.',
      submittedText: 'Submitted translation.',
    },
  ],
};

describe('TranslationReviewApiService', () => {
  let api: TranslationReviewApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(TranslationReviewApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads readiness with no-store and a strong snapshot validator', async () => {
    const result = api.getReadiness(id);
    http.expectOne(`/api/v1/translation/jobs/${id}/candidate/review-readiness`).flush(readiness, {
      headers: { ...noStore, ETag: etag },
    });
    await expect(result).resolves.toMatchObject({ readiness: { readyForSubmission: true }, etag });
  });

  it('submits with session CSRF, the readiness ETag and stable idempotency key', async () => {
    const key = 'translation-review-submit-0001';
    const result = api.submit(id, etag, key);
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne(`/api/v1/translation/jobs/${id}/candidate/review-submissions`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.headers.get('If-Match')).toBe(etag);
    expect(request.request.headers.get('Idempotency-Key')).toBe(key);
    request.flush(
      {
        submissionId: id,
        candidateId,
        status: 'pending_review',
        snapshotDigest: digest,
        segmentCount: 1,
        submittedAt: '2026-11-17T07:00:00Z',
        replayed: false,
      },
      { status: 201, statusText: 'Created', headers: { ...noStore, ETag: etag } },
    );
    await expect(result).resolves.toMatchObject({ submissionId: id, replayed: false });
  });

  it('loads a bounded reviewer queue and validates a no-store response', async () => {
    const result = api.getQueue(null, 20);
    const request = http.expectOne(
      (candidate) =>
        candidate.url === '/api/v1/review/translations' && candidate.params.get('limit') === '20',
    );
    request.flush({ items: [queueItem], nextCursor: null, hasMore: false }, { headers: noStore });
    await expect(result).resolves.toMatchObject({ items: [{ submissionId: id }], hasMore: false });
  });

  it('loads the frozen detail and rejects an unexpected prose or metadata field', async () => {
    const invalid = api.getDetail(id);
    http
      .expectOne(`/api/v1/review/translations/${id}`)
      .flush({ ...detail, privateNote: 'unexpected' }, { headers: { ...noStore, ETag: etag } });
    await expect(invalid).rejects.toMatchObject({
      code: 'INVALID_TRANSLATION_REVIEW_DETAIL_RESPONSE',
    });

    const result = api.getDetail(id);
    http.expectOne(`/api/v1/review/translations/${id}`).flush(detail, {
      headers: { ...noStore, ETag: etag },
    });
    await expect(result).resolves.toMatchObject({
      detail: { segments: [{ submittedText: 'Submitted translation.' }] },
      etag,
    });
  });

  it('posts an allow-listed decision and validates the immutable receipt', async () => {
    const key = 'translation-review-decision-0001';
    const result = api.decide(id, etag, key, 'REJECT', 'MEANING_ERROR');
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne(`/api/v1/review/translations/${id}/decisions`);
    expect(request.request.body).toEqual({ actionCode: 'REJECT', reasonCode: 'MEANING_ERROR' });
    expect(request.request.headers.get('If-Match')).toBe(etag);
    expect(request.request.headers.get('Idempotency-Key')).toBe(key);
    request.flush(
      {
        decisionId: revisionId,
        submissionId: id,
        actionCode: 'REJECT',
        reasonCode: 'MEANING_ERROR',
        snapshotDigest: digest,
        decidedAt: '2026-11-17T07:05:00Z',
        replayed: false,
      },
      { status: 201, statusText: 'Created', headers: { ...noStore, ETag: etag } },
    );
    await expect(result).resolves.toMatchObject({
      actionCode: 'REJECT',
      reasonCode: 'MEANING_ERROR',
    });
  });

  it('rejects reason/action mismatches before requesting CSRF', async () => {
    await expect(
      api.decide(id, etag, 'translation-review-key-0001', 'REJECT', 'ACCURATE'),
    ).rejects.toMatchObject({
      code: 'INVALID_TRANSLATION_REVIEW_DECISION',
    });
    http.expectNone('/api/v1/auth/csrf');
  });
});
