import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ChapterUnlockApiService } from './chapter-unlock-api.service';

const chapterId = '77777777-7777-4777-8777-777777777777';
const workId = '66666666-6666-4666-8666-666666666666';
const entitlementId = '99999999-9999-4999-8999-999999999999';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const quote = {
  chapterId,
  workId,
  chapterTitle: 'Chương khóa',
  workTitle: 'Gió qua núi',
  priceVersion: 1,
  priceCredits: 10,
  accessAlreadyGranted: false,
};

async function flushCsrf(http: HttpTestingController): Promise<void> {
  http.expectOne('/api/v1/auth/csrf').flush({ token: csrfToken });
  await Promise.resolve();
  await Promise.resolve();
}

describe('ChapterUnlockApiService', () => {
  let api: ChapterUnlockApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ChapterUnlockApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads and validates the current server-owned quote', async () => {
    const result = api.getQuote(chapterId);
    http.expectOne(`/api/v1/chapters/${chapterId}/unlock-quote`).flush(quote);
    await expect(result).resolves.toEqual(quote);
  });

  it('sends only the quote version with CSRF and idempotency headers', async () => {
    const result = api.unlock(chapterId, quote.priceVersion, 'unlock-attempt-0001');
    await flushCsrf(http);
    const request = http.expectOne(`/api/v1/chapters/${chapterId}/unlock`);
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.headers.get('Idempotency-Key')).toBe('unlock-attempt-0001');
    expect(request.request.body).toEqual({ priceVersion: 1 });
    request.flush(
      {
        outcome: 'CREATED',
        entitlementId,
        chapterId,
        priceVersion: 1,
        creditsSpent: 10,
        grantedAt: '2026-11-15T10:00:00Z',
      },
      { status: 201, statusText: 'Created' },
    );
    await expect(result).resolves.toMatchObject({
      outcome: 'CREATED',
      chapterId,
      creditsSpent: 10,
    });
  });

  it('rejects an unlock result for another chapter or quote version', async () => {
    const result = api.unlock(chapterId, 1, 'unlock-attempt-0002');
    await flushCsrf(http);
    http.expectOne(`/api/v1/chapters/${chapterId}/unlock`).flush({
      outcome: 'CREATED',
      entitlementId,
      chapterId: workId,
      priceVersion: 2,
      creditsSpent: 10,
      grantedAt: '2026-11-15T10:00:00Z',
    });
    await expect(result).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });

  it('keeps a stable attempt key for a chapter until the caller clears it', () => {
    const first = api.getOrCreateIdempotencyKey(chapterId);
    expect(first).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
    expect(api.getOrCreateIdempotencyKey(chapterId)).toBe(first);
    api.clearIdempotencyKey(chapterId);
    expect(api.getOrCreateIdempotencyKey(chapterId)).not.toBe(first);
  });

  it('maps insufficient paid-credit responses to a stable safe code', async () => {
    const result = api.unlock(chapterId, 1, 'unlock-attempt-0003');
    await flushCsrf(http);
    http
      .expectOne(`/api/v1/chapters/${chapterId}/unlock`)
      .flush(
        { code: 'INSUFFICIENT_CREDITS', detail: 'not displayed' },
        { status: 409, statusText: 'Conflict' },
      );
    await expect(result).rejects.toMatchObject({ status: 409, code: 'INSUFFICIENT_CREDITS' });
  });
});
