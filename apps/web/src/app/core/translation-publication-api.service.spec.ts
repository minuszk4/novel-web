import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TranslationPublicationApiService } from './translation-publication-api.service';

const workId = '11111111-1111-4111-8111-111111111111';
const chapterId = '22222222-2222-4222-8222-222222222222';
const submissionId = '33333333-3333-4333-8333-333333333333';
const versionId = '44444444-4444-4444-8444-444444444444';
const currentVersionId = '55555555-5555-4555-8555-555555555555';
const digest = 'a'.repeat(64);
const etag = `"translation-review-${digest}"`;
const csrf = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const noStore = { 'Cache-Control': 'no-store', Pragma: 'no-cache' };
const acceptedItem = {
  submissionId,
  chapterId,
  workTitle: 'Tác phẩm thử',
  chapterTitle: 'Chương một',
  targetLanguage: 'en',
  snapshotDigest: digest,
  segmentCount: 2,
  submittedAt: '2026-11-17T07:00:00Z',
  rightsCurrent: true,
  currentVersionId: null,
  currentVersionNo: null,
};

describe('TranslationPublicationApiService', () => {
  let api: TranslationPublicationApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(TranslationPublicationApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads a bounded owner queue with no-store and opaque continuation', async () => {
    const cursor =
      'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB';
    const result = api.getAccepted(workId, cursor, 20);
    const request = http.expectOne(
      (candidate) =>
        candidate.url === `/api/v1/authoring/manuscripts/${workId}/translations/accepted` &&
        candidate.params.get('cursor') === cursor &&
        candidate.params.get('limit') === '20',
    );
    request.flush(
      { items: [acceptedItem], nextCursor: null, hasMore: false },
      { headers: noStore },
    );
    await expect(result).resolves.toMatchObject({ items: [{ submissionId }], hasMore: false });
  });

  it('releases without a body using session CSRF, strong ETag and exact idempotency key', async () => {
    const key = 'translation-release-test-key-0001';
    const result = api.release(submissionId, etag, key);
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne(
      `/api/v1/authoring/translations/review-submissions/${submissionId}/release`,
    );
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.headers.get('If-Match')).toBe(etag);
    expect(request.request.headers.get('Idempotency-Key')).toBe(key);
    request.flush(
      {
        versionId,
        versionNo: 1,
        snapshotDigest: digest,
        releasedAt: '2026-11-17T07:01:00Z',
        replayed: false,
      },
      { status: 201, statusText: 'Created', headers: { ...noStore, ETag: etag } },
    );
    await expect(result).resolves.toMatchObject({ versionId, versionNo: 1, replayed: false });
  });

  it('rejects a response with prose fields outside the owner receipt contract', async () => {
    const result = api.release(submissionId, etag, 'translation-release-test-key-0002');
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    http
      .expectOne(`/api/v1/authoring/translations/review-submissions/${submissionId}/release`)
      .flush(
        {
          versionId,
          versionNo: 1,
          snapshotDigest: digest,
          releasedAt: '2026-11-17T07:01:00Z',
          replayed: false,
          translatedText: 'must not be returned',
        },
        { status: 201, statusText: 'Created', headers: { ...noStore, ETag: etag } },
      );
    await expect(result).rejects.toMatchObject({ status: 502 });
  });

  it('loads metadata-only release history with current-version rollback binding', async () => {
    const result = api.getHistory(workId);
    http.expectOne(`/api/v1/authoring/manuscripts/${workId}/translations/history?limit=20`).flush(
      {
        items: [
          {
            eventId: '66666666-6666-4666-8666-666666666666',
            versionId,
            chapterId,
            chapterTitle: 'Chương một',
            targetLanguage: 'en',
            versionNo: 1,
            actionCode: 'PUBLISH',
            rollbackOfVersionId: null,
            releasedAt: '2026-11-17T07:01:00Z',
            current: false,
            currentVersionId,
            currentVersionNo: 2,
            currentVersionEtag: `"translation-version-${currentVersionId}-2"`,
            rollbackAllowed: true,
          },
        ],
        nextCursor: null,
        hasMore: false,
      },
      { headers: noStore },
    );
    await expect(result).resolves.toMatchObject({
      items: [{ versionId, rollbackAllowed: true, currentVersionNo: 2 }],
    });
  });

  it('restores a retained version with CSRF, current ETag and no request body', async () => {
    const key = 'translation-rollback-test-key-0001';
    const currentEtag = `"translation-version-${currentVersionId}-2"`;
    const result = api.rollback(versionId, currentEtag, key);
    http.expectOne('/api/v1/auth/csrf').flush({ token: csrf });
    await new Promise((resolve) => setTimeout(resolve, 0));
    const request = http.expectOne(`/api/v1/authoring/translations/versions/${versionId}/rollback`);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrf);
    expect(request.request.headers.get('If-Match')).toBe(currentEtag);
    expect(request.request.headers.get('Idempotency-Key')).toBe(key);
    request.flush(
      {
        versionId: currentVersionId,
        versionNo: 3,
        snapshotDigest: digest,
        releasedAt: '2026-11-17T07:02:00Z',
        replayed: false,
      },
      {
        status: 201,
        statusText: 'Created',
        headers: { ...noStore, ETag: etag },
      },
    );
    await expect(result).resolves.toMatchObject({ versionNo: 3, replayed: false });
  });
});
