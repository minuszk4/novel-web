import { expect, expectNoAxeViolations, test } from './support';

const glossaryId = '11111111-1111-4111-8111-111111111111';
const activeRevisionId = '22222222-2222-4222-8222-222222222222';
const latestRevisionId = '33333333-3333-4333-8333-333333333333';
const termId = '44444444-4444-4444-8444-444444444444';

test('writer can download the chosen revision and the detail page reflows accessibly', async ({
  browser,
}, testInfo) => {
  const context = await browser.newContext({
    baseURL: 'http://127.0.0.1:4300',
    viewport: { width: 360, height: 800 },
    deviceScaleFactor: 2,
    acceptDownloads: true,
  });
  const page = await context.newPage();
  const exportRequests: Array<{ format: string | null; revision: string | null }> = [];
  try {
    await installExportRoutes(page, {
      activeRevision: true,
      canWrite: true,
      onExport: (url) => {
        const parsed = new URL(url);
        exportRequests.push({
          format: parsed.searchParams.get('format'),
          revision: parsed.searchParams.get('revision'),
        });
      },
    });
    await page.goto(`/app/glossaries/${glossaryId}`);
    await expect(page.getByRole('heading', { name: 'Tu luyện' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Xuất glossary' })).toBeVisible();
    await expect(page.getByLabel('Phiên bản')).toHaveValue('active');
    await expect(page.getByLabel('Định dạng tệp')).toHaveValue('json');
    await expectNoAxeViolations(page);
    await page.screenshot({ path: testInfo.outputPath('glossary-export-mobile-360x800.png') });

    await page.getByLabel('Định dạng tệp').selectOption('csv');
    await expect(page.getByText(/dấu nháy đơn/)).toBeVisible();
    await page.getByLabel('Phiên bản').selectOption('latest');
    const downloadButton = page.getByRole('button', { name: 'Tải tệp xuống' });
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await expect(downloadButton).toBeInViewport();
    const [buttonBox, mobileNavBox] = await Promise.all([
      downloadButton.boundingBox(),
      page.locator('.mobile-nav').boundingBox(),
    ]);
    expect(buttonBox).not.toBeNull();
    expect(mobileNavBox).not.toBeNull();
    expect(buttonBox!.y + buttonBox!.height).toBeLessThanOrEqual(mobileNavBox!.y);
    await page.screenshot({
      path: testInfo.outputPath('glossary-export-mobile-action-visible.png'),
    });
    const downloadPromise = page.waitForEvent('download');
    await downloadButton.click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe('Tu_luyen-r2.csv');
    await expect(page.locator('.glossary-export-status')).toContainText('Đã tải xuống bản 2');
    expect(exportRequests).toEqual([{ format: 'csv', revision: 'latest' }]);

    const measurements: Array<Record<string, number | string>> = [];
    for (const viewport of [
      { name: 'tablet-768x1024', width: 768, height: 1024 },
      { name: 'desktop-1280x900', width: 1280, height: 900 },
      { name: 'effective-200-percent-180x400', width: 180, height: 400 },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      const size = await page.evaluate(() => ({
        viewport: document.documentElement.clientWidth,
        document: document.documentElement.scrollWidth,
        overflow: Array.from(document.querySelectorAll<HTMLElement>('*'))
          .filter((element) => element.scrollWidth > element.clientWidth + 2)
          .map((element) => ({
            tag: element.tagName,
            className: String(element.className),
            width: element.clientWidth,
            scrollWidth: element.scrollWidth,
          }))
          .slice(0, 8),
      }));
      expect(
        size.document,
        `${viewport.name} horizontal overflow: ${JSON.stringify(size.overflow)}`,
      ).toBeLessThanOrEqual(size.viewport);
      measurements.push({ ...viewport, ...size });
      await expectNoAxeViolations(page);
      await page.screenshot({ path: testInfo.outputPath(`glossary-export-${viewport.name}.png`) });
    }
    await testInfo.attach('glossary-export-layout-measurements.json', {
      body: Buffer.from(JSON.stringify(measurements, null, 2)),
      contentType: 'application/json',
    });
  } finally {
    await context.close();
  }
});

test('reader only sees the active revision export', async ({ page }) => {
  await installExportRoutes(page, { activeRevision: true, canWrite: false });
  await page.goto(`/app/glossaries/${glossaryId}`);

  await expect(page.getByRole('heading', { name: 'Xuất glossary' })).toBeVisible();
  await expect(page.getByText('Chỉ xem')).toBeVisible();
  await expect(page.getByLabel('Phiên bản')).toHaveValue('active');
  await expect(page.getByLabel('Phiên bản').locator('option')).toHaveCount(1);
  await expect(page.getByText('Bản nháp mới nhất')).toHaveCount(0);
  await expectNoAxeViolations(page);
});

test('writer with a first draft defaults to latest and does not load draft terms on page entry', async ({
  page,
}) => {
  const readPaths: string[] = [];
  await installExportRoutes(page, {
    activeRevision: false,
    canWrite: true,
    onRead: (path) => readPaths.push(path),
  });
  await page.goto(`/app/glossaries/${glossaryId}`);

  await expect(page.getByText('Chưa có bản thuật ngữ đang dùng')).toBeVisible();
  await expect(page.getByLabel('Phiên bản')).toHaveValue('latest');
  await expect(page.getByLabel('Phiên bản').locator('option')).toHaveCount(1);
  expect(readPaths).not.toContain(`/api/v1/glossaries/${glossaryId}/latest-revision`);
  await expectNoAxeViolations(page);
});

async function installExportRoutes(
  page: import('@playwright/test').Page,
  options: {
    activeRevision: boolean;
    canWrite: boolean;
    onRead?: (path: string) => void;
    onExport?: (url: string) => void;
  },
): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    route.fulfill({
      json: {
        apiVersion: '1.0.0',
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
          translationFakePreviewEnabled: false,
        },
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      json: {
        user: {
          id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
          displayName: 'Người đọc',
          contactEmail: 'reader@example.test',
        },
        policy: {
          termsAcceptedVersion: 'terms-current',
          privacyNoticeAcknowledgedVersion: 'privacy-current',
          requiredAcceptanceComplete: true,
        },
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/current', (route) =>
    route.fulfill({
      json: {
        terms: {
          version: 'terms-current',
          effectiveAt: '2026-11-01T00:00:00Z',
          documentUrl: '/api/v1/auth/policies/terms-current',
        },
        privacyNotice: {
          version: 'privacy-current',
          effectiveAt: '2026-11-01T00:00:00Z',
          documentUrl: '/api/v1/auth/policies/privacy-current',
        },
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/terms-current', (route) =>
    route.fulfill({ contentType: 'text/plain', body: 'Điều khoản.' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-current', (route) =>
    route.fulfill({ contentType: 'text/plain', body: 'Quyền riêng tư.' }),
  );
  await page.route('**/api/v1/glossaries/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    options.onRead?.(path);
    if (path === `/api/v1/glossaries/${glossaryId}` && request.method() === 'GET') {
      return route.fulfill({
        headers: { ETag: '"v3"', 'Cache-Control': 'no-store' },
        json: {
          id: glossaryId,
          name: 'Tu luyện',
          scope: 'USER_GLOBAL',
          sourceLanguage: 'zh-Hans',
          targetLanguage: 'vi',
          version: 3,
          activeRevisionId: options.activeRevision ? activeRevisionId : null,
          activeRevisionNumber: options.activeRevision ? 1 : null,
          latestRevisionNumber: options.canWrite ? (options.activeRevision ? 2 : 1) : null,
          createdAt: '2026-11-14T05:00:00Z',
          canWrite: options.canWrite,
        },
      });
    }
    if (
      path === `/api/v1/glossaries/${glossaryId}/active-revision` &&
      request.method() === 'GET' &&
      options.activeRevision
    ) {
      return route.fulfill({
        headers: { ETag: '"v3"', 'Cache-Control': 'no-store' },
        json: {
          glossaryId,
          name: 'Tu luyện',
          scope: 'USER_GLOBAL',
          sourceLanguage: 'zh-Hans',
          targetLanguage: 'vi',
          version: 3,
          activeRevisionId,
          revisionNumber: 1,
          parentRevisionId: null,
          revisionCreatedAt: '2026-11-14T05:00:00Z',
          terms: [
            {
              id: termId,
              ordinal: 1,
              sourceTerm: '金丹',
              targetTerm: 'Kim Đan',
              priority: 0,
              casePolicy: 'INSENSITIVE',
              matchMode: 'WHOLE_PHRASE',
              doNotTranslate: false,
              contextNote: null,
              aliases: ['Golden Core'],
            },
          ],
        },
      });
    }
    if (path === `/api/v1/glossaries/${glossaryId}/export` && request.method() === 'GET') {
      options.onExport?.(request.url());
      const format = new URL(request.url()).searchParams.get('format');
      const revision = new URL(request.url()).searchParams.get('revision');
      const extension = format === 'csv' ? 'csv' : format === 'tsv' ? 'tsv' : 'json';
      const contentType =
        extension === 'csv'
          ? 'text/csv;charset=UTF-8'
          : extension === 'tsv'
            ? 'text/tab-separated-values;charset=UTF-8'
            : 'application/json;charset=UTF-8';
      return route.fulfill({
        status: 200,
        contentType,
        headers: {
          'Content-Disposition': `attachment; filename="Tu_luyen-r${revision === 'latest' ? 2 : 1}.${extension}"`,
          ETag: '"v3"',
          'Cache-Control': 'no-store',
          'X-Content-Type-Options': 'nosniff',
        },
        body: 'sourceTerm,targetTerm\r\n"金丹","Kim Đan"\r\n',
      });
    }
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } });
  });
}
