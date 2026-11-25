import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const jobId = '66666666-6666-4666-8666-666666666666';
const workId = '33333333-3333-4333-8333-333333333333';
const chapterId = '77777777-7777-4777-8777-777777777777';
const revisionId = '88888888-8888-4888-8888-888888888888';
const candidateId = '99999999-9999-4999-8999-999999999999';
const cursor = `${'A'.repeat(60)}.${'B'.repeat(43)}`;

const currentUser = {
  user: {
    id: '11111111-1111-4111-8111-111111111111',
    displayName: 'Biên tập viên',
    contactEmail: 'writer@example.test',
  },
  policy: {
    termsAcceptedVersion: 'terms-1',
    privacyNoticeAcknowledgedVersion: 'privacy-1',
    requiredAcceptanceComplete: true,
  },
};

function job(id = jobId, overrides: Record<string, unknown> = {}) {
  return {
    job: {
      id,
      workId,
      chapterId,
      sourceRevisionId: revisionId,
      sourceRevisionCurrent: true,
      workTitle: 'Thiên ngoại chi thư',
      chapterTitle: 'Chương mở đầu',
      chapterOrdinal: 1,
      canManage: true,
      sourceLanguage: 'vi',
      targetLanguage: 'en',
      providerId: 'fake-local',
      status: 'review_required',
      totalUnits: 3,
      completedUnits: 2,
      failedUnits: 0,
      queuedUnits: 0,
      runningUnits: 0,
      retryWaitUnits: 0,
      cancelledUnits: 0,
      failureCode: null,
      candidateId,
      candidateStatus: 'ready_for_review',
      createdAt: '2026-11-14T09:00:00Z',
      updatedAt: '2026-11-14T10:30:00Z',
      startedAt: '2026-11-14T09:01:00Z',
      completedAt: '2026-11-14T10:30:00Z',
      cancelRequestedAt: null,
      ...overrides,
    },
    terminalUnits: 2,
    progressPercent: 66,
  };
}

async function json(
  route: Route,
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers,
    body: JSON.stringify(body),
  });
}

async function installDeskRoutes(
  page: Page,
  listHandler: (route: Route) => Promise<void>,
  fakeEnabled = true,
): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '6.28.0',
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
        translationFakePreviewEnabled: fakeEnabled,
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
  await page.route('**/api/v1/translation/jobs**', listHandler);
}

test('shows writer job metadata, disclosure, and responsive accessible controls', async ({
  browser,
}, testInfo) => {
  const context = await browser.newContext({
    viewport: { width: 360, height: 800 },
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  const requests: string[] = [];
  try {
    await installDeskRoutes(page, async (route) => {
      requests.push(route.request().url());
      await json(route, { items: [job()], nextCursor: null }, 200, {
        'Cache-Control': 'no-store',
        Pragma: 'no-cache',
      });
    });
    await page.goto('/app/translation');
    await expect(page.getByRole('heading', { name: 'Bàn dịch' })).toBeVisible();
    await expect(page.getByRole('heading', { name: /Chương 1 · Chương mở đầu/ })).toBeVisible();
    await expect(page.getByText('Bản thử cục bộ · kết quả giả.')).toBeVisible();
    await expect(page.getByText('Candidate giả đã sẵn sàng')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Đối chiếu bản giả' })).toHaveAttribute(
      'href',
      `/app/translation/jobs/${jobId}/candidate`,
    );
    await expect(page.locator('.mobile-nav').getByRole('link', { name: 'Bàn dịch' })).toBeVisible();
    expect(requests).toHaveLength(2);
    expect(requests.map((url) => new URL(url).searchParams.get('limit')).sort()).toEqual([
      '1',
      '25',
    ]);
    expect(requests.every((url) => !new URL(url).searchParams.has('cursor'))).toBe(true);
    await expectNoAxeViolations(page);

    const measurements: Array<Record<string, number | string>> = [];
    for (const viewport of [
      { name: 'mobile-360x800', width: 360, height: 800 },
      { name: 'landscape-640x360', width: 640, height: 360 },
      { name: 'tablet-768x1024', width: 768, height: 1024 },
      { name: 'desktop-1280x900', width: 1280, height: 900 },
      { name: 'effective-zoom-200-180x400', width: 180, height: 400 },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      const size = await page.evaluate(() => ({
        viewport: document.documentElement.clientWidth,
        document: document.documentElement.scrollWidth,
        dpr: window.devicePixelRatio,
      }));
      expect(size.document, `${viewport.name} horizontal overflow`).toBeLessThanOrEqual(
        size.viewport,
      );
      expect(size.dpr).toBe(2);
      measurements.push({ ...viewport, ...size });

      if (viewport.name === 'mobile-360x800' || viewport.name === 'effective-zoom-200-180x400') {
        const action = page.getByRole('link', { name: 'Mở trạng thái job' });
        await action.scrollIntoViewIfNeeded();
        const [actionBox, navigationBox] = await Promise.all([
          action.boundingBox(),
          page.locator('.mobile-nav').boundingBox(),
        ]);
        expect(actionBox).not.toBeNull();
        expect(navigationBox).not.toBeNull();
        expect(actionBox!.y + actionBox!.height).toBeLessThanOrEqual(navigationBox!.y);
        await expectNoAxeViolations(page);
        await page.screenshot({
          path: testInfo.outputPath(`translation-desk-${viewport.name}.png`),
        });
      }
    }
    expect(measurements).toHaveLength(5);
    await testInfo.attach('translation-desk-layout-measurements.json', {
      body: Buffer.from(JSON.stringify(measurements, null, 2)),
      contentType: 'application/json',
    });
  } finally {
    await context.close();
  }
});

test('preserves the first page after a later-page failure and appends after retry', async ({
  page,
}) => {
  let cursorRequests = 0;
  const secondJobId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  await installDeskRoutes(page, async (route) => {
    const requestUrl = new URL(route.request().url());
    if (requestUrl.searchParams.get('limit') === '1') {
      await json(route, { items: [job()], nextCursor: null }, 200, {
        'Cache-Control': 'no-store',
      });
      return;
    }
    if (!requestUrl.searchParams.has('cursor')) {
      const firstPage = Array.from({ length: 25 }, (_, index) =>
        index === 0
          ? job()
          : job(
              `${String(index + 1).padStart(8, '0')}-0000-4000-8000-${String(index).padStart(12, '0')}`,
              { chapterOrdinal: index + 1, chapterTitle: `Chương ${index + 1}` },
            ),
      );
      await json(route, { items: firstPage, nextCursor: cursor }, 200, {
        'Cache-Control': 'no-store',
      });
    } else if (requestUrl.searchParams.get('cursor') === cursor) {
      cursorRequests++;
      if (cursorRequests === 1) {
        await json(route, { code: 'TEMPORARY_FAILURE' }, 503, { 'Cache-Control': 'no-store' });
        return;
      }
      await json(
        route,
        {
          items: [job(secondJobId, { chapterOrdinal: 2, chapterTitle: 'Ngọn đèn xanh' })],
          nextCursor: null,
        },
        200,
        { 'Cache-Control': 'no-store' },
      );
    } else {
      await json(route, { code: 'TEMPORARY_FAILURE' }, 503, { 'Cache-Control': 'no-store' });
    }
  });
  await page.goto('/app/translation');
  await expect(page.locator('.translation-desk-card')).toHaveCount(25);
  await page.getByRole('button', { name: 'Tải thêm job' }).click();
  await expect(page.getByRole('alert')).toContainText('Dịch vụ chưa phản hồi');
  await expect(page.locator('.translation-desk-card')).toHaveCount(25);
  await page.getByRole('alert').getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.locator('.translation-desk-card')).toHaveCount(26);
  await expect(page.getByRole('heading', { name: /Ngọn đèn xanh/ })).toBeVisible();
  await expect(page.getByText(/Đã tải thêm 1 job/)).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expectNoAxeViolations(page);
});

test('shows an honest empty state and hides the feature link when fake-local is disabled', async ({
  page,
}) => {
  await installDeskRoutes(
    page,
    (route) => json(route, { items: [], nextCursor: null }, 200, { 'Cache-Control': 'no-store' }),
    false,
  );
  await page.goto('/app/translation');
  await expect(
    page.getByRole('heading', { name: 'Chưa có job nào bạn có thể quản lý' }),
  ).toBeVisible();
  await expect(page.locator('.sidebar').getByRole('link', { name: 'Bàn dịch' })).toHaveCount(0);
  await expect(page.locator('.mobile-nav').getByRole('link', { name: 'Bàn dịch' })).toHaveCount(0);
  await expectNoAxeViolations(page);
});

test('does not advertise a desk to an account with no manageable writer job', async ({ page }) => {
  await installDeskRoutes(page, (route) =>
    json(route, { items: [], nextCursor: null }, 200, { 'Cache-Control': 'no-store' }),
  );
  await page.goto('/app/translation');
  await expect(
    page.getByRole('heading', { name: 'Chưa có job nào bạn có thể quản lý' }),
  ).toBeVisible();
  await expect(page.locator('.sidebar').getByRole('link', { name: 'Bàn dịch' })).toHaveCount(0);
  await expect(page.locator('.mobile-nav').getByRole('link', { name: 'Bàn dịch' })).toHaveCount(0);
  await expectNoAxeViolations(page);
});
