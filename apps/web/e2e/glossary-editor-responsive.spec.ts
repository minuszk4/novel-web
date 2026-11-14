import { expect, expectNoAxeViolations, test } from './support';

const glossaryId = '11111111-1111-4111-8111-111111111111';
const activeRevisionId = '22222222-2222-4222-8222-222222222222';
const revisionId = '33333333-3333-4333-8333-333333333333';
const termId = '44444444-4444-4444-8444-444444444444';

test('glossary editor reflows and keeps save controls clear of mobile navigation', async ({
  browser,
}, testInfo) => {
  const context = await browser.newContext({
    baseURL: 'http://127.0.0.1:4300',
    viewport: { width: 1280, height: 900 },
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  try {
    await installRoutes(page);
    await page.goto(`/app/glossaries/${glossaryId}/edit`);
    await expect(page.getByRole('heading', { name: 'Tu luyện' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Lưu bản nháp' })).toBeDisabled();
    await page.getByLabel(/Văn bản mẫu/).fill('Golden Core appears.');
    await page.getByRole('button', { name: 'Xem trước bản đã lưu' }).click();
    await expect(page.getByText('Kết quả bản nháp 2')).toBeVisible();
    await expect(page.getByText('Golden Core → Kim Đan mới')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Lưu bản nháp' })).toBeDisabled();
    await expectNoAxeViolations(page);

    const measurements: Array<Record<string, number | string>> = [];
    for (const viewport of [
      { name: 'mobile-360x800', width: 360, height: 800, dpr: 2 },
      { name: 'tablet-768x1024', width: 768, height: 1024, dpr: 2 },
      { name: 'desktop-1280x900', width: 1280, height: 900, dpr: 2 },
      { name: 'zoom-200-percent-360x800', width: 180, height: 400, dpr: 2 },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      const size = await page.evaluate(() => ({
        viewport: document.documentElement.clientWidth,
        document: document.documentElement.scrollWidth,
        dpr: window.devicePixelRatio,
        overflow: Array.from(document.querySelectorAll<HTMLElement>('*'))
          .filter((element) => element.scrollWidth > element.clientWidth + 2)
          .map((element) => ({
            tag: element.tagName,
            className: String(element.className),
            width: element.clientWidth,
            scrollWidth: element.scrollWidth,
            left: Math.round(element.getBoundingClientRect().left),
          }))
          .slice(0, 8),
      }));
      expect(
        size.document,
        `${viewport.name} horizontal overflow: ${JSON.stringify(size.overflow)}`,
      ).toBeLessThanOrEqual(size.viewport);
      expect(size.dpr).toBe(viewport.dpr);
      measurements.push({ ...viewport, ...size });

      if (viewport.name === 'mobile-360x800' || viewport.name === 'zoom-200-percent-360x800') {
        const save = page.getByRole('button', { name: 'Lưu bản nháp' });
        const nav = page.locator('.mobile-nav');
        await page.evaluate(() => {
          document.documentElement.style.scrollBehavior = 'auto';
        });
        await save.scrollIntoViewIfNeeded();
        await expect(save).toBeInViewport();
        const [saveBox, navBox] = await Promise.all([save.boundingBox(), nav.boundingBox()]);
        expect(saveBox, `${viewport.name} save button must be visible`).not.toBeNull();
        expect(navBox, `${viewport.name} mobile navigation must be visible`).not.toBeNull();
        expect(
          saveBox!.y + saveBox!.height,
          `${viewport.name} save/nav overlap`,
        ).toBeLessThanOrEqual(navBox!.y);
        await expectNoAxeViolations(page);
        await page.screenshot({
          path: testInfo.outputPath(`glossary-editor-${viewport.name}.png`),
        });
      }
    }
    expect(measurements).toHaveLength(4);
    await testInfo.attach('glossary-editor-layout-measurements.json', {
      body: Buffer.from(JSON.stringify(measurements, null, 2)),
      contentType: 'application/json',
    });
  } finally {
    await context.close();
  }
});

async function installRoutes(page: import('@playwright/test').Page): Promise<void> {
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
  await page.route('**/api/v1/auth/csrf', (route) =>
    route.fulfill({ json: { token: 'A'.repeat(43) } }),
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
  await page.route('**/api/v1/glossaries/**', (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (
      path === `/api/v1/glossaries/${glossaryId}/revisions/${revisionId}/preview` &&
      request.method() === 'POST'
    ) {
      return route.fulfill({
        headers: { ETag: '"v4"', 'Cache-Control': 'no-store' },
        json: {
          glossaryId,
          baseRevisionId: activeRevisionId,
          proposedRevisionId: revisionId,
          glossaryVersion: 4,
          canPublish: true,
          changeCount: 1,
          changes: [
            {
              kind: 'UPDATED',
              termId,
              before: {
                id: termId,
                revisionId: activeRevisionId,
                sourceLanguage: 'zh-Hans',
                sourceTerm: '金丹',
                targetTerm: 'Kim Đan',
                aliases: ['Golden Core'],
                scope: 'USER_GLOBAL',
                priority: 2,
                casePolicy: 'INSENSITIVE',
                matchMode: 'WHOLE_PHRASE',
                doNotTranslate: false,
                ordinal: 1,
                contextNote: 'Tên cảnh giới',
              },
              after: {
                id: termId,
                revisionId,
                sourceLanguage: 'zh-Hans',
                sourceTerm: '金丹',
                targetTerm: 'Kim Đan mới',
                aliases: ['Golden Core'],
                scope: 'USER_GLOBAL',
                priority: 2,
                casePolicy: 'INSENSITIVE',
                matchMode: 'WHOLE_PHRASE',
                doNotTranslate: false,
                ordinal: 1,
                contextNote: 'Tên cảnh giới đã sửa',
              },
            },
          ],
          changesTruncated: false,
          errorCount: 0,
          warningCount: 0,
          lintIssues: [],
          lintIssuesTruncated: false,
          matchCount: 1,
          matches: [
            {
              termId,
              revisionId,
              matchedText: 'Golden Core',
              targetTerm: 'Kim Đan mới',
              startUtf16: 0,
              endUtf16: 11,
              alias: true,
              doNotTranslate: false,
              scope: 'USER_GLOBAL',
              priority: 2,
            },
          ],
          matchesTruncated: false,
          conflictCount: 0,
          conflicts: [],
          conflictsTruncated: false,
        },
      });
    }
    if (path === `/api/v1/glossaries/${glossaryId}` && request.method() === 'GET') {
      return route.fulfill({
        headers: { ETag: '"v4"', 'Cache-Control': 'no-store' },
        json: {
          id: glossaryId,
          name: 'Tu luyện',
          scope: 'USER_GLOBAL',
          sourceLanguage: 'zh-Hans',
          targetLanguage: 'vi',
          version: 4,
          activeRevisionId,
          activeRevisionNumber: 1,
          latestRevisionNumber: 2,
          createdAt: '2026-11-14T05:00:00Z',
          canWrite: true,
        },
      });
    }
    if (path === `/api/v1/glossaries/${glossaryId}/latest-revision` && request.method() === 'GET') {
      return route.fulfill({
        headers: { ETag: '"v4"', 'Cache-Control': 'no-store' },
        json: {
          glossaryId,
          name: 'Tu luyện',
          scope: 'USER_GLOBAL',
          sourceLanguage: 'zh-Hans',
          targetLanguage: 'vi',
          version: 4,
          activeRevisionId,
          revisionId,
          revisionNumber: 2,
          parentRevisionId: activeRevisionId,
          revisionCreatedAt: '2026-11-14T05:00:00Z',
          terms: [
            {
              id: termId,
              ordinal: 1,
              sourceTerm: '金丹',
              targetTerm: 'Kim Đan',
              priority: 2,
              casePolicy: 'INSENSITIVE',
              matchMode: 'WHOLE_PHRASE',
              doNotTranslate: false,
              contextNote: 'Cảnh giới tu luyện',
              aliases: ['Golden Core'],
            },
          ],
        },
      });
    }
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } });
  });
}
