import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  PublicationReviewApiError,
  PublicationReviewApiService,
} from './publication-review-api.service';

const requestId = '11111111-1111-4111-8111-111111111111';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const etag = `"publication-review-${'a'.repeat(64)}"`;
const sha256 = 'b'.repeat(64);

const queueItem = {
  requestId,
  targetChapterId: null,
  requestVersion: 1,
  submittedAt: '2026-11-16T10:00:00Z',
  policyVersion: 'rights-policy-1',
  snapshotSha256: sha256,
  title: 'The Quiet Mountain',
  author: 'An Example',
  sourceLanguage: 'vi',
  scopeCodes: [
    'PUBLIC_METADATA',
    'CHAPTER_TEXT',
    'COMMERCIAL_SALE',
    'FREE_PREVIEW',
    'TRANSLATION_ADAPTATION',
  ],
};

const detail = {
  requestId,
  targetChapterId: null,
  requestVersion: 1,
  submittedAt: '2026-11-16T10:00:00Z',
  policyVersion: 'rights-policy-1',
  state: 'PENDING_REVIEW',
  snapshotSha256: sha256,
  scopes: [
    {
      scopeCode: 'PUBLIC_METADATA',
      snapshotSha256: sha256,
      previewPolicyVersion: null,
      previewPercent: null,
      measurement: null,
      work: { title: 'The Quiet Mountain', author: 'An Example', sourceLanguage: 'vi' },
      chapters: [],
    },
    {
      scopeCode: 'CHAPTER_TEXT',
      snapshotSha256: sha256,
      previewPolicyVersion: null,
      previewPercent: null,
      measurement: null,
      work: { title: 'The Quiet Mountain', author: 'An Example', sourceLanguage: 'vi' },
      chapters: [
        {
          chapterId: '22222222-2222-4222-8222-222222222222',
          ordinal: 1,
          title: 'First Steps',
          sourceLanguage: 'vi',
          contentHash: sha256,
          segmentCount: 8,
        },
      ],
    },
    {
      scopeCode: 'COMMERCIAL_SALE',
      snapshotSha256: sha256,
      previewPolicyVersion: null,
      previewPercent: null,
      measurement: null,
      work: { title: 'The Quiet Mountain', author: 'An Example', sourceLanguage: 'vi' },
      chapters: [],
    },
    {
      scopeCode: 'TRANSLATION_ADAPTATION',
      snapshotSha256: sha256,
      previewPolicyVersion: null,
      previewPercent: null,
      measurement: null,
      work: { title: 'The Quiet Mountain', author: 'An Example', sourceLanguage: 'vi' },
      chapters: [
        {
          chapterId: '22222222-2222-4222-8222-222222222222',
          ordinal: 1,
          title: 'First Steps',
          sourceLanguage: 'vi',
          contentHash: sha256,
          segmentCount: 8,
        },
      ],
    },
    {
      scopeCode: 'FREE_PREVIEW',
      snapshotSha256: sha256,
      previewPolicyVersion: 'free-preview-v1',
      previewPercent: 10,
      measurement: 'UNICODE_CODE_POINTS',
      work: { title: 'The Quiet Mountain', author: 'An Example', sourceLanguage: 'vi' },
      chapters: [
        {
          chapterId: '22222222-2222-4222-8222-222222222222',
          ordinal: 1,
          title: 'First Steps',
          sourceLanguage: 'vi',
          contentHash: sha256,
          segmentCount: 8,
        },
      ],
    },
  ],
  claims: [
    { scopeCode: 'PUBLIC_METADATA', rightsBasisCode: 'ORIGINAL_WORK' },
    { scopeCode: 'CHAPTER_TEXT', rightsBasisCode: 'ORIGINAL_WORK' },
    { scopeCode: 'COMMERCIAL_SALE', rightsBasisCode: 'ORIGINAL_WORK' },
    { scopeCode: 'FREE_PREVIEW', rightsBasisCode: 'ORIGINAL_WORK' },
    { scopeCode: 'TRANSLATION_ADAPTATION', rightsBasisCode: 'ORIGINAL_WORK' },
  ],
  events: [
    {
      sequence: 1,
      actorRoleCode: 'author',
      actionCode: 'SUBMITTED',
      fromState: null,
      toState: 'PENDING_REVIEW',
      reasonCode: null,
      createdAt: '2026-11-16T10:00:00Z',
    },
  ],
};

describe('PublicationReviewApiService', () => {
  let api: PublicationReviewApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(PublicationReviewApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests a bounded keyset page and parses only the queue contract fields', async () => {
    const result = api.getQueue('eyJjdXJzb3IifQ.signature', 10);
    const request = http.expectOne(
      (candidate) =>
        candidate.url === '/api/v1/review/publications' &&
        candidate.params.get('limit') === '10' &&
        candidate.params.get('cursor') === 'eyJjdXJzb3IifQ.signature',
    );
    expect(request.request.method).toBe('GET');
    request.flush({ items: [queueItem], nextCursor: null });
    await expect(result).resolves.toMatchObject({ items: [{ requestId }], nextCursor: null });
  });

  it('refuses queue response fields outside the privacy-filtered contract', async () => {
    const result = api.getQueue();
    http
      .expectOne((candidate) => candidate.url === '/api/v1/review/publications')
      .flush({ items: [{ ...queueItem, ownerId: requestId }], nextCursor: null });
    await expect(result).rejects.toMatchObject({ status: 0 });
  });

  it('requires the strong detail ETag and rejects a prose field', async () => {
    const missingEtag = api.getDetail(requestId);
    http.expectOne(`/api/v1/review/publications/${requestId}`).flush(detail);
    await expect(missingEtag).rejects.toMatchObject({ code: 'INVALID_REVIEW_ETAG' });

    const proseResponse = api.getDetail(requestId);
    http
      .expectOne(`/api/v1/review/publications/${requestId}`)
      .flush({ ...detail, prose: 'must never be rendered' }, { headers: { ETag: etag } });
    await expect(proseResponse).rejects.toMatchObject({ status: 0 });

    const valid = api.getDetail(requestId);
    http
      .expectOne(`/api/v1/review/publications/${requestId}`)
      .flush(detail, { headers: { ETag: etag } });
    await expect(valid).resolves.toMatchObject({ detail: { requestId }, etag });
  });

  it('sends a reason-coded decision with fresh session CSRF, the detail ETag and exact idempotency key', async () => {
    const key = 'review-decision-attempt-0001';
    const result = api.decide(requestId, etag, key, 'APPROVED', 'RIGHTS_REVIEWED');
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrfToken });
    await Promise.resolve();
    const request = http.expectOne(`/api/v1/review/publications/${requestId}/decisions`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      actionCode: 'APPROVED',
      reasonCode: 'RIGHTS_REVIEWED',
    });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('If-Match')).toBe(etag);
    expect(request.request.headers.get('Idempotency-Key')).toBe(key);
    request.flush(
      {
        eventSequence: 2,
        actorRoleCode: 'publication_reviewer',
        actionCode: 'APPROVED',
        fromState: 'PENDING_REVIEW',
        toState: 'APPROVED_UNPUBLISHED',
        reasonCode: 'RIGHTS_REVIEWED',
        decidedAt: '2026-11-16T10:05:00Z',
      },
      { status: 201, statusText: 'Created' },
    );
    await expect(result).resolves.toMatchObject({
      actionCode: 'APPROVED',
      toState: 'APPROVED_UNPUBLISHED',
      reasonCode: 'RIGHTS_REVIEWED',
    });
  });

  it('rejects action/reason mismatches before any network request', async () => {
    await expect(
      api.decide(requestId, etag, 'review-decision-attempt-0001', 'APPROVED', 'OUT_OF_SCOPE'),
    ).rejects.toMatchObject({ code: 'INVALID_REVIEW_DECISION' });
    http.expectNone('/api/v1/auth/csrf');
  });

  it('preserves conflict code and Retry-After for explicit stale-state recovery', async () => {
    const result = api.getDetail(requestId);
    http.expectOne(`/api/v1/review/publications/${requestId}`).flush(
      { code: 'PUBLICATION_REVIEW_ETAG_STALE' },
      {
        status: 412,
        statusText: 'Precondition Failed',
        headers: { 'Retry-After': '90' },
      },
    );
    await expect(result).rejects.toBeInstanceOf(PublicationReviewApiError);
    await expect(result).rejects.toMatchObject({
      status: 412,
      code: 'PUBLICATION_REVIEW_ETAG_STALE',
      retryAfterSeconds: 90,
    });
  });
});
