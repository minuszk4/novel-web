import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuthoringApiError, AuthoringApiService } from './authoring-api.service';

const workId = '11111111-1111-4111-8111-111111111111';
const csrfToken = 'A'.repeat(43);
const idempotencyKey = 'author-draft-create-key-0001';
const createdAt = '2026-11-14T12:00:00Z';
const chapterId = '22222222-2222-4222-8222-222222222222';
const chapterCreatedAt = '2026-11-14T12:30:00Z';
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
});

async function flushCsrf(http: HttpTestingController): Promise<void> {
  http.expectOne('/api/v1/auth/csrf').flush({ token: csrfToken });
  await Promise.resolve();
  await Promise.resolve();
}
