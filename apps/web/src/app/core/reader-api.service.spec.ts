import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { ReaderApiService } from './reader-api.service';

const workId = '66666666-6666-4666-8666-666666666666';
const libraryId = '33333333-3333-4333-8333-333333333333';
const chapterId = '77777777-7777-4777-8777-777777777777';
const revisionId = '88888888-8888-4888-8888-888888888888';
const cursor = 'signed-reader-cursor';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';

async function flushReaderCsrf(http: HttpTestingController): Promise<void> {
  http.expectOne({ method: 'GET', url: '/api/v1/auth/csrf' }).flush({ token: csrfToken });
  await Promise.resolve();
  await Promise.resolve();
}

const work = {
  id: workId,
  libraryId,
  title: 'Tác phẩm riêng tư',
  author: 'Tác giả',
  language: 'vi',
  visibility: 'private',
  chapterCount: 2,
  createdAt: '2026-11-11T00:00:00Z',
};

const publicWork = { ...work, visibility: 'public', title: 'Truyện công khai' };

const annotation = {
  id: '99999999-9999-4999-8999-999999999999',
  workId,
  chapterId,
  chapterRevisionId: revisionId,
  startAnchor: 'chapter-1-p1',
  startOffset: 2,
  endAnchor: 'chapter-1-p1',
  endOffset: 12,
  quoteSnapshot: 'ây ☁️ mở cửa',
  note: 'Đoạn cần nhớ',
  color: 'amber',
  version: 3,
  createdAt: '2026-11-11T10:00:00Z',
  updatedAt: '2026-11-11T10:05:00Z',
  chapterOrdinal: 1,
  chapterTitle: 'Chương một',
  anchorState: 'CURRENT',
};

describe('ReaderApiService', () => {
  let service: ReaderApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ReaderApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests and validates a bounded account manifest page', async () => {
    const result = service.getManifestPage(workId, undefined, 50);
    const request = http.expectOne(
      (candidate) =>
        candidate.method === 'GET' &&
        candidate.url === `/api/v1/works/${workId}/reader-manifest` &&
        candidate.params.get('limit') === '50',
    );
    request.flush({
      work,
      sourceLanguage: 'vi',
      chapters: [
        { id: chapterId, ordinal: 1, title: 'Chương một', accessState: 'readable' },
        {
          id: revisionId,
          ordinal: 2,
          title: 'Chương hai',
          accessState: 'unavailable',
        },
      ],
      nextCursor: cursor,
    });

    await expect(result).resolves.toMatchObject({
      work: { id: workId, title: 'Tác phẩm riêng tư' },
      chapters: [
        { ordinal: 1, accessState: 'readable' },
        { ordinal: 2, accessState: 'unavailable' },
      ],
      nextCursor: cursor,
    });
  });

  it('accepts a public work manifest and preserves locked chapter access state', async () => {
    const result = service.getManifestPage(workId);
    http
      .expectOne((candidate) => candidate.url === `/api/v1/works/${workId}/reader-manifest`)
      .flush({
        work: publicWork,
        sourceLanguage: 'vi',
        chapters: [{ id: chapterId, ordinal: 1, title: 'Chương khóa', accessState: 'locked' }],
        nextCursor: null,
      });

    await expect(result).resolves.toMatchObject({
      work: { visibility: 'public' },
      chapters: [{ accessState: 'locked' }],
    });
  });

  it('requests bounded source content with the revision and opaque cursor', async () => {
    const result = service.getContentPage(chapterId, revisionId, cursor, 250);
    const request = http.expectOne(
      (candidate) =>
        candidate.method === 'GET' &&
        candidate.url === `/api/v1/chapters/${chapterId}/content` &&
        candidate.params.get('revision') === revisionId &&
        candidate.params.get('cursor') === cursor &&
        candidate.params.get('limit') === '250',
    );
    request.flush({
      chapterId,
      workId,
      sourceRevisionId: revisionId,
      sourceLanguage: 'vi',
      accessState: 'readable',
      previewPercent: null,
      segments: [
        { anchor: 'chapter-1-p1', type: 'paragraph', sourceText: 'Một đoạn văn bản.' },
        { anchor: 'chapter-1-q1', type: 'quote', sourceText: '<em>An toàn như văn bản.</em>' },
      ],
      nextSegmentCursor: null,
    });

    await expect(result).resolves.toMatchObject({
      chapterId,
      workId,
      sourceRevisionId: revisionId,
      accessState: 'readable',
      previewPercent: null,
      segments: [
        { type: 'paragraph', sourceText: 'Một đoạn văn bản.' },
        { type: 'quote', sourceText: '<em>An toàn như văn bản.</em>' },
      ],
    });
  });

  it('validates the server preview marker and fixed 10 percent policy', async () => {
    const result = service.getContentPage(chapterId);
    http
      .expectOne(
        (request) =>
          request.method === 'GET' &&
          request.url === `/api/v1/chapters/${chapterId}/content` &&
          request.params.get('limit') === '1000',
      )
      .flush({
        chapterId,
        workId,
        sourceRevisionId: revisionId,
        sourceLanguage: 'vi',
        accessState: 'preview',
        previewPercent: 10,
        segments: [{ anchor: 'chapter-1-p1', type: 'paragraph', sourceText: 'Mẫu 10%.' }],
        nextSegmentCursor: null,
      });
    await expect(result).resolves.toMatchObject({
      accessState: 'preview',
      previewPercent: 10,
      segments: [{ sourceText: 'Mẫu 10%.' }],
    });

    const malformed = service.getContentPage(chapterId);
    http
      .expectOne(
        (request) =>
          request.method === 'GET' &&
          request.url === `/api/v1/chapters/${chapterId}/content` &&
          request.params.get('limit') === '1000',
      )
      .flush({
        chapterId,
        workId,
        sourceRevisionId: revisionId,
        sourceLanguage: 'vi',
        accessState: 'preview',
        previewPercent: 100,
        segments: [],
        nextSegmentCursor: null,
      });
    await expect(malformed).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_READER_RESPONSE',
    });
  });

  it('loads only unique, valid languages with currently released translations', async () => {
    const result = service.getAvailableTranslations(chapterId);
    http.expectOne({ method: 'GET', url: `/api/v1/chapters/${chapterId}/translations` }).flush({
      languages: ['en', 'ja-JP'],
    });
    await expect(result).resolves.toEqual(['en', 'ja-JP']);

    const malformed = service.getAvailableTranslations(chapterId);
    http.expectOne({ method: 'GET', url: `/api/v1/chapters/${chapterId}/translations` }).flush({
      languages: ['en', 'en'],
    });
    await expect(malformed).rejects.toMatchObject({ status: 502, code: 'INVALID_READER_RESPONSE' });
  });

  it('requests a released translation page and maps plain translation text into Reader segments', async () => {
    const result = service.getTranslatedContentPage(chapterId, 'en', cursor, 100);
    const request = http.expectOne(
      (candidate) =>
        candidate.method === 'GET' &&
        candidate.url === `/api/v1/chapters/${chapterId}/translations/en/content` &&
        candidate.params.get('cursor') === cursor &&
        candidate.params.get('limit') === '100',
    );
    request.flush({
      chapterId,
      workId,
      sourceRevisionId: revisionId,
      translationVersionId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      translationVersionNo: 2,
      sourceLanguage: 'vi',
      targetLanguage: 'en',
      segments: [
        { anchor: 'chapter-1-p1', type: 'paragraph', translatedText: 'The translated paragraph.' },
      ],
      nextSegmentCursor: null,
    });
    await expect(result).resolves.toMatchObject({
      targetLanguage: 'en',
      translationVersionNo: 2,
      segments: [{ anchor: 'chapter-1-p1', sourceText: 'The translated paragraph.' }],
    });

    const malformed = service.getTranslatedContentPage(chapterId, 'en');
    http
      .expectOne(
        (candidate) => candidate.url === `/api/v1/chapters/${chapterId}/translations/en/content`,
      )
      .flush({
        chapterId,
        workId,
        sourceRevisionId: revisionId,
        translationVersionId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        translationVersionNo: 2,
        sourceLanguage: 'vi',
        targetLanguage: 'ja',
        segments: [],
        nextSegmentCursor: null,
      });
    await expect(malformed).rejects.toMatchObject({ status: 502, code: 'INVALID_READER_RESPONSE' });
  });

  it('loads, validates, and conditionally saves persisted reader progress', async () => {
    const progress = {
      workId,
      chapterId,
      segmentAnchor: 'chapter-1-p1',
      scrollRatio: 0.42,
      version: 3,
      updatedAt: '2026-11-11T10:00:00Z',
    };
    const loaded = service.getProgress(workId);
    http.expectOne({ method: 'GET', url: `/api/v1/works/${workId}/progress` }).flush(progress);
    await expect(loaded).resolves.toEqual(progress);

    const saved = service.saveProgress(workId, {
      expectedVersion: 3,
      chapterId,
      segmentAnchor: 'chapter-1-p1',
      scrollRatio: 0.42,
    });
    await flushReaderCsrf(http);
    const saveRequest = http.expectOne({ method: 'PUT', url: `/api/v1/works/${workId}/progress` });
    expect(saveRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(saveRequest.request.body).toEqual({
      expectedVersion: 3,
      chapterId,
      segmentAnchor: 'chapter-1-p1',
      scrollRatio: 0.42,
    });
    saveRequest.flush(progress);
    await expect(saved).resolves.toEqual(progress);
  });

  it('loads and saves bounded per-account reader preferences', async () => {
    const preferences = {
      schemaVersion: 1 as const,
      theme: 'sepia' as const,
      fontFamily: 'sans' as const,
      fontSize: 24,
      lineHeight: 1.9,
      contentWidth: 840,
    };
    const loaded = service.getReaderPreferences();
    http.expectOne({ method: 'GET', url: '/api/v1/me/reader-preferences' }).flush(preferences);
    await expect(loaded).resolves.toEqual(preferences);

    const saved = service.saveReaderPreferences(preferences);
    await flushReaderCsrf(http);
    const request = http.expectOne({ method: 'PUT', url: '/api/v1/me/reader-preferences' });
    expect(request.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(request.request.body).toEqual(preferences);
    request.flush(preferences);
    await expect(saved).resolves.toEqual(preferences);
  });

  it('starts, heartbeats, and closes server-timed cultivation reader sessions', async () => {
    const sessionId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
    const start = service.startCultivationReadingSession(
      workId,
      chapterId,
      'reader-start-idempotency-01',
    );
    await flushReaderCsrf(http);
    const startRequest = http.expectOne({
      method: 'POST',
      url: '/api/v1/me/reading-sessions',
    });
    expect(startRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(startRequest.request.headers.get('Idempotency-Key')).toBe('reader-start-idempotency-01');
    expect(startRequest.request.body).toEqual({ workId, chapterId, active: true });
    startRequest.flush({
      sessionId,
      state: 'active',
      startedAt: '2026-11-14T10:00:00Z',
      expiresAt: '2026-11-14T10:01:30Z',
      lastSequence: 0,
      totalActiveSeconds: 0,
      heartbeatIntervalSeconds: 30,
      idleTimeoutSeconds: 90,
    });
    await expect(start).resolves.toMatchObject({ sessionId, heartbeatIntervalSeconds: 30 });

    const heartbeat = service.heartbeatCultivationReadingSession(
      sessionId,
      1,
      false,
      'reader-heartbeat-idempotency-01',
    );
    await flushReaderCsrf(http);
    const heartbeatRequest = http.expectOne({
      method: 'POST',
      url: `/api/v1/me/reading-sessions/${sessionId}:heartbeat`,
    });
    expect(heartbeatRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(heartbeatRequest.request.headers.get('Idempotency-Key')).toBe(
      'reader-heartbeat-idempotency-01',
    );
    expect(heartbeatRequest.request.body).toEqual({ sequence: 1, active: false });
    heartbeatRequest.flush({
      sessionId,
      state: 'active',
      sequence: 1,
      creditedSeconds: 30,
      totalActiveSeconds: 30,
      awardedXp: 0,
      receivedAt: '2026-11-14T10:00:30Z',
      expiresAt: '2026-11-14T10:02:00Z',
    });
    await expect(heartbeat).resolves.toMatchObject({ sequence: 1, creditedSeconds: 30 });

    const close = service.closeCultivationReadingSession(sessionId);
    await flushReaderCsrf(http);
    const closeRequest = http.expectOne({
      method: 'POST',
      url: `/api/v1/me/reading-sessions/${sessionId}:close`,
    });
    expect(closeRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(closeRequest.request.body).toBeNull();
    closeRequest.flush({
      sessionId,
      state: 'closed',
      startedAt: '2026-11-14T10:00:00Z',
      closedAt: '2026-11-14T10:00:31Z',
      totalActiveSeconds: 30,
      lastSequence: 1,
      awardedXp: 0,
    });
    await expect(close).resolves.toMatchObject({ state: 'closed', lastSequence: 1 });
  });

  it('loads a bounded bookmark page and validates bookmark ownership shape', async () => {
    const result = service.getBookmarks(workId, undefined, 25);
    const request = http.expectOne(
      (candidate) =>
        candidate.method === 'GET' &&
        candidate.url === `/api/v1/works/${workId}/bookmarks` &&
        candidate.params.get('limit') === '25',
    );
    request.flush({
      items: [
        {
          id: '99999999-9999-4999-8999-999999999999',
          workId,
          chapterId,
          segmentAnchor: 'chapter-1-p1',
          label: 'Chương 1 · Gió qua núi',
          createdAt: '2026-11-11T10:00:00Z',
        },
      ],
      nextCursor: cursor,
    });
    await expect(result).resolves.toMatchObject({
      items: [{ chapterId, segmentAnchor: 'chapter-1-p1' }],
      nextCursor: cursor,
    });

    const malformed = service.getBookmarks(workId);
    http
      .expectOne((candidate) => candidate.url === `/api/v1/works/${workId}/bookmarks`)
      .flush({
        items: [
          {
            id: '99999999-9999-4999-8999-999999999999',
            workId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
            chapterId,
            segmentAnchor: null,
            label: 'Other work',
            createdAt: '2026-11-11T10:00:00Z',
          },
        ],
        nextCursor: null,
      });
    await expect(malformed).rejects.toMatchObject({ status: 502, code: 'INVALID_READER_RESPONSE' });
  });

  it('sends bookmark writes with an idempotency key and deletes by bookmark id', async () => {
    const bookmarkId = '99999999-9999-4999-8999-999999999999';
    const bookmark = {
      id: bookmarkId,
      workId,
      chapterId,
      segmentAnchor: null,
      label: 'Chương một',
      createdAt: '2026-11-11T10:00:00Z',
    };
    const created = service.createBookmark(
      workId,
      { chapterId, segmentAnchor: null, label: bookmark.label },
      'client-retry-key-0001',
    );
    await flushReaderCsrf(http);
    const createRequest = http.expectOne({
      method: 'POST',
      url: `/api/v1/works/${workId}/bookmarks`,
    });
    expect(createRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(createRequest.request.headers.get('Idempotency-Key')).toBe('client-retry-key-0001');
    expect(createRequest.request.body).toEqual({
      chapterId,
      segmentAnchor: null,
      label: 'Chương một',
    });
    createRequest.flush(bookmark);
    await expect(created).resolves.toEqual(bookmark);

    const deleted = service.deleteBookmark(bookmarkId);
    await flushReaderCsrf(http);
    const deleteRequest = http.expectOne({
      method: 'DELETE',
      url: `/api/v1/bookmarks/${bookmarkId}`,
    });
    expect(deleteRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    deleteRequest.flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    await expect(deleted).resolves.toBeUndefined();
  });

  it('loads private annotations using the chapter filter and a filter-bound cursor', async () => {
    const result = service.getAnnotations(workId, chapterId, cursor, 20);
    const request = http.expectOne(
      (candidate) =>
        candidate.method === 'GET' &&
        candidate.url === `/api/v1/works/${workId}/annotations` &&
        candidate.params.get('chapterId') === chapterId &&
        candidate.params.get('cursor') === cursor &&
        candidate.params.get('limit') === '20',
    );
    request.flush({ items: [annotation], nextCursor: null });
    await expect(result).resolves.toEqual({ items: [annotation], nextCursor: null });

    const malformed = service.getAnnotations(workId, chapterId);
    http
      .expectOne((candidate) => candidate.url === `/api/v1/works/${workId}/annotations`)
      .flush({ items: [{ ...annotation, chapterId: revisionId }], nextCursor: null });
    await expect(malformed).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_READER_RESPONSE',
    });
  });

  it('creates annotations, updates with If-Match, reconciles a stale note and deletes privately', async () => {
    const requestBody = {
      chapterId,
      sourceRevisionId: revisionId,
      startAnchor: 'chapter-1-p1',
      startOffset: 2,
      endAnchor: 'chapter-1-p1',
      endOffset: 12,
      quoteSnapshot: annotation.quoteSnapshot,
      note: annotation.note,
      color: 'amber' as const,
    };
    const created = service.createAnnotation(workId, requestBody);
    await flushReaderCsrf(http);
    const createRequest = http.expectOne({
      method: 'POST',
      url: `/api/v1/works/${workId}/annotations`,
    });
    expect(createRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(createRequest.request.body).toEqual(requestBody);
    createRequest.flush(annotation);
    await expect(created).resolves.toEqual(annotation);

    const updated = service.updateAnnotation(annotation.id, 3, 'Ghi chú mới', 'sky');
    await flushReaderCsrf(http);
    const updateRequest = http.expectOne({
      method: 'PATCH',
      url: `/api/v1/annotations/${annotation.id}`,
    });
    expect(updateRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    expect(updateRequest.request.headers.get('If-Match')).toBe('"annotation-3"');
    expect(updateRequest.request.body).toEqual({ note: 'Ghi chú mới', color: 'sky' });
    updateRequest.flush({ ...annotation, note: 'Ghi chú mới', color: 'sky', version: 4 });
    await expect(updated).resolves.toMatchObject({ note: 'Ghi chú mới', version: 4 });

    const stale = service.updateAnnotation(annotation.id, 3, 'Bản cũ', 'rose');
    await flushReaderCsrf(http);
    http.expectOne({ method: 'PATCH', url: `/api/v1/annotations/${annotation.id}` }).flush(
      {
        code: 'READER_ANNOTATION_VERSION_STALE',
        latestAnnotation: { ...annotation, note: 'Ghi chú mới', color: 'sky', version: 4 },
      },
      { status: 409, statusText: 'Conflict' },
    );
    await expect(stale).rejects.toMatchObject({
      status: 409,
      code: 'READER_ANNOTATION_VERSION_STALE',
      latestAnnotation: { note: 'Ghi chú mới', version: 4 },
    });

    const deleted = service.deleteAnnotation(annotation.id);
    await flushReaderCsrf(http);
    const deleteRequest = http.expectOne({
      method: 'DELETE',
      url: `/api/v1/annotations/${annotation.id}`,
    });
    expect(deleteRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    deleteRequest.flush(null, { status: 204, statusText: 'No Content' });
    await expect(deleted).resolves.toBeUndefined();

    const cleared = service.clearAnnotations(workId);
    await flushReaderCsrf(http);
    const clearRequest = http.expectOne({
      method: 'DELETE',
      url: `/api/v1/works/${workId}/annotations`,
    });
    expect(clearRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    clearRequest.flush(null, { status: 204, statusText: 'No Content' });
    await expect(cleared).resolves.toBeUndefined();
  });

  it('loads a bounded recent-history page without accepting mismatched progress snapshots', async () => {
    const lastReadAt = '2026-11-11T10:00:00Z';
    const result = service.getReadingHistory(undefined, 10);
    const request = http.expectOne(
      (candidate) =>
        candidate.method === 'GET' &&
        candidate.url === '/api/v1/me/reading-history' &&
        candidate.params.get('limit') === '10',
    );
    request.flush({
      items: [
        {
          work: { ...work, lastReadAt },
          chapter: { id: chapterId, ordinal: 1, title: 'Chương một' },
          progress: {
            workId,
            chapterId,
            segmentAnchor: 'chapter-1-p1',
            scrollRatio: 0.42,
            version: 3,
            updatedAt: lastReadAt,
          },
        },
      ],
      nextCursor: cursor,
    });
    await expect(result).resolves.toMatchObject({
      items: [
        { work: { id: workId, lastReadAt }, chapter: { ordinal: 1 }, progress: { version: 3 } },
      ],
      nextCursor: cursor,
    });

    const malformed = service.getReadingHistory();
    http
      .expectOne(
        (candidate) =>
          candidate.method === 'GET' &&
          candidate.url === '/api/v1/me/reading-history' &&
          candidate.params.get('limit') === '25',
      )
      .flush({
        items: [
          {
            work: { ...work, lastReadAt },
            chapter: { id: chapterId, ordinal: 1, title: 'Chương một' },
            progress: {
              workId,
              chapterId: revisionId,
              segmentAnchor: null,
              scrollRatio: 0,
              version: 1,
              updatedAt: lastReadAt,
            },
          },
        ],
        nextCursor: null,
      });
    await expect(malformed).rejects.toMatchObject({ status: 502, code: 'INVALID_READER_RESPONSE' });
  });

  it('deletes one or all history positions through the authenticated account endpoints', async () => {
    const one = service.deleteReadingHistoryWork(workId);
    await flushReaderCsrf(http);
    const deleteOneRequest = http.expectOne({
      method: 'DELETE',
      url: `/api/v1/me/reading-history/${workId}`,
    });
    expect(deleteOneRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    deleteOneRequest.flush(null, { status: 204, statusText: 'No Content' });
    await expect(one).resolves.toBeUndefined();

    const all = service.clearReadingHistory();
    await flushReaderCsrf(http);
    const deleteAllRequest = http.expectOne({
      method: 'DELETE',
      url: '/api/v1/me/reading-history',
    });
    expect(deleteAllRequest.request.headers.get('X-CSRF-Token')).toBe(csrfToken);
    deleteAllRequest.flush(null, { status: 204, statusText: 'No Content' });
    await expect(all).resolves.toBeUndefined();
  });

  it('rejects unsafe bookmark anchors, invalid page limits, and idempotency keys locally', async () => {
    await expect(service.getBookmarks(workId, undefined, 101)).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_READER_QUERY',
    });
    await expect(
      service.createBookmark(
        workId,
        { chapterId, segmentAnchor: 'unstable anchor', label: 'Chapter' },
        'short',
      ),
    ).rejects.toMatchObject({ status: 502, code: 'INVALID_READER_QUERY' });
    expect(() => http.expectNone((request) => request.url.includes('/bookmarks'))).not.toThrow();
  });

  it('rejects preferences outside the server contract', async () => {
    const loaded = service.getReaderPreferences();
    http.expectOne({ method: 'GET', url: '/api/v1/me/reader-preferences' }).flush({
      schemaVersion: 1,
      theme: 'sepia',
      fontFamily: 'sans',
      fontSize: 40,
      lineHeight: 1.8,
      contentWidth: 720,
    });
    await expect(loaded).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_READER_PREFERENCES',
    });

    const unknownField = service.getReaderPreferences();
    http.expectOne({ method: 'GET', url: '/api/v1/me/reader-preferences' }).flush({
      schemaVersion: 1,
      theme: 'light',
      fontFamily: 'serif',
      fontSize: 20,
      lineHeight: 1.8,
      contentWidth: 720,
      trackingId: 'must-be-rejected',
    });
    await expect(unknownField).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_READER_PREFERENCES',
    });
  });

  it('exposes a validated latest progress snapshot for stale-device reconciliation', async () => {
    const staleSave = service.saveProgress(workId, {
      expectedVersion: 2,
      chapterId,
      segmentAnchor: 'chapter-1-p1',
      scrollRatio: 0.2,
    });
    await flushReaderCsrf(http);
    http.expectOne({ method: 'PUT', url: `/api/v1/works/${workId}/progress` }).flush(
      {
        code: 'READER_PROGRESS_VERSION_STALE',
        latestProgress: {
          workId,
          chapterId,
          segmentAnchor: 'chapter-1-p1',
          scrollRatio: 0.8,
          version: 4,
          updatedAt: '2026-11-11T10:00:00Z',
        },
      },
      { status: 409, statusText: 'Conflict' },
    );
    await expect(staleSave).rejects.toMatchObject({
      status: 409,
      code: 'READER_PROGRESS_VERSION_STALE',
      latestProgress: { version: 4, scrollRatio: 0.8 },
    });
  });

  it('rejects malformed response data and preserves safe HTTP status details', async () => {
    const malformed = service.getManifestPage(workId);
    http
      .expectOne((candidate) => candidate.url === `/api/v1/works/${workId}/reader-manifest`)
      .flush({ work, chapters: [], sourceLanguage: 'vi', nextCursor: 4 });
    await expect(malformed).rejects.toMatchObject({
      status: 502,
      code: 'INVALID_READER_RESPONSE',
    });

    const unauthorized = service.getContentPage(chapterId);
    http
      .expectOne((candidate) => candidate.url === `/api/v1/chapters/${chapterId}/content`)
      .flush({ code: 'UNAUTHENTICATED' }, { status: 401, statusText: 'Unauthorized' });
    await expect(unauthorized).rejects.toMatchObject({
      status: 401,
      code: 'UNAUTHENTICATED',
    });
  });
});
