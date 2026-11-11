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

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installReaderRoutes(
  page: Page,
  manifestStatus: number | readonly number[] = 200,
  includeHostileMarkup = true,
  contentStatus: number | readonly number[] = 200,
  paginatedManifest = false,
): Promise<string[]> {
  const loadedChapters: string[] = [];
  const manifestStatuses =
    typeof manifestStatus === 'number' ? [manifestStatus] : [...manifestStatus];
  const contentStatuses = typeof contentStatus === 'number' ? [contentStatus] : [...contentStatus];
  let manifestRequestCount = 0;
  let firstChapterContentRequestCount = 0;
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '4.2.1',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        momoTestEnabled: false,
        momoEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) => json(route, currentUser));
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
        segments,
        nextSegmentCursor: null,
      });
    }
    return json(route, {
      chapterId,
      workId,
      sourceRevisionId: revisionId,
      sourceLanguage: 'vi',
      segments: [{ anchor: 'ch3-p1', type: 'paragraph', sourceText: 'Con đường đã sáng.' }],
      nextSegmentCursor: null,
    });
  });
  return loadedChapters;
}

const viewports = [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'tablet 768×1024', width: 768, height: 1024 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const;

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
    await expect(page.getByText('Tiến độ và dấu trang chưa đồng bộ')).toBeVisible();
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
    await expect(page.getByRole('link', { name: 'Quay lại thư viện tài khoản' })).toBeVisible();
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
