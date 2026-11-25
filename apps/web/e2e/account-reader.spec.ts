import { expect, expectNoAxeViolations, test } from './support';
import type { Browser, Page, Route } from '@playwright/test';

const workId = '66666666-6666-4666-8666-666666666666';
const libraryId = '33333333-3333-4333-8333-333333333333';
const chapterIds = [
  '77777777-7777-4777-8777-777777777777',
  '88888888-8888-4888-8888-888888888888',
  '99999999-9999-4999-8999-999999999999',
];
const revisionId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const hostileMarkup = [
  '<img src="https://reader-xss.invalid/pixel.png" onerror="window.readerInjected=true">',
  '<svg onload="window.readerInjected=true"><script>window.readerInjected=true</script><image href="https://reader-xss.invalid/vector.svg"></image></svg>',
  '<iframe src="https://reader-xss.invalid/frame"></iframe>',
  '<a href="javascript:window.readerInjected=true">run script</a>',
];

const currentUser = {
  user: {
    id: 'user-1',
    displayName: 'Độc giả',
    contactEmail: 'reader@example.test',
    avatarUrl: null,
    status: 'ACTIVE',
  },
  identityProviders: ['google'],
  policy: {
    termsAcceptedVersion: 'terms-1',
    privacyNoticeAcknowledgedVersion: 'privacy-1',
    requiredAcceptanceComplete: true,
  },
  roles: [],
  features: {
    privateLibraryEnabled: true,
    importEnabled: true,
    readerEnabled: true,
    commerceEnabled: false,
    cultivationEnabled: false,
  },
};

const work = {
  id: workId,
  libraryId,
  title: 'Gió qua núi',
  author: 'Linh An',
  language: 'vi',
  visibility: 'private',
  chapterCount: 3,
  createdAt: '2026-11-11T00:00:00Z',
};

const chapters = [
  { id: chapterIds[0], ordinal: 1, title: 'Cánh cửa mở', accessState: 'readable' },
  { id: chapterIds[1], ordinal: 2, title: 'Bản nháp', accessState: 'unavailable' },
  { id: chapterIds[2], ordinal: 3, title: 'Qua khỏi dốc', accessState: 'readable' },
];

interface MockProgress {
  workId: string;
  chapterId: string | null;
  segmentAnchor: string | null;
  scrollRatio: number;
  version: number;
  updatedAt: string | null;
}

interface MockReaderPreferences {
  schemaVersion: 2;
  theme: 'light' | 'sepia' | 'dark';
  fontFamily: 'serif' | 'sans';
  fontSize: number;
  lineHeight: number;
  contentWidth: number;
  pairedModeByDefault: boolean;
}

interface MockProgressWrite extends Omit<MockProgress, 'version' | 'updatedAt' | 'workId'> {
  expectedVersion: number;
}

interface MockReaderBookmark {
  id: string;
  workId: string;
  chapterId: string;
  segmentAnchor: string | null;
  label: string;
  createdAt: string;
}

interface MockReaderAnnotation {
  id: string;
  workId: string;
  chapterId: string;
  chapterRevisionId: string | null;
  startAnchor: string;
  startOffset: number;
  endAnchor: string;
  endOffset: number;
  quoteSnapshot: string;
  note: string;
  color: 'amber' | 'mint' | 'sky' | 'rose';
  version: number;
  createdAt: string;
  updatedAt: string;
  chapterOrdinal: number;
  chapterTitle: string;
  anchorState: 'CURRENT' | 'STALE';
}

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installReaderRoutes(
  page: Page,
  manifestStatus: number | readonly number[] = 200,
  includeHostileMarkup = true,
  contentStatus: number | readonly number[] = 200,
  paginatedManifest = false,
  initialProgress?: MockProgress,
  onProgressSave?: (request: MockProgressWrite) => void,
  longThirdChapter = false,
  initialBookmarks: MockReaderBookmark[] = [],
  initialAnnotations: MockReaderAnnotation[] = [],
): Promise<string[]> {
  const loadedChapters: string[] = [];
  const manifestStatuses =
    typeof manifestStatus === 'number' ? [manifestStatus] : [...manifestStatus];
  const contentStatuses = typeof contentStatus === 'number' ? [contentStatus] : [...contentStatus];
  let manifestRequestCount = 0;
  let firstChapterContentRequestCount = 0;
  let progress: MockProgress = initialProgress ?? {
    workId,
    chapterId: null,
    segmentAnchor: null,
    scrollRatio: 0,
    version: 0,
    updatedAt: null,
  };
  const readerPreferences: MockReaderPreferences = {
    schemaVersion: 2,
    theme: 'light',
    fontFamily: 'serif',
    fontSize: 20,
    lineHeight: 1.8,
    contentWidth: 720,
    pairedModeByDefault: false,
  };
  const bookmarks: MockReaderBookmark[] = [...initialBookmarks];
  const annotations: MockReaderAnnotation[] = [...initialAnnotations];
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '4.2.1',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled: false,
        vnpayEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: false,
        translationFakePreviewEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, { token: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }),
  );
  await page.route('**/api/v1/auth/me', (route) => json(route, currentUser));
  await page.route('**/api/v1/me/reader-preferences', async (route) => {
    if (route.request().method() === 'GET') return json(route, readerPreferences);
    Object.assign(readerPreferences, route.request().postDataJSON());
    return json(route, readerPreferences);
  });
  await page.route(`**/api/v1/works/${workId}/bookmarks**`, async (route) => {
    const request = route.request();
    if (request.method() === 'GET') {
      return json(route, { items: bookmarks, nextCursor: null });
    }
    if (request.method() === 'POST') {
      const idempotencyKey = request.headers()['idempotency-key'];
      const body = request.postDataJSON() as {
        chapterId: string;
        segmentAnchor: string | null;
        label: string;
      };
      if (!idempotencyKey || idempotencyKey.length < 16) {
        return json(route, { code: 'INVALID_BOOKMARK_REQUEST' }, 400);
      }
      const existing = bookmarks.find(
        (item) => item.chapterId === body.chapterId && item.segmentAnchor === body.segmentAnchor,
      );
      if (existing) return json(route, existing, 200);
      const bookmark = {
        id: `bbbbbbbb-bbbb-4bbb-8bbb-${String(bookmarks.length + 1).padStart(12, '0')}`,
        workId,
        chapterId: body.chapterId,
        segmentAnchor: body.segmentAnchor,
        label: body.label,
        createdAt: '2026-11-11T10:00:00Z',
      };
      bookmarks.unshift(bookmark);
      return json(route, bookmark, 201);
    }
    return route.fallback();
  });
  await page.route('**/api/v1/bookmarks/*', async (route) => {
    if (route.request().method() !== 'DELETE') return route.fallback();
    const bookmarkId = route.request().url().split('/bookmarks/')[1]?.split('?')[0];
    const index = bookmarks.findIndex((bookmark) => bookmark.id === bookmarkId);
    if (index < 0) return json(route, { code: 'NOT_FOUND' }, 404);
    bookmarks.splice(index, 1);
    return route.fulfill({ status: 204 });
  });
  await page.route(`**/api/v1/works/${workId}/annotations**`, async (route) => {
    const request = route.request();
    if (request.method() === 'GET') {
      const requestUrl = new URL(request.url());
      const chapterFilter = requestUrl.searchParams.get('chapterId');
      const filtered = annotations.filter(
        (item) => chapterFilter === null || item.chapterId === chapterFilter,
      );
      const limit = Number(requestUrl.searchParams.get('limit') ?? 50);
      return json(route, { items: filtered.slice(0, limit), nextCursor: null });
    }
    if (request.method() === 'POST') {
      const body = request.postDataJSON() as {
        chapterId: string;
        sourceRevisionId: string;
        startAnchor: string;
        startOffset: number;
        endAnchor: string;
        endOffset: number;
        quoteSnapshot: string;
        note: string;
        color: MockReaderAnnotation['color'];
      };
      const existing = annotations.find(
        (item) =>
          item.chapterId === body.chapterId &&
          item.chapterRevisionId === body.sourceRevisionId &&
          item.startAnchor === body.startAnchor &&
          item.startOffset === body.startOffset &&
          item.endAnchor === body.endAnchor &&
          item.endOffset === body.endOffset,
      );
      if (existing) return json(route, existing, 200);
      const created: MockReaderAnnotation = {
        id: `dddddddd-dddd-4ddd-8ddd-${String(annotations.length + 1).padStart(12, '0')}`,
        workId,
        chapterId: body.chapterId,
        chapterRevisionId: body.sourceRevisionId,
        startAnchor: body.startAnchor,
        startOffset: body.startOffset,
        endAnchor: body.endAnchor,
        endOffset: body.endOffset,
        quoteSnapshot: body.quoteSnapshot,
        note: body.note,
        color: body.color,
        version: 1,
        createdAt: '2026-11-11T10:00:00Z',
        updatedAt: '2026-11-11T10:00:00Z',
        chapterOrdinal: chapters.find((item) => item.id === body.chapterId)?.ordinal ?? 1,
        chapterTitle: chapters.find((item) => item.id === body.chapterId)?.title ?? '',
        anchorState: 'CURRENT',
      };
      annotations.unshift(created);
      return json(route, created, 201);
    }
    if (request.method() === 'DELETE') {
      annotations.splice(0, annotations.length);
      return route.fulfill({ status: 204 });
    }
    return route.fallback();
  });
  await page.route('**/api/v1/annotations/*', async (route) => {
    const request = route.request();
    const annotationId = request.url().split('/annotations/')[1]?.split('?')[0];
    const index = annotations.findIndex((item) => item.id === annotationId);
    if (index < 0) return json(route, { code: 'NOT_FOUND' }, 404);
    if (request.method() === 'DELETE') {
      annotations.splice(index, 1);
      return route.fulfill({ status: 204 });
    }
    if (request.method() === 'PATCH') {
      const annotation = annotations[index];
      const expectedEtag = `"annotation-${annotation.version}"`;
      if (request.headers()['if-match'] !== expectedEtag) {
        return json(
          route,
          { code: 'READER_ANNOTATION_VERSION_STALE', latestAnnotation: annotation },
          409,
        );
      }
      const body = request.postDataJSON() as Pick<MockReaderAnnotation, 'note' | 'color'>;
      Object.assign(annotation, body, {
        version: annotation.version + 1,
        updatedAt: '2026-11-11T10:05:00Z',
      });
      return json(route, annotation);
    }
    return route.fallback();
  });
  await page.route(`**/api/v1/works/${workId}/progress`, async (route) => {
    if (route.request().method() === 'GET') return json(route, progress);
    const request = route.request().postDataJSON() as MockProgressWrite;
    onProgressSave?.(request);
    if (
      request.expectedVersion !== progress.version &&
      (request.chapterId !== progress.chapterId ||
        request.segmentAnchor !== progress.segmentAnchor ||
        request.scrollRatio !== progress.scrollRatio)
    ) {
      return json(route, { code: 'READER_PROGRESS_VERSION_STALE', latestProgress: progress }, 409);
    }
    if (
      request.chapterId !== progress.chapterId ||
      request.segmentAnchor !== progress.segmentAnchor ||
      request.scrollRatio !== progress.scrollRatio
    ) {
      progress = {
        workId,
        chapterId: request.chapterId,
        segmentAnchor: request.segmentAnchor,
        scrollRatio: request.scrollRatio,
        version: progress.version + 1,
        updatedAt: '2026-11-11T10:00:00Z',
      };
    }
    return json(route, progress);
  });
  await page.route('**/api/v1/auth/policies/current', (route) =>
    json(route, {
      terms: {
        version: 'terms-1',
        effectiveAt: '2026-11-01T00:00:00Z',
        documentUrl: '/api/v1/auth/policies/terms/terms-1',
      },
      privacyNotice: {
        version: 'privacy-1',
        effectiveAt: '2026-11-01T00:00:00Z',
        documentUrl: '/api/v1/auth/policies/privacy-notice/privacy-1',
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/terms/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Terms' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Privacy' }),
  );
  await page.route(`**/api/v1/works/${workId}/reader-manifest**`, (route) => {
    const requestUrl = new URL(route.request().url());
    if (paginatedManifest && !requestUrl.searchParams.has('cursor')) {
      return json(route, {
        work,
        sourceLanguage: 'vi',
        chapters: chapters.slice(0, 2),
        nextCursor: 'reader-page-2',
      });
    }
    const status =
      manifestStatuses[Math.min(manifestRequestCount, manifestStatuses.length - 1)] ?? 200;
    manifestRequestCount += 1;
    return status === 200
      ? json(route, {
          work,
          sourceLanguage: 'vi',
          chapters: paginatedManifest ? chapters.slice(2) : chapters,
          nextCursor: null,
        })
      : json(
          route,
          {
            code:
              status === 401
                ? 'UNAUTHENTICATED'
                : status === 403
                  ? 'POLICY_REQUIRED'
                  : 'READER_TEMPORARILY_UNAVAILABLE',
          },
          status,
        );
  });
  await page.route('**/api/v1/chapters/*/content**', (route) => {
    const chapterId = route.request().url().split('/chapters/')[1]?.split('/')[0];
    if (chapterId) loadedChapters.push(chapterId);
    if (chapterId === chapterIds[0]) {
      const status =
        contentStatuses[Math.min(firstChapterContentRequestCount, contentStatuses.length - 1)] ??
        200;
      firstChapterContentRequestCount += 1;
      if (status !== 200) {
        return json(route, { code: 'READER_TEMPORARILY_UNAVAILABLE' }, status);
      }
      const segments = [
        { anchor: 'ch1-h1', type: 'heading', sourceText: 'Ngày đầu tiên' },
        { anchor: 'ch1-p1', type: 'paragraph', sourceText: 'Một đoạn văn bản riêng tư.' },
        ...(includeHostileMarkup
          ? hostileMarkup.map((sourceText, index) => ({
              anchor: `ch1-xss-${index + 1}`,
              type: 'paragraph',
              sourceText,
            }))
          : [
              {
                anchor: 'ch1-p2',
                type: 'paragraph',
                sourceText: 'Gió lặng dần khi con đường men theo sườn núi.',
              },
            ]),
      ];
      return json(route, {
        chapterId,
        workId,
        sourceRevisionId: revisionId,
        sourceLanguage: 'vi',
        accessState: 'readable',
        previewPercent: null,
        segments,
        nextSegmentCursor: null,
      });
    }
    return json(route, {
      chapterId,
      workId,
      sourceRevisionId: revisionId,
      sourceLanguage: 'vi',
      accessState: 'readable',
      previewPercent: null,
      segments: longThirdChapter
        ? Array.from({ length: 20 }, (_, index) => ({
            anchor: `ch3-p${index + 1}`,
            type: 'paragraph',
            sourceText: `Đoạn ${index + 1}. ${'Con đường men theo sườn núi và mở ra thung lũng phía trước. '.repeat(8)}`,
          }))
        : [{ anchor: 'ch3-p1', type: 'paragraph', sourceText: 'Con đường đã sáng.' }],
      nextSegmentCursor: null,
    });
  });
  return loadedChapters;
}

async function installCultivationSessionRoutes(
  page: Page,
  featureEnabled: boolean,
  profileEnabled: boolean,
  retryFirstHeartbeat = false,
): Promise<string[]> {
  const sessionEvents: string[] = [];
  let sessionNumber = 0;
  let sequence = 0;
  let didFailHeartbeat = false;
  let pendingFailedHeartbeat: { sequence: number; idempotencyKey: string } | null = null;
  let activeSessionId: string | null = null;
  await page.unroute('**/api/v1/system/status');
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '4.2.1',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled: false,
        vnpayEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: featureEnabled,
        commerceSandboxEnabled: false,
        translationFakePreviewEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/me/cultivation', (route) =>
    json(route, {
      enabled: profileEnabled,
      profileVersion: 1,
      totalXp: 0,
      todayXpEarned: 0,
      currentRank: {
        key: 'mortal',
        group: 'Nhập môn',
        name: 'Phàm nhân',
        thresholdXp: 0,
        sortOrder: 0,
      },
      nextRank: null,
      xpToNextRank: 0,
      progressRatio: 0,
      rankCatalogVersion: 'cultivation-v1',
      policyVersion: 'xp-v1',
      locale: 'vi-VN',
      xpPerActiveMinute: 1,
      dailyXpCap: 120,
      nextDailyResetAt: '2026-11-15T00:00:00Z',
      rankCatalog: [
        { key: 'mortal', group: 'Nhập môn', name: 'Phàm nhân', thresholdXp: 0, sortOrder: 0 },
      ],
    }),
  );
  await page.route('**/api/v1/me/reading-sessions**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() !== 'POST') return route.fallback();
    if (path === '/api/v1/me/reading-sessions') {
      if (!request.headers()['idempotency-key']) {
        return json(route, { code: 'IDEMPOTENCY_KEY_REQUIRED' }, 400);
      }
      const body = request.postDataJSON() as { workId: string; chapterId: string; active: boolean };
      if (body.workId !== workId || body.active !== true || activeSessionId !== null) {
        return json(route, { code: 'INVALID_READING_SESSION_START' }, 409);
      }
      sessionNumber += 1;
      sequence = 0;
      activeSessionId = `aaaaaaaa-aaaa-4aaa-8aaa-${String(sessionNumber).padStart(12, '0')}`;
      sessionEvents.push(`start:${body.chapterId}`);
      return json(
        route,
        {
          sessionId: activeSessionId,
          state: 'active',
          startedAt: '2026-11-14T10:00:00Z',
          expiresAt: '2026-11-14T10:01:30Z',
          lastSequence: 0,
          totalActiveSeconds: 0,
          heartbeatIntervalSeconds: 30,
          idleTimeoutSeconds: 90,
        },
        201,
      );
    }
    const sessionId = path.split('/').at(-1)?.split(':')[0];
    if (!sessionId || sessionId !== activeSessionId) {
      return json(route, { code: 'READING_SESSION_NOT_FOUND' }, 404);
    }
    if (path.endsWith(':heartbeat')) {
      const body = request.postDataJSON() as { sequence: number; active: boolean };
      const idempotencyKey = request.headers()['idempotency-key'];
      sessionEvents.push(`heartbeat-attempt:${body.sequence}:${body.active}`);
      if (body.sequence !== sequence + 1 || !idempotencyKey) {
        return json(route, { code: 'READING_SESSION_SEQUENCE_CONFLICT' }, 409);
      }
      if (retryFirstHeartbeat && !didFailHeartbeat) {
        didFailHeartbeat = true;
        pendingFailedHeartbeat = { sequence: body.sequence, idempotencyKey };
        sessionEvents.push(`heartbeat-503:${body.sequence}:${body.active}`);
        return json(route, { code: 'TEMPORARY_READING_SESSION_ERROR' }, 503);
      }
      if (pendingFailedHeartbeat) {
        if (
          pendingFailedHeartbeat.sequence !== body.sequence ||
          pendingFailedHeartbeat.idempotencyKey !== idempotencyKey
        ) {
          return json(route, { code: 'RETRY_CHANGED_IDEMPOTENCY_KEY' }, 409);
        }
        sessionEvents.push('heartbeat-retried-same-key');
        pendingFailedHeartbeat = null;
      }
      sequence = body.sequence;
      sessionEvents.push(`heartbeat:${sequence}:${body.active}`);
      return json(route, {
        sessionId,
        state: 'active',
        sequence,
        creditedSeconds: 0,
        totalActiveSeconds: 0,
        awardedXp: 0,
        receivedAt: '2026-11-14T10:00:30Z',
        expiresAt: '2026-11-14T10:02:00Z',
      });
    }
    if (path.endsWith(':close')) {
      sessionEvents.push(`close:${sessionId}`);
      activeSessionId = null;
      return json(route, {
        sessionId,
        state: 'closed',
        startedAt: '2026-11-14T10:00:00Z',
        closedAt: '2026-11-14T10:00:31Z',
        totalActiveSeconds: 0,
        lastSequence: sequence,
        awardedXp: 0,
      });
    }
    return route.fallback();
  });
  return sessionEvents;
}

const viewports = [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'tablet 768×1024', width: 768, height: 1024 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const;

async function installPublicLockedManifest(page: Page, commerceEnabled: boolean): Promise<void> {
  const publicWork = { ...work, visibility: 'public', title: 'Gió qua núi công khai' };
  const lockedChapters = [
    chapters[0],
    { ...chapters[1], accessState: 'locked' as const },
    chapters[2],
  ];
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: 'v1',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled: commerceEnabled,
        vnpayEnvironment: commerceEnabled ? 'TEST' : 'OFF',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: commerceEnabled,
        translationFakePreviewEnabled: false,
      },
    }),
  );
  await page.route(`**/api/v1/works/${workId}/reader-manifest**`, (route) =>
    json(route, {
      work: publicWork,
      sourceLanguage: 'vi',
      chapters: lockedChapters,
      nextCursor: null,
    }),
  );
}

for (const viewport of viewports) {
  test(`account Reader supports TOC and safe chapter content at ${viewport.name}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const unexpectedExternalRequests: string[] = [];
    page.on('request', (request) => {
      if (new URL(request.url()).hostname === 'reader-xss.invalid') {
        unexpectedExternalRequests.push(request.url());
      }
    });
    const loadedChapters = await installReaderRoutes(page);
    await page.goto(`/app/reader/${workId}`);

    await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
    await expect(page.getByText('Một đoạn văn bản riêng tư.')).toBeVisible();
    await expect(page.locator('.reader-account-private')).toContainText('THƯ VIỆN TÀI KHOẢN');
    await expect(page.getByText(/Tiến độ đã đồng bộ · Dấu trang chưa tải/)).toBeVisible();
    for (const payload of hostileMarkup) {
      await expect(page.locator('.reader-body').getByText(payload, { exact: true })).toBeVisible();
    }
    await expect(
      page.locator('.reader-body img, .reader-body svg, .reader-body script, .reader-body iframe'),
    ).toHaveCount(0);
    expect(unexpectedExternalRequests).toEqual([]);
    expect(
      await page.evaluate(() => (window as Window & { readerInjected?: boolean }).readerInjected),
    ).toBeUndefined();
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Mở mục lục' }).click();
    const toc = page.getByRole('dialog', { name: 'Gió qua núi' });
    await expect(toc).toBeVisible();
    await expect(toc.getByRole('button', { name: /Bản nháp/ })).toBeDisabled();
    await expectNoAxeViolations(page);
    await toc.getByRole('button', { name: /Qua khỏi dốc/ }).click();

    await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[2]}$`));
    await expect(page.getByRole('heading', { name: 'Qua khỏi dốc' })).toBeVisible();
    await expect(page.getByText('Con đường đã sáng.')).toBeVisible();
    const workDetailLink = page.getByRole('link', { name: 'Quay lại chi tiết tác phẩm' });
    await expect(workDetailLink).toBeVisible();
    await expect(workDetailLink).toHaveAttribute('href', `/app/works/${workId}`);
    expect(loadedChapters).toEqual([chapterIds[0], chapterIds[2]]);
    await page.screenshot({
      path: test.info().outputPath(`account-reader-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    });

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  });
}

test('approved paid-chapter preview explains its limit and skips private Reader writes', async ({
  page,
}) => {
  const progressWrites: MockProgressWrite[] = [];
  const optionalFeatureRequests: string[] = [];
  await installReaderRoutes(page, 200, false, 200, false, undefined, (write) => {
    progressWrites.push(write);
  });
  await page.route(`**/api/v1/works/${workId}/reader-manifest**`, (route) =>
    json(route, {
      work: { ...work, visibility: 'public' },
      sourceLanguage: 'vi',
      chapters: [{ ...chapters[0], accessState: 'preview' }],
      nextCursor: null,
    }),
  );
  await page.route(`**/api/v1/chapters/${chapterIds[0]}/content**`, (route) =>
    json(route, {
      chapterId: chapterIds[0],
      workId,
      sourceRevisionId: revisionId,
      sourceLanguage: 'vi',
      accessState: 'preview',
      previewPercent: 10,
      segments: [{ anchor: 'preview-p1', type: 'paragraph', sourceText: 'Bản đọc thử.' }],
      nextSegmentCursor: null,
    }),
  );
  page.on('request', (request) => {
    const pathname = new URL(request.url()).pathname;
    if (pathname.includes('/reading-sessions') || pathname.includes('/annotations')) {
      optionalFeatureRequests.push(`${request.method()} ${pathname}`);
    }
  });

  await page.goto(`/app/reader/${workId}/${chapterIds[0]}`);

  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await expect(page.getByText('BẢN ĐỌC THỬ · 10%')).toBeVisible();
  await expect(page.getByText('Bạn đang xem 10% đầu chương.', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Dấu trang' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Ghi chú' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Xem giá và mở toàn chương' })).toBeVisible();
  await page.locator('.account-reader-content').evaluate((element) => {
    element.dispatchEvent(new Event('scroll'));
  });
  await page.waitForTimeout(900);
  expect(progressWrites).toEqual([]);
  expect(optionalFeatureRequests).toEqual([]);
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Xem giá và mở toàn chương' }).click();
  await expect(page.getByText('CHƯƠNG TRẢ PHÍ · MÔI TRƯỜNG TEST')).toBeVisible();
  await expect(page.getByText('Quay lại bản đọc thử')).toBeVisible();
  await page.getByRole('button', { name: 'Quay lại bản đọc thử' }).click();
  await expect(page.getByText('Bản đọc thử.', { exact: true })).toBeVisible();
});

test('cultivation Reader sessions follow content, focus, chapter changes, and server policy', async ({
  page,
}) => {
  await installReaderRoutes(page, 200, false);
  const events = await installCultivationSessionRoutes(page, true, true, true);
  await page.addInitScript(() => {
    let readerFocused = false;
    Object.defineProperty(document, 'hasFocus', {
      configurable: true,
      value: () => readerFocused,
    });
    Object.defineProperty(window, 'setReaderPageFocus', {
      configurable: true,
      value: (focused: boolean) => {
        readerFocused = focused;
      },
    });
  });
  const networkOrder: string[] = [];
  page.on('requestfinished', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/v1/chapters/')) {
      networkOrder.push('chapter-content-finished');
    }
  });
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/me/reading-sessions')) {
      networkOrder.push('session-start-requested');
    }
  });
  const profileResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/me/cultivation',
  );
  await page.goto(`/app/reader/${workId}`);

  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await profileResponse;
  await page.waitForTimeout(100);
  expect(events.filter((event) => event.startsWith('start:'))).toEqual([]);
  await page.evaluate(() => {
    (window as Window & { setReaderPageFocus: (focused: boolean) => void }).setReaderPageFocus(
      true,
    );
    window.dispatchEvent(new Event('focus'));
  });
  await expect
    .poll(() => events.filter((event) => event.startsWith('start:')))
    .toEqual([`start:${chapterIds[0]}`]);
  expect(events[0]).toBe(`start:${chapterIds[0]}`);
  expect(networkOrder.indexOf('chapter-content-finished')).toBeLessThan(
    networkOrder.indexOf('session-start-requested'),
  );

  await page.evaluate(() => {
    (window as Window & { setReaderPageFocus: (focused: boolean) => void }).setReaderPageFocus(
      false,
    );
    window.dispatchEvent(new Event('blur'));
  });
  await expect.poll(() => events).toContain('heartbeat-retried-same-key');
  await expect.poll(() => events).toContain('heartbeat:1:false');
  expect(events.filter((event) => event.startsWith('heartbeat-attempt:'))).toEqual([
    'heartbeat-attempt:1:false',
    'heartbeat-attempt:1:false',
  ]);
  expect(events.filter((event) => event.startsWith('heartbeat-503:'))).toEqual([
    'heartbeat-503:1:false',
  ]);
  expect(events.filter((event) => event === 'heartbeat-retried-same-key')).toHaveLength(1);
  expect(events.filter((event) => event.startsWith('heartbeat:'))).toEqual(['heartbeat:1:false']);
  await page.evaluate(() => {
    (window as Window & { setReaderPageFocus: (focused: boolean) => void }).setReaderPageFocus(
      true,
    );
    window.dispatchEvent(new Event('focus'));
  });
  await expect.poll(() => events).toContain('heartbeat:2:true');
  expect(events.filter((event) => event.startsWith('heartbeat:'))).toEqual([
    'heartbeat:1:false',
    'heartbeat:2:true',
  ]);

  await page.getByRole('button', { name: 'Mở mục lục' }).click();
  await page
    .getByRole('dialog', { name: 'Gió qua núi' })
    .getByRole('button', { name: /Qua khỏi dốc/ })
    .click();
  await expect(page.getByRole('heading', { name: 'Qua khỏi dốc' })).toBeVisible();
  await expect
    .poll(() => events.filter((event) => event.startsWith('start:')))
    .toEqual([`start:${chapterIds[0]}`, `start:${chapterIds[2]}`]);
  expect(events.indexOf('heartbeat:3:false')).toBeLessThan(
    events.findIndex((event) => event.startsWith('close:')),
  );
  expect(events.findIndex((event) => event.startsWith('close:'))).toBeLessThan(
    events.lastIndexOf(`start:${chapterIds[2]}`),
  );
});

for (const scenario of [
  { label: 'global feature is off', featureEnabled: false, profileEnabled: true, expectedPosts: 0 },
  { label: 'reader opted out', featureEnabled: true, profileEnabled: false, expectedPosts: 0 },
]) {
  test(`cultivation Reader does not start a session when ${scenario.label}`, async ({ page }) => {
    await installReaderRoutes(page, 200, false);
    await installCultivationSessionRoutes(page, scenario.featureEnabled, scenario.profileEnabled);
    const sessionPosts: string[] = [];
    let profileResponseCount = 0;
    let systemStatusResponseCount = 0;
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().includes('/api/v1/me/reading-sessions')) {
        sessionPosts.push(request.url());
      }
    });
    page.on('response', (response) => {
      if (response.request().method() !== 'GET') return;
      const path = new URL(response.url()).pathname;
      if (path === '/api/v1/system/status') systemStatusResponseCount += 1;
      if (path === '/api/v1/me/cultivation') profileResponseCount += 1;
    });
    await page.goto(`/app/reader/${workId}`);
    await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
    await expect.poll(() => systemStatusResponseCount).toBe(1);
    await expect.poll(() => profileResponseCount).toBe(scenario.featureEnabled ? 1 : 0);
    expect(sessionPosts).toHaveLength(scenario.expectedPosts);
    if (!scenario.featureEnabled) {
      await expect(page.getByText('Một đoạn văn bản riêng tư.')).toBeVisible();
    }
  });
}

test('saves, opens across chapters, and removes a private Reader bookmark at mobile width', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false);
  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();

  await page.getByRole('button', { name: 'Mở dấu trang' }).click();
  const panel = page.getByRole('dialog', { name: 'Vị trí đã lưu' });
  await expect(panel.getByRole('heading', { name: 'Chưa có dấu trang' })).toBeVisible();
  await expectNoAxeViolations(page);
  await panel.getByRole('button', { name: 'Lưu vị trí đang đọc' }).click();
  const bookmark = panel.getByRole('button', {
    name: 'Chương 1 · Cánh cửa mở',
    exact: true,
  });
  await expect(bookmark).toBeVisible();
  await expect(page.locator('.account-reader-bookmark-global-message')).toContainText(
    'Đã lưu vị trí đọc hiện tại.',
  );
  await expectNoAxeViolations(page);

  await panel.getByRole('button', { name: /Đóng dấu trang/ }).click();
  await page.getByRole('button', { name: 'Mở mục lục' }).click();
  await page
    .getByRole('dialog', { name: 'Gió qua núi' })
    .getByRole('button', { name: /Qua khỏi dốc/ })
    .click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[2]}$`));
  await page.getByRole('button', { name: 'Mở dấu trang' }).click();
  const reopenedPanel = page.getByRole('dialog', { name: 'Vị trí đã lưu' });
  await reopenedPanel.getByRole('button', { name: 'Chương 1 · Cánh cửa mở', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[0]}$`));
  await expect(page.locator('[data-source-anchor="ch1-h1"]')).toBeFocused();

  await page.getByRole('button', { name: 'Mở dấu trang' }).click();
  const finalPanel = page.getByRole('dialog', { name: 'Vị trí đã lưu' });
  await finalPanel.getByRole('button', { name: 'Xóa Chương 1 · Cánh cửa mở', exact: true }).click();
  await expect(finalPanel.getByRole('heading', { name: 'Chưa có dấu trang' })).toBeVisible();
  await expect(page.locator('.account-reader-bookmark-global-message')).toContainText(
    'Đã xóa dấu trang.',
  );
  await expectNoAxeViolations(page);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
});

test('explains when a bookmark anchor is absent from the current chapter revision', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false, 200, false, undefined, undefined, false, [
    {
      id: 'bbbbbbbb-bbbb-4bbb-8bbb-000000000001',
      workId,
      chapterId: chapterIds[0],
      segmentAnchor: 'ch1-removed-anchor',
      label: 'Chương 1 · Bản cũ',
      createdAt: '2026-11-11T10:00:00Z',
    },
  ]);
  await page.goto(`/app/reader/${workId}/${chapterIds[2]}`);
  await expect(page.getByRole('heading', { name: 'Qua khỏi dốc' })).toBeVisible();
  await page.getByRole('button', { name: 'Mở dấu trang' }).click();
  await page
    .getByRole('dialog', { name: 'Vị trí đã lưu' })
    .getByRole('button', {
      name: 'Chương 1 · Bản cũ',
      exact: true,
    })
    .click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[0]}$`));
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeFocused();
  await expect(page.locator('.account-reader-bookmark-global-message')).toContainText(
    'Đoạn đã đánh dấu không còn trong phiên bản chương hiện tại',
  );
  await expectNoAxeViolations(page);
});

test('keeps a bookmark jump retryable when the next manifest page is temporarily unavailable', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, [503, 200], false, 200, true, undefined, undefined, false, [
    {
      id: 'bbbbbbbb-bbbb-4bbb-8bbb-000000000003',
      workId,
      chapterId: chapterIds[2],
      segmentAnchor: 'ch3-p1',
      label: 'Chương 3 · Qua khỏi dốc',
      createdAt: '2026-11-11T10:00:00Z',
    },
  ]);
  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  const panel = page.getByRole('dialog', { name: 'Vị trí đã lưu' });
  await page.getByRole('button', { name: 'Mở dấu trang' }).click();
  await panel.getByRole('button', { name: 'Chương 3 · Qua khỏi dốc', exact: true }).click();
  await expect(panel.getByRole('alert')).toContainText('Reader đang tạm thời không khả dụng');
  await expect(panel.getByRole('button', { name: 'Thử mở chương lại' })).toBeVisible();
  await expectNoAxeViolations(page);

  await panel.getByRole('button', { name: 'Thử mở chương lại' }).click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[2]}$`));
  await expect(page.getByRole('heading', { name: 'Qua khỏi dốc' })).toBeVisible();
});

test('restores and syncs Reader typography preferences across reloads', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  const preferences: MockReaderPreferences = {
    schemaVersion: 2,
    theme: 'dark',
    fontFamily: 'serif',
    fontSize: 22,
    lineHeight: 1.7,
    contentWidth: 760,
    pairedModeByDefault: false,
  };
  const writes: MockReaderPreferences[] = [];
  await installReaderRoutes(page, 200, false);
  await page.route('**/api/v1/me/reader-preferences', async (route) => {
    if (route.request().method() === 'GET') return json(route, preferences);
    const saved = route.request().postDataJSON() as MockReaderPreferences;
    writes.push(saved);
    Object.assign(preferences, saved);
    return json(route, preferences);
  });
  await page.goto(`/app/reader/${workId}`);

  const root = page.locator('.account-reader-app');
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await expect(root).toHaveClass(/reader-dark/);
  await expect(root.locator('.reader-article')).toHaveCSS('font-size', '22px');
  await page.getByRole('button', { name: 'Cài đặt trình đọc' }).click();
  const settings = page.getByRole('dialog', { name: 'Cài đặt đọc' });
  await expect(settings.locator('#account-reader-line-height')).toHaveValue('1.7');
  await expect(settings.locator('#account-reader-content-width')).toHaveValue('760');

  await settings.getByRole('button', { name: 'Sepia' }).click();
  await settings.getByRole('button', { name: /A\+.*tăng cỡ chữ/i }).click();
  await settings.locator('#account-reader-line-height').evaluate((element) => {
    const input = element as HTMLInputElement;
    input.value = '1.9';
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await settings.locator('#account-reader-content-width').evaluate((element) => {
    const input = element as HTMLInputElement;
    input.value = '840';
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await expect.poll(() => writes.at(-1)?.contentWidth).toBe(840);
  await expect(settings.getByRole('status')).toContainText('Cài đặt đã đồng bộ');
  expect(writes.at(-1)).toEqual({
    schemaVersion: 2,
    theme: 'sepia',
    fontFamily: 'serif',
    fontSize: 23,
    lineHeight: 1.9,
    contentWidth: 840,
    pairedModeByDefault: false,
  });

  await page.reload();
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await expect(root).toHaveClass(/reader-sepia/);
  await expect(root.locator('.reader-article')).toHaveCSS('font-size', '23px');
  await page.getByRole('button', { name: 'Cài đặt trình đọc' }).click();
  const reloadedSettings = page.getByRole('dialog', { name: 'Cài đặt đọc' });
  await expect(reloadedSettings.locator('#account-reader-line-height')).toHaveValue('1.9');
  await expect(reloadedSettings.locator('#account-reader-content-width')).toHaveValue('840');
  await expectNoAxeViolations(page);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
});

test('resumes the saved chapter anchor and debounces Reader progress writes', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  const saves: MockProgressWrite[] = [];
  await installReaderRoutes(
    page,
    200,
    false,
    200,
    false,
    {
      workId,
      chapterId: chapterIds[2],
      segmentAnchor: 'ch3-p14',
      scrollRatio: 0.65,
      version: 4,
      updatedAt: '2026-11-11T10:00:00Z',
    },
    (request) => saves.push(request),
    true,
  );
  await page.goto(`/app/reader/${workId}`);

  await expect(page.getByRole('heading', { name: 'Qua khỏi dốc' })).toBeVisible();
  await expect(page.locator('[data-source-anchor="ch3-p14"]')).toBeVisible();
  await expect(page.getByRole('status')).toContainText('Tiến độ đã đồng bộ');
  await expect
    .poll(() => page.locator('.account-reader-content').evaluate((element) => element.scrollTop))
    .toBeGreaterThan(0);

  await page.locator('.account-reader-content').evaluate((element) => {
    element.scrollTop = element.scrollHeight;
    element.dispatchEvent(new Event('scroll'));
  });
  await expect.poll(() => saves.at(-1)?.segmentAnchor).toBe('ch3-p20');
  expect(saves[0]).toMatchObject({
    expectedVersion: 4,
    chapterId: chapterIds[2],
  });
  expect(saves.at(-1)).toMatchObject({
    chapterId: chapterIds[2],
    segmentAnchor: 'ch3-p20',
  });
  await expect(page.getByRole('status')).toContainText('Tiến độ đã đồng bộ');

  await page.reload();
  await expect(page.getByRole('heading', { name: 'Qua khỏi dốc' })).toBeVisible();
  await expect(page.locator('[data-source-anchor="ch3-p20"]')).toBeVisible();
});

test('uses the saved scroll ratio when a revision no longer contains its anchor', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(
    page,
    200,
    false,
    200,
    false,
    {
      workId,
      chapterId: chapterIds[2],
      segmentAnchor: 'anchor-from-older-revision',
      scrollRatio: 0.6,
      version: 2,
      updatedAt: '2026-11-11T10:00:00Z',
    },
    undefined,
    true,
  );
  await page.goto(`/app/reader/${workId}`);

  await expect(page.getByRole('heading', { name: 'Qua khỏi dốc' })).toBeVisible();
  await expect
    .poll(() =>
      page.locator('.account-reader-content').evaluate((element) => {
        const availableScroll = element.scrollHeight - element.clientHeight;
        return availableScroll > 0 ? element.scrollTop / availableScroll : 0;
      }),
    )
    .toBeCloseTo(0.6, 1);
});

test('keeps an explicitly requested unavailable chapter in its recovery state', async ({
  page,
}) => {
  await installReaderRoutes(page, 200, false);
  await page.goto(`/app/reader/${workId}/${chapterIds[1]}`);

  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[1]}$`));
  await expect(page.getByRole('heading', { name: 'Chương chưa sẵn sàng' })).toBeVisible();
  await expect(page.locator('.account-reader-state button')).toContainText('Mở mục lục');
});

test('public work details link a locked chapter to its Reader paywall', async ({ page }) => {
  await installReaderRoutes(page, 200, false);
  await installPublicLockedManifest(page, true);
  await page.route(`**/api/v1/chapters/${chapterIds[1]}/unlock-quote`, (route) =>
    json(route, {
      chapterId: chapterIds[1],
      workId,
      chapterTitle: 'Bản nháp',
      workTitle: 'Gió qua núi công khai',
      priceVersion: 1,
      priceCredits: 10,
      accessAlreadyGranted: false,
    }),
  );
  await page.route('**/api/v1/me/credits/balance', (route) =>
    json(route, {
      paidCredits: 100,
      bonusCredits: 0,
      totalCredits: 100,
      asOf: '2026-11-15T10:00:00Z',
    }),
  );

  await page.goto(`/app/works/${workId}`);
  await expect(page.getByRole('heading', { name: 'Gió qua núi công khai' })).toBeVisible();
  await expect(page.getByText('Công khai', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: /Chương 2 Bản nháp Xem giá & mở/ }).click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[1]}$`));
  await expect(page.getByRole('heading', { name: 'Bản nháp' })).toBeVisible();
  await expect(page.getByText('10 credit đã mua', { exact: true })).toBeVisible();
  await expectNoAxeViolations(page);
});

test('requires explicit confirmation before paid-credit chapter unlock', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 360, height: 800 });
  const loadedChapters = await installReaderRoutes(page, 200, false);
  const publicWork = { ...work, visibility: 'public', title: 'Gió qua núi công khai' };
  await installPublicLockedManifest(page, true);
  let quoteRequests = 0;
  await page.route(`**/api/v1/chapters/${chapterIds[1]}/unlock-quote`, async (route) => {
    quoteRequests += 1;
    await json(route, {
      chapterId: chapterIds[1],
      workId,
      chapterTitle: 'Bản nháp',
      workTitle: publicWork.title,
      priceVersion: 1,
      priceCredits: 10,
      accessAlreadyGranted: false,
    });
  });
  let balanceRequests = 0;
  await page.route('**/api/v1/me/credits/balance', async (route) => {
    balanceRequests += 1;
    await json(route, {
      paidCredits: 100,
      bonusCredits: 0,
      totalCredits: 100,
      asOf: '2026-11-15T10:00:00Z',
    });
  });
  let unlockRequests = 0;
  const idempotencyKeys: string[] = [];
  await page.route(`**/api/v1/chapters/${chapterIds[1]}/unlock`, async (route) => {
    unlockRequests += 1;
    const request = route.request();
    expect(request.method()).toBe('POST');
    expect(request.headers()['x-csrf-token']).toBe('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA');
    expect(request.postDataJSON()).toEqual({ priceVersion: 1 });
    const idempotencyKey = request.headers()['idempotency-key'] ?? '';
    idempotencyKeys.push(idempotencyKey);
    expect(idempotencyKey).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
    if (unlockRequests === 1) {
      await json(route, { code: 'TEMPORARY_RESULT_UNKNOWN' }, 503);
      return;
    }
    await json(
      route,
      {
        outcome: 'REPLAYED',
        entitlementId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
        chapterId: chapterIds[1],
        priceVersion: 1,
        creditsSpent: 10,
        grantedAt: '2026-11-15T10:15:00Z',
      },
      200,
    );
  });

  await page.goto(`/app/reader/${workId}/${chapterIds[1]}`);
  await expect(page.getByRole('heading', { name: 'Bản nháp' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Bản nháp' })).toBeFocused();
  await expect(page.getByText('10 credit đã mua', { exact: true })).toBeVisible();
  await expect(page.getByText('100', { exact: true })).toBeVisible();
  await expect.poll(() => quoteRequests).toBe(1);
  expect(loadedChapters).not.toContain(chapterIds[1]);
  await expectNoAxeViolations(page);
  await page.screenshot({ path: testInfo.outputPath('reader-paywall-360x800.png') });

  await page.getByRole('button', { name: 'Tiếp tục đến xác nhận' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Xác nhận mở chương' })).toBeFocused();
  await expectNoAxeViolations(page);
  await page.screenshot({ path: testInfo.outputPath('reader-paywall-confirmation-360x800.png') });
  await expect(page.getByRole('button', { name: 'Xác nhận · 10 credit' })).toBeVisible();
  await page.getByRole('button', { name: 'Hủy', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('button', { name: 'Tiếp tục đến xác nhận' })).toBeFocused();
  expect(unlockRequests).toBe(0);
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Tiếp tục đến xác nhận' }).click();
  await page.getByRole('button', { name: 'Xác nhận · 10 credit' }).click();
  await expect(page.getByText(/Chưa xác nhận được kết quả/)).toBeVisible();
  expect(unlockRequests).toBe(1);
  expect(loadedChapters).not.toContain(chapterIds[1]);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Bản nháp' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tiếp tục đến xác nhận' })).toBeVisible();
  await expect.poll(() => quoteRequests).toBe(2);
  await page.getByRole('button', { name: 'Tiếp tục đến xác nhận' }).click();
  await page.getByRole('button', { name: 'Xác nhận · 10 credit' }).click();
  await expect(page.locator('.account-reader-content')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Bản nháp' })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('reader-unlocked-360x800.png') });
  await expect.poll(() => unlockRequests).toBe(2);
  await expect.poll(() => balanceRequests).toBe(2);
  expect(idempotencyKeys[0].length).toBeGreaterThanOrEqual(16);
  expect(idempotencyKeys[1]).toBe(idempotencyKeys[0]);
  expect(loadedChapters).toContain(chapterIds[1]);
  await expectNoAxeViolations(page);
});

test('paid chapter confirmation stays keyboard-accessible at desktop width', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await installReaderRoutes(page, 200, false);
  await installPublicLockedManifest(page, true);
  await page.route(`**/api/v1/chapters/${chapterIds[1]}/unlock-quote`, (route) =>
    json(route, {
      chapterId: chapterIds[1],
      workId,
      chapterTitle: 'Bản nháp',
      workTitle: 'Gió qua núi công khai',
      priceVersion: 1,
      priceCredits: 10,
      accessAlreadyGranted: false,
    }),
  );
  await page.route('**/api/v1/me/credits/balance', (route) =>
    json(route, {
      paidCredits: 100,
      bonusCredits: 0,
      totalCredits: 100,
      asOf: '2026-11-15T10:00:00Z',
    }),
  );
  let unlockRequests = 0;
  await page.route(`**/api/v1/chapters/${chapterIds[1]}/unlock`, async (route) => {
    unlockRequests += 1;
    await json(route, { code: 'UNEXPECTED_UNLOCK' }, 500);
  });

  await page.goto(`/app/reader/${workId}/${chapterIds[1]}`);
  await expect(page.getByRole('heading', { name: 'Bản nháp' })).toBeVisible();
  await expect(page.locator('.reader-paywall-lock-icon')).toBeVisible();
  await expect(page.getByText('10 credit đã mua', { exact: true })).toBeVisible();
  await expect(page.getByText('100', { exact: true })).toBeVisible();
  let dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  await expectNoAxeViolations(page);
  await page.screenshot({ path: testInfo.outputPath('reader-paywall-1280x900.png') });

  const continueButton = page.getByRole('button', { name: 'Tiếp tục đến xác nhận' });
  await continueButton.focus();
  await page.keyboard.press('Enter');
  const confirmationHeading = page.getByRole('heading', { name: 'Xác nhận mở chương' });
  await expect(confirmationHeading).toBeFocused();
  const confirmButton = page.getByRole('button', { name: 'Xác nhận · 10 credit' });
  const confirmBounds = await confirmButton.boundingBox();
  expect(confirmBounds).not.toBeNull();
  expect(confirmBounds!.width).toBeGreaterThanOrEqual(44);
  expect(confirmBounds!.height).toBeGreaterThanOrEqual(44);
  dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  await expectNoAxeViolations(page);
  await page.screenshot({ path: testInfo.outputPath('reader-confirmation-1280x900.png') });

  const cancelButton = page.getByRole('button', { name: 'Hủy', exact: true });
  await cancelButton.focus();
  await page.keyboard.press('Enter');
  await expect(continueButton).toBeFocused();
  expect(unlockRequests).toBe(0);
  await expectNoAxeViolations(page);
});

test('does not request a quote or show an unlock action when VNPAY TEST is off', async ({
  page,
}) => {
  await installReaderRoutes(page, 200, false);
  await installPublicLockedManifest(page, false);
  let quoteRequests = 0;
  await page.route(`**/api/v1/chapters/${chapterIds[1]}/unlock-quote`, async (route) => {
    quoteRequests += 1;
    await json(route, { code: 'SHOULD_NOT_BE_REQUESTED' }, 503);
  });

  await page.goto(`/app/reader/${workId}/${chapterIds[1]}`);
  await expect(page.getByRole('heading', { name: 'Bản nháp' })).toBeVisible();
  await expect(page.getByText(/VNPAY TEST chưa được bật/)).toBeVisible();
  await expect(page.getByRole('button', { name: /mở chương/i })).toHaveCount(0);
  await expect.poll(() => quoteRequests).toBe(0);
  await expectNoAxeViolations(page);
});

test('locked-chapter paywall fits a 200% effective mobile viewport', async ({ browser }) => {
  const context = await browser.newContext({
    viewport: { width: 180, height: 400 },
    deviceScaleFactor: 2,
  });
  try {
    const page = await context.newPage();
    await installReaderRoutes(page, 200, false);
    await installPublicLockedManifest(page, true);
    await page.route(`**/api/v1/chapters/${chapterIds[1]}/unlock-quote`, (route) =>
      json(route, {
        chapterId: chapterIds[1],
        workId,
        chapterTitle: 'Bản nháp',
        workTitle: 'Gió qua núi công khai',
        priceVersion: 1,
        priceCredits: 10,
        accessAlreadyGranted: false,
      }),
    );
    await page.route('**/api/v1/me/credits/balance', (route) =>
      json(route, {
        paidCredits: 100,
        bonusCredits: 0,
        totalCredits: 100,
        asOf: '2026-11-15T10:00:00Z',
      }),
    );

    await page.goto(`/app/reader/${workId}/${chapterIds[1]}`);
    await expect(page.getByRole('heading', { name: 'Bản nháp' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Tiếp tục đến xác nhận' })).toBeVisible();
    const paywall = page.getByRole('region', { name: 'Bản nháp' });
    await paywall.focus();
    await page.keyboard.press('End');
    await expect(page.getByRole('button', { name: 'Tiếp tục đến xác nhận' })).toBeInViewport();
    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
      dpr: window.devicePixelRatio,
    }));
    expect(dimensions.dpr).toBe(2);
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: test.info().outputPath('reader-paywall-effective-200-percent.png'),
      fullPage: false,
    });
  } finally {
    await context.close();
  }
});

test('account Reader remains usable in an effective 200% mobile viewport', async ({ browser }) => {
  await verifyEffectiveZoom(browser);
});

test('account Reader dialogs keep keyboard focus and restore it to the opener', async ({
  page,
}) => {
  const expectNoHorizontalOverflow = async () => {
    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  };
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false);
  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeFocused();
  await expectNoAxeViolations(page);
  await expectNoHorizontalOverflow();
  await page.screenshot({ path: test.info().outputPath('audit-01-reader.png') });

  const tocOpener = page.getByRole('button', { name: 'Mở mục lục' });
  const settingsOpener = page.getByRole('button', { name: 'Cài đặt trình đọc' });
  await tocOpener.focus();
  await expect(tocOpener).toBeFocused();
  await page.keyboard.press('Enter');
  const toc = page.getByRole('dialog', { name: 'Gió qua núi' });
  const tocClose = toc.getByRole('button', { name: 'Đóng mục lục' });
  await expect(toc.getByRole('button', { name: /Bản nháp/ })).toBeDisabled();
  await expect(toc.getByText('Chưa sẵn sàng', { exact: true })).toBeVisible();
  await expect(tocClose).toBeFocused();
  await expectNoAxeViolations(page);
  await expectNoHorizontalOverflow();
  await page.screenshot({ path: test.info().outputPath('audit-02-toc-open.png') });

  const lastChapter = toc.getByRole('button', { name: /Qua khỏi dốc/ });
  await page.keyboard.press('Shift+Tab');
  await expect(lastChapter).toBeFocused();
  await page.screenshot({ path: test.info().outputPath('audit-03-toc-focus-wrap.png') });
  await page.keyboard.press('Tab');
  await expect(tocClose).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(toc).toHaveCount(0);
  await expect(tocOpener).toBeFocused();
  await page.screenshot({ path: test.info().outputPath('audit-04-toc-closed-focus-return.png') });

  await page.keyboard.press('Tab');
  await expect(settingsOpener).toBeFocused();
  await page.keyboard.press('Enter');
  const settings = page.getByRole('dialog', { name: 'Cài đặt đọc' });
  const settingsClose = settings.getByRole('button', { name: 'Đóng cài đặt' });
  await expect(settingsClose).toBeFocused();
  await expect(settings.getByRole('button', { name: /A−.*giảm cỡ chữ/i })).toBeVisible();
  await expect(settings.getByRole('button', { name: /A\+.*tăng cỡ chữ/i })).toBeVisible();
  await expectNoAxeViolations(page);
  await expectNoHorizontalOverflow();
  await page.screenshot({ path: test.info().outputPath('audit-05-settings-open.png') });
  await page.keyboard.press('Escape');
  await expect(settings).toHaveCount(0);
  await expect(settingsOpener).toBeFocused();
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: test.info().outputPath('audit-06-settings-closed-focus-return.png'),
  });
});

for (const recovery of [
  { status: 401, name: 'sign-in', label: 'Đăng nhập lại', query: 'returnTo' },
  { status: 403, name: 'policy setup', label: 'Xem chính sách tài khoản', query: 'returnUrl' },
]) {
  test(`account Reader preserves its route through ${recovery.name} recovery`, async ({ page }) => {
    await installReaderRoutes(page, recovery.status);
    await page.goto(`/app/reader/${workId}/${chapterIds[0]}`);
    const recoveryLink = page.getByRole('link', { name: recovery.label });
    await expect(recoveryLink).toBeVisible();
    const href = await recoveryLink.getAttribute('href');
    expect(href).toContain(`${recovery.query}=`);
    expect(decodeURIComponent(href ?? '')).toContain(`/app/reader/${workId}/${chapterIds[0]}`);
    if (recovery.status === 403) {
      await recoveryLink.click();
      await expect(page).toHaveURL(/\/app\/setup\?returnUrl=/);
      const continueButton = page.getByRole('button', { name: 'Vào không gian đọc' });
      await expect(continueButton).toBeEnabled();
      await continueButton.click();
      await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[0]}$`));
    }
  });
}

for (const recovery of [
  { status: 401, label: 'Đăng nhập lại', query: 'returnTo' },
  { status: 403, label: 'Xem chính sách tài khoản', query: 'returnUrl' },
]) {
  test(`account Reader uses ${recovery.label.toLowerCase()} for paginated TOC recovery`, async ({
    page,
  }) => {
    await installReaderRoutes(page, [recovery.status], true, 200, true);
    await page.goto(`/app/reader/${workId}`);
    await page.getByRole('button', { name: 'Mở mục lục' }).click();

    const toc = page.getByRole('dialog', { name: 'Gió qua núi' });
    await toc.getByRole('button', { name: 'Tải thêm chương' }).click();
    const error = toc.getByRole('alert');
    const recoveryLink = error.getByRole('link', { name: recovery.label });
    await expect(error).toContainText(
      recovery.status === 401 ? 'Phiên đăng nhập đã hết hạn.' : 'Cần xác nhận chính sách hiện hành',
    );
    await expect(recoveryLink).toBeVisible();
    await expect(recoveryLink).toBeFocused();
    await expect(error.getByRole('button', { name: 'Thử tải lại' })).toHaveCount(0);
    await expectNoAxeViolations(page);

    const href = await recoveryLink.getAttribute('href');
    expect(href).toContain(`${recovery.query}=`);
    expect(decodeURIComponent(href ?? '')).toContain(`/app/reader/${workId}/${chapterIds[0]}`);
    if (recovery.status === 403) {
      await recoveryLink.click();
      await expect(page).toHaveURL(/\/app\/setup\?returnUrl=/);
      const continueButton = page.getByRole('button', { name: 'Vào không gian đọc' });
      await expect(continueButton).toBeEnabled();
      await continueButton.click();
      await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[0]}$`));
    }
  });
}

test('account Reader announces a transient manifest failure and recovers with retry', async ({
  page,
}) => {
  await installReaderRoutes(page, [503, 200]);
  await page.goto(`/app/reader/${workId}/${chapterIds[0]}`);

  const error = page.getByRole('alert');
  await expect(error).toBeVisible();
  await expect(error.getByRole('heading', { name: 'Chưa mở được tác phẩm' })).toBeVisible();
  await expect(error).toContainText('Reader đang tạm thời không khả dụng. Thử lại sau ít phút.');
  await expect(error.getByRole('link', { name: 'Quay lại thư viện' })).toBeVisible();
  await expectNoAxeViolations(page);

  await error.getByRole('button', { name: 'Thử lại' }).click();

  const chapterHeading = page.getByRole('heading', { name: 'Cánh cửa mở' });
  await expect(chapterHeading).toBeVisible();
  await expect(chapterHeading).toBeFocused();
  await expect(error).toHaveCount(0);
  await expectNoAxeViolations(page);
});

test('account Reader announces a transient chapter-content failure and recovers with retry', async ({
  page,
}) => {
  const loadedChapters = await installReaderRoutes(page, 200, true, [503, 200]);
  await page.goto(`/app/reader/${workId}/${chapterIds[0]}`);

  const error = page.getByRole('alert');
  await expect(error).toBeVisible();
  await expect(error.getByRole('heading', { name: 'Chưa tải được chương' })).toBeVisible();
  await expect(error).toContainText('Reader đang tạm thời không khả dụng. Thử lại sau ít phút.');
  await expect(error.getByRole('link', { name: 'Quay lại thư viện' })).toBeVisible();
  await expectNoAxeViolations(page);

  await error.getByRole('button', { name: 'Tải lại chương' }).click();

  const chapterHeading = page.getByRole('heading', { name: 'Cánh cửa mở' });
  await expect(chapterHeading).toBeVisible();
  await expect(chapterHeading).toBeFocused();
  await expect(page.getByText('Một đoạn văn bản riêng tư.')).toBeVisible();
  await expect(error).toHaveCount(0);
  expect(loadedChapters).toEqual([chapterIds[0], chapterIds[0]]);
  await expectNoAxeViolations(page);
});

test('account Reader keeps focus on the new TOC chapter after retrying a failed page', async ({
  page,
}) => {
  await installReaderRoutes(page, [503, 503, 200], true, 200, true);
  await page.goto(`/app/reader/${workId}`);
  await page.getByRole('button', { name: 'Mở mục lục' }).click();

  const toc = page.getByRole('dialog', { name: 'Gió qua núi' });
  await toc.getByRole('button', { name: 'Tải thêm chương' }).click();
  const error = toc.getByRole('alert');
  await expect(error).toBeVisible();
  const retry = error.getByRole('button', { name: 'Thử tải lại' });
  await expect(retry).toBeFocused();
  await expectNoAxeViolations(page);

  await retry.click();
  await expect(error).toBeVisible();
  await expect(retry).toBeEnabled();
  await expect(retry).toBeFocused();
  await retry.click();

  const loadedChapter = toc.getByRole('button', { name: /Qua khỏi dốc/ });
  await expect(loadedChapter).toBeVisible();
  await expect(loadedChapter).toBeFocused();
  await expect(error).toHaveCount(0);
  await expectNoAxeViolations(page);
});

test('account Reader moves keyboard focus to newly loaded TOC chapters', async ({ page }) => {
  await installReaderRoutes(page, 200, true, 200, true);
  await page.goto(`/app/reader/${workId}`);
  await page.getByRole('button', { name: 'Mở mục lục' }).click();

  const toc = page.getByRole('dialog', { name: 'Gió qua núi' });
  await toc.getByRole('button', { name: 'Tải thêm chương' }).click();

  const loadedChapter = toc.getByRole('button', { name: /Qua khỏi dốc/ });
  await expect(loadedChapter).toBeVisible();
  await expect(loadedChapter).toBeFocused();
  await expectNoAxeViolations(page);
});

test('account Reader reports and recovers a failed next-chapter page outside the TOC', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, [503, 200], true, 200, true);
  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await page.getByRole('button', { name: 'Chương tiếp' }).click();

  const error = page.getByRole('alert');
  await expect(error).toBeVisible();
  await expect(error).toContainText('Reader đang tạm thời không khả dụng. Thử lại sau ít phút.');
  const retry = error.getByRole('button', { name: 'Thử lại' });
  await expect(retry).toBeFocused();
  const retryBounds = await retry.boundingBox();
  expect(retryBounds?.height).toBeGreaterThanOrEqual(44);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  await expectNoAxeViolations(page);

  await retry.click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[2]}$`));
  await expect(page.getByRole('heading', { name: 'Qua khỏi dốc' })).toBeFocused();
  await expect(error).toHaveCount(0);
  await expectNoAxeViolations(page);
});

for (const recovery of [
  { status: 401, label: 'Đăng nhập lại', query: 'returnTo' },
  { status: 403, label: 'Xem chính sách tài khoản', query: 'returnUrl' },
]) {
  test(`account Reader provides ${recovery.label.toLowerCase()} outside the TOC`, async ({
    page,
  }) => {
    await installReaderRoutes(page, [recovery.status], true, 200, true);
    await page.goto(`/app/reader/${workId}`);
    await page.getByRole('button', { name: 'Chương tiếp' }).click();

    const error = page.getByRole('alert');
    const recoveryLink = error.getByRole('link', { name: recovery.label });
    await expect(recoveryLink).toBeFocused();
    await expect(error.getByRole('button', { name: 'Thử lại' })).toHaveCount(0);
    await expectNoAxeViolations(page);

    const href = await recoveryLink.getAttribute('href');
    expect(href).toContain(`${recovery.query}=`);
    expect(decodeURIComponent(href ?? '')).toContain(`/app/reader/${workId}/${chapterIds[0]}`);
    if (recovery.status === 403) {
      await recoveryLink.click();
      await expect(page).toHaveURL(/\/app\/setup\?returnUrl=/);
      const continueButton = page.getByRole('button', { name: 'Vào không gian đọc' });
      await expect(continueButton).toBeEnabled();
      await continueButton.click();
      await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[0]}$`));
    }
  });
}

async function selectReaderTextWithMouse(page: Page, anchor: string, quote: string): Promise<void> {
  const paragraph = page.locator(`[data-source-anchor="${anchor}"]`);
  const coordinates = await paragraph.evaluate((element, selectedQuote) => {
    const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    let textNode = walker.nextNode() as Text | null;
    while (textNode && !textNode.data.includes(selectedQuote)) {
      textNode = walker.nextNode() as Text | null;
    }
    if (!textNode) throw new Error('Selected quote was not found in the source segment');
    const start = textNode.data.indexOf(selectedQuote);
    const range = document.createRange();
    range.setStart(textNode, start);
    range.setEnd(textNode, start + selectedQuote.length);
    const rect = range.getBoundingClientRect();
    return {
      start: { x: rect.left + 1, y: rect.top + rect.height / 2 },
      end: { x: rect.right - 1, y: rect.top + rect.height / 2 },
    };
  }, quote);
  await page.mouse.move(coordinates.start.x, coordinates.start.y);
  await page.mouse.down();
  await page.mouse.move(coordinates.end.x + 2, coordinates.end.y, { steps: 8 });
  await page.mouse.up();
}

test('account Reader starts and tracks a clearly fake local translation preview', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false);
  await page.unroute('**/api/v1/system/status');
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '4.2.1',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled: false,
        vnpayEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: false,
        translationFakePreviewEnabled: true,
      },
    }),
  );

  const jobId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
  await page.route('**/api/v1/translation/jobs', async (route) => {
    const request = route.request();
    expect(request.method()).toBe('POST');
    expect(request.headers()['x-csrf-token']).toBe('A'.repeat(43));
    expect(request.headers()['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
    expect(request.postDataJSON()).toEqual({
      chapterId: chapterIds[0],
      sourceRevisionId: revisionId,
      targetLanguage: 'en',
    });
    await route.fulfill({
      status: 202,
      headers: { location: `/api/v1/translation/jobs/${jobId}` },
      contentType: 'application/json',
      body: JSON.stringify({
        jobId,
        status: 'queued',
        chapterId: chapterIds[0],
        sourceRevisionId: revisionId,
        sourceLanguage: 'vi',
        targetLanguage: 'en',
        providerId: 'fake-local',
        totalUnits: 2,
        replayed: false,
      }),
    });
  });
  await page.route(`**/api/v1/translation/jobs/${jobId}`, (route) =>
    json(route, {
      job: {
        id: jobId,
        workId,
        chapterId: chapterIds[0],
        sourceRevisionId: revisionId,
        sourceRevisionCurrent: true,
        workTitle: work.title,
        chapterTitle: chapters[0].title,
        chapterOrdinal: 1,
        canManage: true,
        sourceLanguage: 'vi',
        targetLanguage: 'en',
        providerId: 'fake-local',
        status: 'review_required',
        totalUnits: 2,
        completedUnits: 2,
        failedUnits: 0,
        queuedUnits: 0,
        runningUnits: 0,
        retryWaitUnits: 0,
        cancelledUnits: 0,
        failureCode: null,
        candidateId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
        candidateStatus: 'ready_for_review',
        createdAt: '2026-11-14T10:00:00Z',
        updatedAt: '2026-11-14T10:00:01Z',
        startedAt: '2026-11-14T10:00:00Z',
        completedAt: '2026-11-14T10:00:01Z',
        cancelRequestedAt: null,
      },
      terminalUnits: 2,
      progressPercent: 100,
    }),
  );

  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await expect(page.getByText('BẢN THỬ · KẾT QUẢ GIẢ')).toBeVisible();
  await page.getByRole('button', { name: 'Tạo bản xem thử' }).click();

  await expect(page).toHaveURL(new RegExp(`/app/translation/jobs/${jobId}$`));
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await expect(page.getByText('Một đoạn văn bản riêng tư.', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Bản thử đã tạo xong' })).toBeVisible();
  await expect(page.getByText(/không phải bản dịch dùng được/)).toBeVisible();
  await expect(page.getByText('Bản xem thử chưa được xuất bản')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Quay lại chương' })).toHaveAttribute(
    'href',
    `/app/reader/${workId}/${chapterIds[0]}`,
  );
  await page.screenshot({
    path: test.info().outputPath('translation-preview-mobile-360x800.png'),
    fullPage: false,
  });
  await expectNoAxeViolations(page);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
});

test('public Reader pairs matching anchors and rejects a mismatched released translation', async ({
  page,
}) => {
  const sourceContentRequests: string[] = [];
  page.on('request', (request) => {
    const pathname = new URL(request.url()).pathname;
    if (pathname === `/api/v1/chapters/${chapterIds[0]}/content`) {
      sourceContentRequests.push(request.url());
    }
  });
  await page.setViewportSize({ width: 1280, height: 900 });
  await installReaderRoutes(page, 200, false);
  await installPublicLockedManifest(page, false);
  await page.unroute('**/api/v1/system/status');
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '4.2.1',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled: false,
        vnpayEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: false,
        translationFakePreviewEnabled: false,
        translationPublicationEnabled: true,
      },
    }),
  );
  await page.route(`**/api/v1/chapters/${chapterIds[0]}/translations`, (route) =>
    json(route, { languages: ['en', 'fr'] }),
  );
  await page.route(`**/api/v1/chapters/${chapterIds[0]}/translations/en/content**`, (route) =>
    json(route, {
      chapterId: chapterIds[0],
      workId,
      sourceRevisionId: revisionId,
      translationVersionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      translationVersionNo: 3,
      sourceLanguage: 'vi',
      targetLanguage: 'en',
      segments: [
        { anchor: 'ch1-h1', type: 'heading', translatedText: 'The First Day' },
        { anchor: 'ch1-p1', type: 'paragraph', translatedText: 'A private paragraph.' },
        { anchor: 'ch1-p2', type: 'paragraph', translatedText: 'The wind grew quiet.' },
      ],
      nextSegmentCursor: null,
    }),
  );
  await page.route(`**/api/v1/chapters/${chapterIds[0]}/translations/fr/content**`, (route) =>
    json(route, {
      chapterId: chapterIds[0],
      workId,
      sourceRevisionId: revisionId,
      translationVersionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
      translationVersionNo: 1,
      sourceLanguage: 'vi',
      targetLanguage: 'fr',
      segments: [
        { anchor: 'incorrect-anchor', type: 'heading', translatedText: 'Le premier jour' },
        { anchor: 'ch1-p1', type: 'paragraph', translatedText: 'Un paragraphe privé.' },
        { anchor: 'ch1-p2', type: 'paragraph', translatedText: 'Le vent s’est calmé.' },
      ],
      nextSegmentCursor: null,
    }),
  );

  await page.goto(`/app/reader/${workId}/${chapterIds[0]}`);
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  const languagePicker = page.getByLabel('Ngôn ngữ');
  await expect(languagePicker).toBeVisible();
  await expect(languagePicker.locator('option[value="en"]')).toHaveCount(1);
  await languagePicker.selectOption('en');
  await expect(page.getByText('The First Day', { exact: true })).toBeVisible();
  const preferenceSave = page.waitForResponse(
    (response) =>
      response.url().includes('/api/v1/me/reader-preferences') &&
      response.request().method() === 'PUT',
  );
  await page.getByRole('button', { name: 'Đọc song ngữ' }).click();
  await preferenceSave;

  const sourceGroup = page.getByRole('group', { name: 'Nguyên tác · vi · đoạn 1' });
  const translationGroup = page.getByRole('group', { name: 'Bản dịch · en · đoạn 1' });
  await expect(sourceGroup).toContainText('Ngày đầu tiên');
  await expect(translationGroup).toContainText('The First Day');
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await expect(languagePicker.locator('option[value="en"]')).toHaveCount(1);
  await languagePicker.selectOption('en');
  await expect(page.getByRole('button', { name: 'Chỉ đọc bản dịch' })).toBeVisible();
  await expect(sourceGroup).toContainText('Ngày đầu tiên');
  await expect(translationGroup).toContainText('The First Day');
  const desktopColumns = await Promise.all([
    sourceGroup.boundingBox(),
    translationGroup.boundingBox(),
  ]);
  expect(desktopColumns[0]?.x).toBeLessThan(desktopColumns[1]?.x ?? 0);
  await expectNoAxeViolations(page);

  await page.setViewportSize({ width: 360, height: 800 });
  await expect(sourceGroup).toBeVisible();
  await expect(translationGroup).toBeVisible();
  const mobileColumns = await Promise.all([
    sourceGroup.boundingBox(),
    translationGroup.boundingBox(),
  ]);
  expect(mobileColumns[1]?.y).toBeGreaterThan(mobileColumns[0]?.y ?? 0);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  await expectNoAxeViolations(page);

  await expect(page.getByRole('button', { name: 'Chỉ đọc bản dịch' })).toBeVisible();
  await languagePicker.selectOption('fr');
  await expect(page.getByText('Le premier jour', { exact: true })).toBeVisible();
  expect(sourceContentRequests).toHaveLength(5);
  await expect(page.locator('.reader-paired-error')).toBeVisible();
  await expect(page.locator('.reader-paired-segment')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Đọc song ngữ' })).toBeVisible();
});

test('account Reader hides the local translation preview unless the API opt-in is active', async ({
  page,
}) => {
  await installReaderRoutes(page, 200, false);
  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tạo bản xem thử' })).toHaveCount(0);
  await expect(page.getByLabel('Bản xem thử dịch cục bộ')).toHaveCount(0);
});

test('translation job confirms writer cancellation and returns a terminal status', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false);
  const jobId = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
  const cancelEventId = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
  let jobStatus: 'queued' | 'cancelled' = 'queued';

  await page.route(`**/api/v1/translation/jobs/${jobId}`, (route) => {
    const cancelled = jobStatus === 'cancelled';
    return json(
      route,
      {
        job: {
          id: jobId,
          workId,
          chapterId: chapterIds[0],
          sourceRevisionId: revisionId,
          sourceRevisionCurrent: true,
          workTitle: work.title,
          chapterTitle: chapters[0].title,
          chapterOrdinal: 1,
          canManage: true,
          sourceLanguage: 'vi',
          targetLanguage: 'en',
          providerId: 'fake-local',
          status: jobStatus,
          totalUnits: 1,
          completedUnits: 0,
          failedUnits: 0,
          queuedUnits: cancelled ? 0 : 1,
          runningUnits: 0,
          retryWaitUnits: 0,
          cancelledUnits: cancelled ? 1 : 0,
          failureCode: null,
          candidateId: null,
          candidateStatus: null,
          createdAt: '2026-11-14T10:00:00Z',
          updatedAt: '2026-11-14T10:00:01Z',
          startedAt: null,
          completedAt: cancelled ? '2026-11-14T10:00:02Z' : null,
          cancelRequestedAt: null,
        },
        terminalUnits: cancelled ? 1 : 0,
        progressPercent: cancelled ? 100 : 0,
      },
      200,
    );
  });
  await page.route(`**/api/v1/translation/jobs/${jobId}/cancel`, async (route) => {
    const request = route.request();
    expect(request.method()).toBe('POST');
    expect(request.headers()['x-csrf-token']).toBe('A'.repeat(43));
    expect(request.headers()['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
    jobStatus = 'cancelled';
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        jobId,
        jobStatus,
        cancelledUnits: 1,
        cancelledAttempts: 0,
        replayed: false,
        outboxEventId: cancelEventId,
      }),
    });
  });

  await page.goto(`/app/translation/jobs/${jobId}`);
  await expect(page.getByRole('heading', { name: 'Đang chờ xử lý thử' })).toBeVisible();
  const cancelButton = page.getByRole('button', { name: 'Dừng bản xem thử' });
  await cancelButton.scrollIntoViewIfNeeded();
  await cancelButton.click();
  const dialog = page.getByRole('dialog', { name: 'Bạn muốn dừng job này?' });
  await expect(dialog).toBeVisible();
  await expectNoAxeViolations(page);

  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(cancelButton).toBeFocused();
  await cancelButton.click();
  await dialog.getByRole('button', { name: 'Xác nhận dừng' }).click();

  await expect(page.getByRole('heading', { name: 'Đã dừng bản thử' })).toBeVisible();
  await expect(page.getByText('100%')).toBeVisible();
  await expectNoAxeViolations(page);
});

test('a failed stale preview keeps the current source chapter readable', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false);
  const jobId = 'ffffffff-ffff-4fff-8fff-ffffffffffff';

  await page.route(`**/api/v1/translation/jobs/${jobId}`, (route) =>
    json(route, {
      job: {
        id: jobId,
        workId,
        chapterId: chapterIds[0],
        sourceRevisionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
        sourceRevisionCurrent: false,
        workTitle: work.title,
        chapterTitle: chapters[0].title,
        chapterOrdinal: 1,
        canManage: true,
        sourceLanguage: 'vi',
        targetLanguage: 'en',
        providerId: 'fake-local',
        status: 'failed',
        totalUnits: 1,
        completedUnits: 0,
        failedUnits: 1,
        queuedUnits: 0,
        runningUnits: 0,
        retryWaitUnits: 0,
        cancelledUnits: 0,
        failureCode: 'TRANSLATION_SOURCE_STALE',
        candidateId: null,
        candidateStatus: null,
        createdAt: '2026-11-14T10:00:00Z',
        updatedAt: '2026-11-14T10:00:01Z',
        startedAt: '2026-11-14T10:00:00Z',
        completedAt: '2026-11-14T10:00:01Z',
        cancelRequestedAt: null,
      },
      terminalUnits: 1,
      progressPercent: 100,
    }),
  );

  await page.goto(`/app/translation/jobs/${jobId}`);
  await expect(page.getByRole('heading', { name: 'Bản thử gặp lỗi' })).toBeVisible();
  await expect(page.getByText('Chương đã thay đổi kể từ khi bản thử bắt đầu.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Thử lại phần lỗi' })).toHaveCount(0);
  await expectNoAxeViolations(page);

  await page.getByRole('link', { name: 'Quay lại chương' }).click();
  await expect(page).toHaveURL(`/app/reader/${workId}/${chapterIds[0]}`);
  await expect(page.getByText('Một đoạn văn bản riêng tư.')).toBeVisible();
  await expectNoAxeViolations(page);
});

test('account Reader opens the annotation composer from a real mouse text selection', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false);
  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByText('Một đoạn văn bản riêng tư.')).toBeVisible();

  await selectReaderTextWithMouse(page, 'ch1-p1', 'đoạn văn');
  await expect
    .poll(() => page.evaluate(() => window.getSelection()?.toString() ?? ''))
    .toBe('đoạn văn');
  await page.getByRole('button', { name: 'Mở ghi chú và đoạn đánh dấu' }).click();

  const panel = page.getByRole('dialog', { name: 'Ghi chú & đoạn đánh dấu' });
  await expect(panel.locator('blockquote')).toHaveText('đoạn văn');
});

test('account Reader consumes a keyboard-style Selection API range on Enter', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false);
  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByText('Một đoạn văn bản riêng tư.')).toBeVisible();

  const selectedQuote = await page
    .locator('[data-source-anchor="ch1-p1"]')
    .evaluate((paragraph) => {
      const walker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT);
      let textNode = walker.nextNode() as Text | null;
      while (textNode && !textNode.data.startsWith('Một')) {
        textNode = walker.nextNode() as Text | null;
      }
      if (!textNode) throw new Error('Keyboard selection fixture is missing its source text');
      const selection = window.getSelection();
      selection?.removeAllRanges();
      selection?.collapse(textNode, 0);
      for (let index = 0; index < 'Một'.length; index += 1) {
        selection?.modify('extend', 'forward', 'character');
      }
      return selection?.toString() ?? '';
    });
  expect(selectedQuote).toBe('Một');

  const openButton = page.getByRole('button', { name: 'Mở ghi chú và đoạn đánh dấu' });
  await openButton.focus();
  await expect(openButton).toBeFocused();
  await page.keyboard.press('Enter');
  const panel = page.getByRole('dialog', { name: 'Ghi chú & đoạn đánh dấu' });
  await expect(panel.locator('blockquote')).toHaveText('Một');
});

test('account Reader discards a cached text selection after chapter navigation', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false);
  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByText('Một đoạn văn bản riêng tư.')).toBeVisible();

  await page.locator('[data-source-anchor="ch1-p1"]').evaluate((paragraph) => {
    const walker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT);
    const textNode = walker.nextNode() as Text | null;
    if (!textNode) throw new Error('Keyboard selection fixture is missing its source text');
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.collapse(textNode, 0);
    for (let index = 0; index < 'Một'.length; index += 1) {
      selection?.modify('extend', 'forward', 'character');
    }
  });
  await expect.poll(() => page.evaluate(() => window.getSelection()?.toString() ?? '')).toBe('Một');

  const tocOpener = page.getByRole('button', { name: 'Mở mục lục' });
  await tocOpener.focus();
  await page.keyboard.press('Enter');
  const toc = page.getByRole('dialog', { name: 'Gió qua núi' });
  const targetChapter = toc.getByRole('button', { name: /Qua khỏi dốc/ });
  await targetChapter.focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterIds[2]}$`));
  await expect(page.getByRole('heading', { name: 'Qua khỏi dốc' })).toBeVisible();

  const annotationOpener = page.getByRole('button', { name: 'Mở ghi chú và đoạn đánh dấu' });
  await annotationOpener.focus();
  await expect(annotationOpener).toBeFocused();
  await page.keyboard.press('Enter');
  const panel = page.getByRole('dialog', { name: 'Ghi chú & đoạn đánh dấu' });
  await expect(panel.getByRole('heading', { name: 'Chưa có ghi chú nào' })).toBeVisible();
  await expect(panel.locator('blockquote')).toHaveCount(0);
});

for (const motion of [
  { name: 'reduced motion', preference: 'reduce' as const, expectedBehavior: 'auto' },
  { name: 'standard motion', preference: 'no-preference' as const, expectedBehavior: 'smooth' },
]) {
  test(`account Reader uses ${motion.expectedBehavior} scrolling for saved annotations with ${motion.name}`, async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: motion.preference });
    await page.addInitScript(() => {
      const captureWindow = window as Window & {
        __readerScrollOptions: Array<boolean | ScrollIntoViewOptions>;
      };
      captureWindow.__readerScrollOptions = [];
      HTMLElement.prototype.scrollIntoView = function (options?: boolean | ScrollIntoViewOptions) {
        captureWindow.__readerScrollOptions.push(options ?? {});
      };
    });
    const annotation: MockReaderAnnotation = {
      id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      workId,
      chapterId: chapterIds[0],
      chapterRevisionId: revisionId,
      startAnchor: 'ch1-p1',
      startOffset: 0,
      endAnchor: 'ch1-p1',
      endOffset: 8,
      quoteSnapshot: 'Một đoạn',
      note: 'Ghi chú riêng tư',
      color: 'amber',
      version: 1,
      createdAt: '2026-11-14T10:00:00Z',
      updatedAt: '2026-11-14T10:00:00Z',
      chapterOrdinal: 1,
      chapterTitle: 'Cánh cửa mở',
      anchorState: 'CURRENT',
    };
    await installReaderRoutes(
      page,
      200,
      false,
      200,
      false,
      undefined,
      undefined,
      false,
      [],
      [annotation],
    );
    await page.goto(`/app/reader/${workId}`);
    await page.getByRole('button', { name: 'Mở ghi chú và đoạn đánh dấu' }).click();
    const panel = page.getByRole('dialog', { name: 'Ghi chú & đoạn đánh dấu' });
    await expect(panel.getByRole('button', { name: /Chương 1/ })).toBeVisible();
    await panel.getByRole('button', { name: /Chương 1/ }).click();

    const options = await page.evaluate(() =>
      (
        window as Window & {
          __readerScrollOptions: Array<boolean | ScrollIntoViewOptions>;
        }
      ).__readerScrollOptions.at(-1),
    );
    expect(options).toMatchObject({ block: 'center', behavior: motion.expectedBehavior });
  });
}

test('account Reader saves a Unicode source highlight and synchronizes private notes', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installReaderRoutes(page, 200, false);
  await page.goto(`/app/reader/${workId}`);
  await expect(page.getByText('Một đoạn văn bản riêng tư.')).toBeVisible();

  await page.locator('[data-source-anchor="ch1-p1"]').evaluate((paragraph) => {
    const walker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT);
    const textNode = walker.nextNode() as Text;
    const start = textNode.data.indexOf('đoạn văn');
    const range = document.createRange();
    range.setStart(textNode, start);
    range.setEnd(textNode, start + 'đoạn văn'.length);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  });

  await page.getByRole('button', { name: 'Mở ghi chú và đoạn đánh dấu' }).click();
  const panel = page.getByRole('dialog', { name: 'Ghi chú & đoạn đánh dấu' });
  await expect(panel.getByText('đoạn văn', { exact: true })).toBeVisible();
  await panel.getByLabel('Ghi chú riêng tư (không bắt buộc)').fill('Nhớ chi tiết này');
  await panel.getByRole('button', { name: 'Xanh trời' }).click();
  await panel.getByRole('button', { name: 'Lưu ghi chú và đoạn đánh dấu' }).click();

  const highlight = page.locator('mark.reader-account-highlight[data-highlight-color="sky"]');
  await expect(highlight).toHaveText('đoạn văn');
  const savedNote = panel.locator('textarea').last();
  await expect(savedNote).toHaveValue('Nhớ chi tiết này');
  await savedNote.fill('Đã sửa trên thiết bị hiện tại');
  await expect(panel.getByRole('status').filter({ hasText: 'Đã lưu' })).toBeVisible({
    timeout: 5000,
  });
  await expectNoAxeViolations(page);

  page.once('dialog', (dialog) => dialog.accept());
  await panel.getByRole('button', { name: 'Xóa ghi chú này' }).click();
  await expect(highlight).toHaveCount(0);
  await expect(panel.getByText('Chưa có ghi chú nào')).toBeVisible();
  await expectNoAxeViolations(page);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
});

async function verifyEffectiveZoom(browser: Browser): Promise<void> {
  const context = await browser.newContext({
    viewport: { width: 180, height: 400 },
    deviceScaleFactor: 2,
  });
  try {
    const page = await context.newPage();
    const pageErrors: string[] = [];
    page.on('pageerror', (error) => pageErrors.push(error.message));
    await installReaderRoutes(page);
    await page.goto(`/app/reader/${workId}`);
    await expect(page.getByRole('heading', { name: 'Cánh cửa mở' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Chương tiếp' })).toBeVisible();
    await page.getByRole('button', { name: 'Mở mục lục' }).click();
    await expect(page.getByRole('dialog', { name: 'Gió qua núi' })).toBeVisible();
    await page.screenshot({
      path: test.info().outputPath('account-reader-effective-200-percent.png'),
      fullPage: false,
    });
    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
      dpr: window.devicePixelRatio,
    }));
    expect(dimensions.dpr).toBe(2);
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    await expectNoAxeViolations(page);
    expect(pageErrors).toEqual([]);
  } finally {
    await context.close();
  }
}
