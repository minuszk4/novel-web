import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const jobId = '66666666-6666-4666-8666-666666666666';
const workId = '33333333-3333-4333-8333-333333333333';
const chapterId = '77777777-7777-4777-8777-777777777777';
const revisionId = '88888888-8888-4888-8888-888888888888';
const candidateId = '99999999-9999-4999-8999-999999999999';
const sourceSegmentId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const sourceText = 'Nguyên tác chỉ hiển thị để đối chiếu.';
const savedText = 'Bản biên tập riêng tư của tác giả.';

function candidate(editedText: string | null = null, editRevision = 0) {
  return {
    jobId,
    candidateId,
    sourceRevisionId: revisionId,
    sourceRevisionCurrent: true,
    sourceLanguage: 'vi',
    targetLanguage: 'en',
    providerId: 'fake-local',
    candidateStatus: 'ready_for_review',
    pageOffset: 0,
    pageLimit: 10,
    totalSegments: 1,
    segments: [
      {
        sourceSegmentId,
        ordinal: 1,
        sourceType: 'paragraph',
        sourceText,
        fakeResultText: `[fake vi->en] ${sourceText}`,
        editedText,
        editRevision,
        editEtag: `"edit-${editRevision}"`,
      },
    ],
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

async function installSession(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '6.29.0',
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
        translationFakePreviewEnabled: true,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
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
    }),
  );
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
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route('**/api/v1/translation/jobs**', (route) =>
    json(route, { items: [], nextCursor: null }, 200, { 'Cache-Control': 'no-store' }),
  );
}

test('edits one fake-local segment explicitly with accessible responsive layout', async ({
  browser,
}, testInfo) => {
  const context = await browser.newContext({
    viewport: { width: 360, height: 800 },
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  let currentCandidate = candidate();
  const writes: Array<{ body: unknown; headers: Record<string, string> }> = [];
  try {
    await installSession(page);
    await page.route(`**/api/v1/translation/jobs/${jobId}/candidate?**`, (route) =>
      json(route, currentCandidate, 200, { 'Cache-Control': 'no-store', Pragma: 'no-cache' }),
    );
    await page.route(
      `**/api/v1/translation/jobs/${jobId}/candidate/segments/${sourceSegmentId}/edit`,
      async (route) => {
        writes.push({ body: route.request().postDataJSON(), headers: route.request().headers() });
        currentCandidate = candidate(savedText, 1);
        await json(
          route,
          {
            jobId,
            candidateId,
            sourceSegmentId,
            editRevision: 1,
            translatedText: savedText,
            createdAt: '2026-11-15T10:00:00Z',
            replayed: false,
          },
          201,
          { 'Cache-Control': 'no-store', ETag: '"edit-1"' },
        );
      },
    );

    await page.goto(`/app/translation/jobs/${jobId}/candidate`);
    await expect(page.getByRole('heading', { name: 'Đối chiếu candidate' })).toBeVisible();
    await expect(page.getByText(sourceText, { exact: true })).toBeVisible();
    await expect(page.getByText(`[fake vi->en] ${sourceText}`, { exact: true })).toBeVisible();
    await expect(page.getByText('Chưa có chỉnh sửa riêng được lưu')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sửa đoạn này' })).toBeVisible();

    const measurements: Array<Record<string, number | string>> = [];
    for (const viewport of [
      { name: 'mobile-360x800', width: 360, height: 800 },
      { name: 'landscape-640x360', width: 640, height: 360 },
      { name: 'tablet-768x1024', width: 768, height: 1024 },
      { name: 'desktop-1280x900', width: 1280, height: 900 },
      { name: 'effective-zoom-200-180x400', width: 180, height: 400 },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      const width = await page.evaluate(() => ({
        viewport: document.documentElement.clientWidth,
        document: document.documentElement.scrollWidth,
        dpr: window.devicePixelRatio,
      }));
      expect(width.document, `${viewport.name} horizontal overflow`).toBeLessThanOrEqual(
        width.viewport,
      );
      expect(width.dpr).toBe(2);
      await expectNoAxeViolations(page);
      measurements.push({ ...viewport, ...width });
    }
    await testInfo.attach('translation-candidate-edit-layout.json', {
      body: Buffer.from(JSON.stringify(measurements, null, 2)),
      contentType: 'application/json',
    });

    await page.setViewportSize({ width: 360, height: 800 });
    await page.getByRole('button', { name: 'Sửa đoạn này' }).click();
    const editor = page.getByRole('textbox', { name: 'Bản nháp chỉnh sửa cho phần 1' });
    await expect(editor).toBeFocused();
    await editor.fill(savedText);
    await expect(page.getByText(/byte UTF-8/)).toBeVisible();
    expect(writes).toHaveLength(0);
    await expectNoAxeViolations(page);

    const saveButton = page.getByRole('button', { name: 'Lưu chỉnh sửa' });
    await page.keyboard.press('Tab');
    await expect(saveButton).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByText(savedText, { exact: true })).toBeVisible();
    await expect(page.getByText('Bản 1 đã lưu · chưa xuất bản')).toBeVisible();
    expect(writes).toHaveLength(1);
    expect(writes[0].body).toEqual({ translatedText: savedText });
    expect(writes[0].headers['x-csrf-token']).toBe(csrfToken);
    expect(writes[0].headers['if-match']).toBe('"edit-0"');
    expect(writes[0].headers['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
    await expect(page.getByText(sourceText, { exact: true })).toBeVisible();
    await expect(page.getByText(`[fake vi->en] ${sourceText}`, { exact: true })).toBeVisible();
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: testInfo.outputPath('translation-candidate-edit-mobile.png'),
      fullPage: true,
    });
  } finally {
    await context.close();
  }
});
