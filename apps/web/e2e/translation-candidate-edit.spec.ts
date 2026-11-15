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

test('resolves a stale segment edit without losing the draft at narrow widths', async ({
  browser,
}, testInfo) => {
  const context = await browser.newContext({
    viewport: { width: 360, height: 800 },
    deviceScaleFactor: 2,
  });
  const page = await context.newPage();
  const localDraft = 'Bản nháp trên thiết bị này.';
  const serverEdit = 'Bản mới đã lưu ở nơi khác.';
  let latestCandidate = candidate();
  let previewReads = 0;
  const writes: Array<{ body: unknown; headers: Record<string, string> }> = [];
  try {
    await installSession(page);
    await page.route(`**/api/v1/translation/jobs/${jobId}/candidate?**`, async (route) => {
      previewReads++;
      await json(route, latestCandidate, 200, {
        'Cache-Control': 'no-store',
        Pragma: 'no-cache',
      });
    });
    await page.route(
      `**/api/v1/translation/jobs/${jobId}/candidate/segments/${sourceSegmentId}/edit`,
      async (route) => {
        const headers = route.request().headers();
        writes.push({ body: route.request().postDataJSON(), headers });
        if (writes.length === 1) {
          latestCandidate = candidate(serverEdit, 1);
          await json(route, { code: 'TRANSLATION_EDIT_STALE', currentEtag: '"edit-1"' }, 412, {
            'Cache-Control': 'no-store',
            ETag: '"edit-1"',
          });
          return;
        }
        latestCandidate = candidate(localDraft, 2);
        await json(
          route,
          {
            jobId,
            candidateId,
            sourceSegmentId,
            editRevision: 2,
            translatedText: localDraft,
            createdAt: '2026-11-16T10:00:00Z',
            replayed: false,
          },
          201,
          { 'Cache-Control': 'no-store', ETag: '"edit-2"' },
        );
      },
    );

    await page.goto(`/app/translation/jobs/${jobId}/candidate`);
    await expect(page.getByRole('heading', { name: 'Đối chiếu candidate' })).toBeVisible();
    await page.getByRole('button', { name: 'Sửa đoạn này' }).click();
    const editor = page.getByRole('textbox', { name: 'Bản nháp chỉnh sửa cho phần 1' });
    await editor.fill(localDraft);
    await expectNoAxeViolations(page);
    const beforeConflictPath = testInfo.outputPath('translation-edit-conflict-before.png');
    await page.screenshot({ path: beforeConflictPath });

    await page.getByRole('button', { name: 'Lưu chỉnh sửa' }).click();
    await expect(page.getByRole('alert')).toContainText('Đoạn này đã được sửa ở nơi khác');
    await expect(editor).toHaveValue(localDraft);
    const conflictActions = page.getByRole('group', { name: 'Xử lý xung đột chỉnh sửa' });
    await expect(conflictActions).toBeFocused();
    await expect(page.getByText(serverEdit, { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Giữ bản nháp của tôi' })).toBeVisible();
    await expectNoAxeViolations(page);
    const conflictPath = testInfo.outputPath('translation-edit-conflict-mobile-360.png');
    await page.screenshot({ path: conflictPath });

    const layoutChecks: Array<Record<string, number | string>> = [];
    for (const viewport of [
      { name: 'mobile-360x800', width: 360, height: 800 },
      { name: 'effective-zoom-200-180x400', width: 180, height: 400 },
    ]) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await expectNoAxeViolations(page);
      const pageWidth = await page.evaluate(() => ({
        viewport: document.documentElement.clientWidth,
        document: document.documentElement.scrollWidth,
        dpr: window.devicePixelRatio,
      }));
      expect(pageWidth.document, `${viewport.name} horizontal overflow`).toBeLessThanOrEqual(
        pageWidth.viewport,
      );
      expect(pageWidth.dpr).toBe(2);
      const editButtons = page.locator('.translation-candidate-edit').getByRole('button');
      for (const button of await editButtons.all()) {
        const buttonBox = await button.boundingBox();
        expect(buttonBox).not.toBeNull();
        expect(buttonBox!.width, `${viewport.name} action width`).toBeGreaterThanOrEqual(44);
        expect(buttonBox!.height, `${viewport.name} action height`).toBeGreaterThanOrEqual(44);
      }
      layoutChecks.push({ ...viewport, ...pageWidth });
      await conflictActions.focus();
      await page.keyboard.press('Tab');
      const refreshButton = page.getByRole('button', { name: 'Tải bản mới nhất' });
      await expect(refreshButton).toBeFocused();
      const mobileNav = page.locator('.mobile-nav');
      await expect
        .poll(async () => {
          const [buttonBox, mobileNavBox] = await Promise.all([
            refreshButton.boundingBox(),
            mobileNav.boundingBox(),
          ]);
          return !!buttonBox && !!mobileNavBox && buttonBox.y + buttonBox.height <= mobileNavBox.y;
        })
        .toBe(true);
      if (viewport.width === 180) {
        const narrowPath = testInfo.outputPath('translation-edit-conflict-effective-200.png');
        await page.screenshot({ path: narrowPath });
      }
      await page.keyboard.press('Tab');
      await expect(page.getByRole('button', { name: 'Dùng bản đã lưu mới' })).toBeFocused();
      await page.keyboard.press('Tab');
      await expect(page.getByRole('button', { name: 'Giữ bản nháp của tôi' })).toBeFocused();
      await page.keyboard.press('Tab');
      const cancelButton = page.getByRole('button', { name: 'Hủy' });
      await expect(cancelButton).toBeFocused();
      if (viewport.width === 180) {
        await expect
          .poll(async () => {
            const [buttonBox, mobileNavBox] = await Promise.all([
              cancelButton.boundingBox(),
              mobileNav.boundingBox(),
            ]);
            return (
              !!buttonBox && !!mobileNavBox && buttonBox.y + buttonBox.height <= mobileNavBox.y
            );
          })
          .toBe(true);
      }
    }
    await testInfo.attach('translation-edit-conflict-layout.json', {
      body: Buffer.from(JSON.stringify(layoutChecks, null, 2)),
      contentType: 'application/json',
    });

    await page.setViewportSize({ width: 360, height: 800 });
    await page.getByRole('button', { name: 'Tải bản mới nhất' }).click();
    await expect(
      page.getByText('Đã tải phiên bản mới nhất. Chọn dùng bản máy chủ hoặc giữ bản nháp của bạn.'),
    ).toBeVisible();
    expect(previewReads).toBe(3);
    await page.getByRole('button', { name: 'Giữ bản nháp của tôi' }).click();
    await expect(editor).toHaveValue(localDraft);
    const saveButton = page.getByRole('button', { name: 'Lưu chỉnh sửa' });
    await expect(saveButton).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByText('Bản 2 đã lưu · chưa xuất bản')).toBeVisible();
    expect(writes).toHaveLength(2);
    expect(writes[0].headers['if-match']).toBe('"edit-0"');
    expect(writes[1].headers['if-match']).toBe('"edit-1"');
    expect(writes[1].headers['idempotency-key']).not.toBe(writes[0].headers['idempotency-key']);
    await expect(page.getByText('Đây không phải bản dịch.', { exact: false })).toBeVisible();
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: testInfo.outputPath('translation-edit-conflict-resolved.png'),
    });
  } finally {
    await context.close();
  }
});
