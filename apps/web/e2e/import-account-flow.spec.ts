import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const libraryId = '33333333-3333-4333-8333-333333333333';
const importId = '44444444-4444-4444-8444-444444444444';
const rightsReceiptId = '55555555-5555-4555-8555-555555555555';
const workId = '66666666-6666-4666-8666-666666666666';
const chapterIds = ['77777777-7777-4777-8777-777777777777', '88888888-8888-4888-8888-888888888888'];
const firstEtag = `"${'a'.repeat(43)}"`;
const secondEtag = `"${'b'.repeat(43)}"`;
const rightsCopy = 'Tôi xác nhận có quyền xử lý tệp trong thư viện cá nhân.';

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

const library = {
  id: libraryId,
  name: 'Thư viện riêng',
  visibility: 'private',
  isDefault: true,
  createdAt: '2026-11-10T10:00:00Z',
};

async function json(
  route: Route,
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers,
    body: JSON.stringify(body),
  });
}

function preview(
  title = 'Sơn Hà',
  chapterOrder = chapterIds,
  singleChapter = false,
  singleChapterWarningCode = 'TXT_SINGLE_CHAPTER',
  chapterNames = ['Khởi đầu', 'Vào núi'],
  warningCodes = singleChapter ? [singleChapterWarningCode] : [],
) {
  const chapterTitles = new Map(
    chapterIds.map((id, index) => [id, chapterNames[index] ?? 'Chương']),
  );
  return {
    importId,
    version: title === 'Sơn Hà' ? 1 : 2,
    title,
    author: 'Minh An',
    chapterCount: singleChapter ? 1 : 2,
    language: 'vi',
    chapters: singleChapter
      ? [{ id: chapterIds[0], ordinal: 1, title: 'Nội dung' }]
      : chapterOrder.map((id, index) => ({
          id,
          ordinal: index + 1,
          title: chapterTitles.get(id) ?? 'Chương',
        })),
    nextCursor: null,
    warnings: warningCodes.map((code) => ({ code, message: 'Markdown structure was normalized.' })),
  };
}

async function installAccountRoutes(
  page: Page,
  options: {
    rightsStatus?: number;
    libraryFailures?: number;
    rightsVersionChangeOnRetry?: boolean;
    parseFailure?: boolean;
    singleChapter?: boolean;
    singleChapterWarningCode?: string;
    chapterNames?: string[];
    warningCodes?: string[];
  } = {},
): Promise<{
  events: string[];
  createBodies: Record<string, unknown>[];
  patchBodies: Record<string, unknown>[];
}> {
  const events: string[] = [];
  const createBodies: Record<string, unknown>[] = [];
  const patchBodies: Record<string, unknown>[] = [];
  let rightsReads = 0;

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
        importProcessingEnabled: true,
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
    route.fulfill({ status: 200, contentType: 'text/markdown; charset=UTF-8', body: '# Terms' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown; charset=UTF-8', body: '# Privacy' }),
  );
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, { token: 'session-import-csrf-token' }),
  );
  await page.route('**/api/v1/imports/rights-statement/current', (route) => {
    events.push('rights');
    rightsReads += 1;
    return options.rightsStatus
      ? json(route, { code: 'IMPORT_RIGHTS_STATEMENT_NOT_CONFIGURED' }, options.rightsStatus)
      : json(route, {
          version:
            options.rightsVersionChangeOnRetry && rightsReads > 1
              ? 'private-import-v2'
              : 'private-import-v1',
          effectiveAt: '2026-11-01T00:00:00Z',
          displayText:
            options.rightsVersionChangeOnRetry && rightsReads > 1
              ? `${rightsCopy} Bản cập nhật.`
              : rightsCopy,
        });
  });
  let libraryFailures = options.libraryFailures ?? 0;
  await page.route('**/api/v1/libraries**', async (route) => {
    if (libraryFailures > 0) {
      libraryFailures -= 1;
      await json(route, { code: 'LIBRARY_UNAVAILABLE' }, 503);
      return;
    }
    await json(route, { items: [library], nextCursor: null });
  });
  await page.route('**/api/v1/imports/rights-statement/acceptance', async (route) => {
    events.push('accept');
    expect(route.request().headers()['x-csrf-token']).toBe('session-import-csrf-token');
    expect(route.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/i);
    expect(route.request().postDataJSON()).toEqual({
      statementVersion: 'private-import-v1',
      confirmed: true,
    });
    await json(
      route,
      {
        id: rightsReceiptId,
        statementVersion: 'private-import-v1',
        acceptedAt: '2026-11-10T10:00:00Z',
        statementSha256: 'c'.repeat(64),
      },
      200,
    );
  });
  await page.route('**/api/v1/imports', async (route) => {
    events.push('create');
    expect(route.request().headers()['x-csrf-token']).toBe('session-import-csrf-token');
    createBodies.push(route.request().postDataJSON() as Record<string, unknown>);
    await json(
      route,
      {
        job: {
          id: importId,
          status: 'upload_pending',
          createdAt: '2026-11-10T10:00:00Z',
          expiresAt: '2026-11-11T10:00:00Z',
        },
        uploadTarget: {
          method: 'PUT',
          url: `/api/v1/imports/${importId}/content`,
          headers: {
            'Content-Type': 'application/octet-stream',
            'X-Import-Upload-Token': `one-time-upload-token-${'x'.repeat(48)}`,
          },
          expiresAt: '2026-11-12T10:01:00Z',
        },
      },
      201,
    );
  });
  await page.route(`**/api/v1/imports/${importId}/content`, async (route) => {
    events.push('upload');
    expect(route.request().headers()['content-type']).toBe('application/octet-stream');
    expect(route.request().headers()['x-import-upload-token']).toContain('one-time-upload-token-');
    expect(route.request().headers()['x-csrf-token']).toBe('session-import-csrf-token');
    expect(route.request().postDataBuffer()?.length).toBeGreaterThan(0);
    await route.fulfill({ status: 204 });
  });
  await page.route(`**/api/v1/imports/${importId}/parse`, async (route) => {
    events.push('parse');
    expect(route.request().headers()['x-csrf-token']).toBe('session-import-csrf-token');
    if (options.parseFailure) {
      await json(route, { code: 'IMPORT_PARSE_FAILED', detail: 'Invalid EPUB test fixture' }, 422);
      return;
    }
    await json(
      route,
      {
        id: importId,
        status: 'queued',
        createdAt: '2026-11-10T10:00:00Z',
        expiresAt: '2026-11-11T10:00:00Z',
        warnings: [],
      },
      202,
    );
  });
  await page.route(`**/api/v1/imports/${importId}`, (route) =>
    json(route, {
      id: importId,
      status: 'ready_to_commit',
      createdAt: '2026-11-10T10:00:00Z',
      expiresAt: '2026-11-11T10:00:00Z',
      warnings: [],
    }),
  );
  await page.route(`**/api/v1/imports/${importId}/preview**`, async (route) => {
    if (route.request().method() === 'PATCH') {
      events.push('save');
      expect(route.request().headers()['x-csrf-token']).toBe('session-import-csrf-token');
      expect(route.request().headers()['if-match']).toBe(firstEtag);
      const body = route.request().postDataJSON() as Record<string, unknown>;
      patchBodies.push(body);
      await json(route, preview(String(body['title']), body['chapterOrder'] as string[]), 200, {
        ETag: secondEtag,
      });
      return;
    }
    const isUpdated = patchBodies.length > 0;
    const order = isUpdated ? (patchBodies.at(-1)?.['chapterOrder'] as string[]) : chapterIds;
    const title = isUpdated ? String(patchBodies.at(-1)?.['title']) : 'Sơn Hà';
    await json(
      route,
      preview(
        title,
        order,
        options.singleChapter,
        options.singleChapterWarningCode,
        options.chapterNames,
        options.warningCodes,
      ),
      200,
      { ETag: isUpdated ? secondEtag : firstEtag },
    );
  });
  await page.route(`**/api/v1/imports/${importId}/commit`, async (route) => {
    events.push('commit');
    expect(route.request().headers()['x-csrf-token']).toBe('session-import-csrf-token');
    expect(route.request().headers()['if-match']).toBe(secondEtag);
    expect(route.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/i);
    await json(
      route,
      {
        id: workId,
        libraryId,
        title: 'Sơn Hà - Bản chỉnh',
        author: 'Minh An',
        language: 'vi',
        visibility: 'private',
        chapterCount: 2,
        createdAt: '2026-11-10T10:10:00Z',
      },
      201,
    );
  });
  return { events, createBodies, patchBodies };
}

test('account import uploads only after rights confirmation, reviews, edits and commits privately', async ({
  page,
}, testInfo) => {
  const api = await installAccountRoutes(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await expect(page.getByRole('heading', { name: 'Mang câu chuyện của bạn về đây' })).toBeVisible();
  await expect(page.getByText(rightsCopy)).toBeVisible();

  await page.locator('#work-file').setInputFiles({
    name: 'son-ha.epub',
    mimeType: 'application/epub+zip',
    buffer: Buffer.from('private test epub bytes'),
  });
  const startButton = page.getByRole('button', { name: 'Tải lên và phân tích' });
  await expect(startButton).toBeDisabled();
  await expectNoAxeViolations(page);

  await page.locator('.account-rights-check').click();
  await expect(startButton).toBeEnabled();
  await page.screenshot({ path: testInfo.outputPath('01-import-rights-confirmed-360x800.png') });
  await startButton.click();
  await expect(page).toHaveURL(/\/app\/import\/review$/);
  await expect(page.getByRole('heading', { name: 'Kiểm tra metadata và mục lục' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Thứ tự chương sẽ lưu' })).toContainText('Khởi đầu');
  await expectNoAxeViolations(page);
  await page.screenshot({ path: testInfo.outputPath('02-import-review-360x800.png') });
  const browser = page.context().browser();
  if (!browser) throw new Error('Chromium browser context is unavailable for the zoom capture.');
  const zoomContext = await browser.newContext({
    baseURL: 'http://127.0.0.1:4300',
    viewport: { width: 180, height: 400 },
    deviceScaleFactor: 2,
  });
  const zoomPage = await zoomContext.newPage();
  const zoomPageErrors: string[] = [];
  zoomPage.on('pageerror', (error) => zoomPageErrors.push(error.message));
  try {
    await installAccountRoutes(zoomPage);
    await zoomPage.goto('/app/import');
    await expect(zoomPage.getByText(rightsCopy)).toBeVisible();
    await zoomPage.locator('#work-file').setInputFiles({
      name: 'son-ha.epub',
      mimeType: 'application/epub+zip',
      buffer: Buffer.from('private test epub bytes'),
    });
    await zoomPage.locator('.account-rights-check').click();
    await zoomPage.getByRole('button', { name: 'Tải lên và phân tích' }).click();
    await expect(zoomPage).toHaveURL(/\/app\/import\/review$/);
    await expect(
      zoomPage.getByRole('heading', { name: 'Kiểm tra metadata và mục lục' }),
    ).toBeVisible();
    await zoomPage.screenshot({
      path: testInfo.outputPath('03-import-review-200-percent-360x800.png'),
    });
    const zoomDimensions = await zoomPage.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
      devicePixelRatio: window.devicePixelRatio,
    }));
    expect(zoomDimensions.viewport).toBe(180);
    expect(zoomDimensions.devicePixelRatio).toBe(2);
    expect(zoomDimensions.document).toBeLessThanOrEqual(zoomDimensions.viewport);
    const zoomCommitButton = zoomPage.getByRole('button', { name: 'Thêm vào thư viện riêng' });
    await zoomPage.evaluate(() => {
      document.documentElement.style.scrollBehavior = 'auto';
    });
    await zoomCommitButton.evaluate((element: HTMLElement) =>
      element.scrollIntoView({ block: 'center' }),
    );
    await expect(zoomCommitButton).toBeVisible();
    const [zoomCommitBox, zoomNavBox] = await Promise.all([
      zoomCommitButton.boundingBox(),
      zoomPage.locator('.mobile-nav').boundingBox(),
    ]);
    expect(zoomCommitBox).not.toBeNull();
    expect(zoomNavBox).not.toBeNull();
    expect(zoomCommitBox!.y).toBeGreaterThanOrEqual(0);
    expect(zoomCommitBox!.y + zoomCommitBox!.height).toBeLessThanOrEqual(zoomNavBox!.y);
    await zoomPage.screenshot({
      path: testInfo.outputPath('03b-import-review-200-percent-action-360x800.png'),
    });
    expect(zoomPageErrors).toEqual([]);
  } finally {
    await zoomContext.close();
  }

  await page.getByLabel('Tên tác phẩm').fill('Sơn Hà - Bản chỉnh');
  await page.getByRole('button', { name: 'Chuyển chương 1 xuống' }).click();
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click();
  await expect(page.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Thêm vào thư viện riêng' })).toBeEnabled();
  expect(api.patchBodies).toHaveLength(1);
  expect(api.patchBodies[0]).toEqual({
    title: 'Sơn Hà - Bản chỉnh',
    author: 'Minh An',
    language: 'vi',
    chapterOrder: [chapterIds[1], chapterIds[0]],
  });
  expect(api.createBodies).toEqual([
    {
      libraryId,
      rightsAcceptanceId: rightsReceiptId,
      rightsStatementVersion: 'private-import-v1',
      rightsConfirmed: true,
      source: {
        kind: 'upload',
        filename: 'son-ha.epub',
        contentType: 'application/epub+zip',
        sizeBytes: 23,
      },
    },
  ]);

  await page.getByRole('button', { name: 'Thêm vào thư viện riêng' }).click();
  await expect(page.locator('.import-success')).toContainText('Đã thêm riêng tư vào thư viện');
  await page.screenshot({ path: testInfo.outputPath('04-import-committed-360x800.png') });
  await expect(page.getByRole('link', { name: 'Mở thư viện' })).toBeVisible();
  expect(api.events).toEqual(['rights', 'accept', 'create', 'upload', 'parse', 'save', 'commit']);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
});

test('mobile account import keeps keyboard focus visible from file selection through review', async ({
  page,
}, testInfo) => {
  await installAccountRoutes(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await expect(page.getByRole('heading', { name: 'Mang câu chuyện của bạn về đây' })).toBeFocused();
  await expect(page.getByText(rightsCopy)).toBeVisible();
  await expectNoAxeViolations(page);

  const fileInput = page.locator('#work-file');
  for (let tab = 0; tab < 30; tab += 1) {
    if (await fileInput.evaluate((element) => document.activeElement === element)) break;
    await page.keyboard.press('Tab');
  }
  await expect(fileInput).toBeFocused();
  await expect(page.locator('.upload-dropzone')).toHaveCSS('outline-style', 'solid');
  await page.screenshot({ path: testInfo.outputPath('audit-01-import-file-picker-focus.png') });

  const fileChooserPromise = page.waitForEvent('filechooser');
  await page.keyboard.press('Enter');
  const fileChooser = await fileChooserPromise;
  await fileChooser.setFiles({
    name: 'keyboard-import.epub',
    mimeType: 'application/epub+zip',
    buffer: Buffer.from('keyboard import fixture'),
  });
  await expect(page.locator('.selected-file')).toContainText('keyboard-import.epub');
  const replaceButton = page.getByRole('button', { name: 'Đổi tệp' });
  await page.keyboard.press('Tab');
  await expect(replaceButton).toBeFocused();
  await page.keyboard.press('Tab');
  const rightsCheckbox = page.getByRole('checkbox');
  await expect(rightsCheckbox).toBeFocused();
  await expect(page.locator('.account-rights-check .check-visual')).toHaveCSS(
    'outline-style',
    'solid',
  );
  const rightsGeometry = await page.evaluate(() => {
    const check = document.querySelector<HTMLElement>('.account-rights-check .check-visual');
    const actionBar = document.querySelector<HTMLElement>('.mobile-form-actions');
    if (!check || !actionBar) throw new Error('Rights checkbox or fixed action bar is missing');
    return {
      checkBottom: check.getBoundingClientRect().bottom,
      actionTop: actionBar.getBoundingClientRect().top,
    };
  });
  expect(rightsGeometry.checkBottom).toBeLessThanOrEqual(rightsGeometry.actionTop - 8);
  await expectNoAxeViolations(page);
  await page.screenshot({ path: testInfo.outputPath('audit-02-import-rights-checkbox-focus.png') });

  await page.keyboard.press('Space');
  await expect(rightsCheckbox).toBeChecked();
  const startButton = page.getByRole('button', { name: 'Tải lên và phân tích' });
  await expect(startButton).toBeEnabled();
  await page.keyboard.press('Tab');
  await expect(startButton).toBeFocused();
  await expect(startButton).toBeEnabled();
  const actionGeometry = await page.evaluate(() => {
    const action = document.querySelector<HTMLElement>('.mobile-form-actions .button-primary');
    const mobileNav = document.querySelector<HTMLElement>('.mobile-nav');
    if (!action || !mobileNav) throw new Error('Import action or mobile navigation is missing');
    const actionBox = action.getBoundingClientRect();
    const navBox = mobileNav.getBoundingClientRect();
    return { actionHeight: actionBox.height, navGap: navBox.top - actionBox.bottom };
  });
  expect(actionGeometry.actionHeight).toBeGreaterThanOrEqual(44);
  expect(actionGeometry.navGap).toBeGreaterThanOrEqual(8);
  await expectNoAxeViolations(page);
  await page.screenshot({ path: testInfo.outputPath('audit-03-import-action-focus.png') });

  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/app\/import\/review$/);
  const reviewHeading = page.getByRole('heading', { name: 'Kiểm tra metadata và mục lục' });
  await expect(reviewHeading).toBeVisible();
  await expect(reviewHeading).toBeFocused();
  await expect(reviewHeading).toHaveCSS('outline-style', 'solid');
  await expectNoAxeViolations(page);
  await page.screenshot({ path: testInfo.outputPath('audit-04-import-review-focus.png') });
});

test('account import stays fail-closed when no approved rights statement is available', async ({
  page,
}) => {
  const api = await installAccountRoutes(page, { rightsStatus: 503 });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await expect.poll(() => api.events).toContain('rights');
  await page.locator('#work-file').setInputFiles({
    name: 'son-ha.epub',
    mimeType: 'application/epub+zip',
    buffer: Buffer.from('private test epub bytes'),
  });
  await expect(page.locator('.import-inline-error')).toContainText(
    'hướng dẫn quyền nhập đã được duyệt',
  );
  await expect(page.getByRole('checkbox')).toBeDisabled();
  await expect(page.locator('.mobile-form-actions .button-primary')).toBeDisabled();
  expect(api.createBodies).toHaveLength(0);
  expect(api.events).toEqual(['rights']);
  await expectNoAxeViolations(page);
});

test('account import can retry an unavailable library prerequisite without losing unchanged rights confirmation', async ({
  page,
}) => {
  await installAccountRoutes(page, { libraryFailures: 1 });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await expect(page.getByText(rightsCopy)).toBeVisible();
  await expect(page.locator('.import-inline-error')).toContainText(
    'Chưa tải được thư viện nhận tác phẩm',
  );

  await page.locator('#work-file').setInputFiles({
    name: 'son-ha.epub',
    mimeType: 'application/epub+zip',
    buffer: Buffer.from('private test epub bytes'),
  });
  await page.locator('.account-rights-check').click();
  const startButton = page.getByRole('button', { name: 'Tải lên và phân tích' });
  await expect(startButton).toBeDisabled();
  await page.getByRole('button', { name: 'Tải lại hướng dẫn và thư viện' }).click();
  await expect(startButton).toBeEnabled();
  await expect(page.locator('.account-rights-check input')).toBeChecked();
  await expectNoAxeViolations(page);
});

test('account import clears confirmation when a refreshed rights statement changes', async ({
  page,
}) => {
  await installAccountRoutes(page, { libraryFailures: 1, rightsVersionChangeOnRetry: true });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await expect(page.getByText(rightsCopy)).toBeVisible();
  await page.locator('#work-file').setInputFiles({
    name: 'son-ha.epub',
    mimeType: 'application/epub+zip',
    buffer: Buffer.from('private test epub bytes'),
  });
  await page.locator('.account-rights-check').click();
  await expect(page.locator('.account-rights-check input')).toBeChecked();

  await page.getByRole('button', { name: 'Tải lại hướng dẫn và thư viện' }).click();
  await expect(page.getByText(`${rightsCopy} Bản cập nhật.`)).toBeVisible();
  await expect(page.locator('.account-rights-check input')).not.toBeChecked();
  await expect(page.getByRole('button', { name: 'Tải lên và phân tích' })).toBeDisabled();
  await expectNoAxeViolations(page);
});

test('an EPUB parse failure stays on the import step and never previews or commits content', async ({
  page,
}, testInfo) => {
  const api = await installAccountRoutes(page, { parseFailure: true });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await expect(page.getByText(rightsCopy)).toBeVisible();
  await page.locator('#work-file').setInputFiles({
    name: 'broken.epub',
    mimeType: 'application/epub+zip',
    buffer: Buffer.from('invalid epub fixture'),
  });
  await page.locator('.account-rights-check').click();
  await page.getByRole('button', { name: 'Tải lên và phân tích' }).click();

  await expect(page).toHaveURL(/\/app\/import$/);
  await expect(page.locator('.import-inline-error')).toContainText('Không thể phân tích tệp');
  await expect(page.locator('.mobile-form-actions .button-primary')).toBeDisabled();
  await page.screenshot({ path: testInfo.outputPath('audit-parse-failure-360x800.png') });
  expect(api.events).toEqual(['rights', 'accept', 'create', 'upload', 'parse']);
  expect(api.createBodies).toHaveLength(1);
  await expectNoAxeViolations(page);
});

test('account import accepts a UTF-8 TXT file and hands it to the private parser flow', async ({
  page,
}) => {
  const api = await installAccountRoutes(page, { singleChapter: true });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await expect(page.getByText('TXT, HTML hoặc Markdown UTF-8.', { exact: false })).toBeVisible();
  await expect(page.locator('#work-file')).toHaveAttribute('accept', /\.txt/);

  await page.locator('#work-file').setInputFiles({
    name: 'chuong-dau.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('Chương đầu\n\nNội dung riêng tư.', 'utf8'),
  });
  await expect(page.locator('.selected-file')).toContainText('chuong-dau.txt');
  const startButton = page.getByRole('button', { name: 'Tải lên và phân tích' });
  await expect(startButton).toBeDisabled();
  await page.locator('.account-rights-check').click();
  await startButton.click();
  await expect(page).toHaveURL(/\/app\/import\/review$/);
  await expect(page.locator('.large-file-type')).toHaveText('TXT');
  await expect(page.getByRole('heading', { name: 'Kiểm tra metadata và mục lục' })).toBeVisible();
  await expect(page.locator('.import-chapter-list')).toContainText('Nội dung');
  await expect(page.locator('.chapter-order-actions')).toHaveCount(0);
  await expect(page.locator('.import-warning-list')).toContainText('một chương');
  expect(api.createBodies[0]?.['source']).toMatchObject({
    kind: 'upload',
    filename: 'chuong-dau.txt',
    contentType: 'text/plain',
  });
  expect(api.events).toEqual(['rights', 'accept', 'create', 'upload', 'parse']);
  await expectNoAxeViolations(page);
});

test('account import accepts a single HTML document and reviews it as one private chapter', async ({
  page,
}) => {
  const api = await installAccountRoutes(page, {
    singleChapter: true,
    singleChapterWarningCode: 'HTML_SINGLE_DOCUMENT',
  });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await expect(page.getByText('TXT, HTML hoặc Markdown UTF-8.', { exact: false })).toBeVisible();
  await expect(page.locator('#work-file')).toHaveAttribute('accept', /\.html/);
  await expect(page.locator('#work-file')).toHaveAttribute('accept', /\.htm/);

  await page.locator('#work-file').setInputFiles({
    name: 'thanh-pho-mua.html',
    mimeType: 'text/html',
    buffer: Buffer.from(
      '<html><body><h1>Mở đầu</h1><p>Câu chuyện riêng tư.</p></body></html>',
      'utf8',
    ),
  });
  await expect(page.locator('.selected-file')).toContainText('thanh-pho-mua.html');
  const startButton = page.getByRole('button', { name: 'Tải lên và phân tích' });
  await expect(startButton).toBeDisabled();
  await page.locator('.account-rights-check').click();
  await startButton.click();
  await expect(page).toHaveURL(/\/app\/import\/review$/);
  await expect(page.locator('.large-file-type')).toHaveText('HTML');
  await expect(page.getByRole('heading', { name: 'Kiểm tra metadata và mục lục' })).toBeVisible();
  await expect(page.locator('.import-chapter-list')).toContainText('Nội dung');
  await expect(page.locator('.chapter-order-actions')).toHaveCount(0);
  await expect(page.locator('.import-warning-list')).toContainText(
    'Tài liệu HTML được giữ thành một chương',
  );
  expect(api.createBodies[0]?.['source']).toMatchObject({
    kind: 'upload',
    filename: 'thanh-pho-mua.html',
    contentType: 'text/html',
  });
  expect(api.events).toEqual(['rights', 'accept', 'create', 'upload', 'parse']);
  await expectNoAxeViolations(page);
});

test('account import accepts Markdown and reviews chapters derived from repeated headings', async ({
  page,
}) => {
  const api = await installAccountRoutes(page, {
    chapterNames: ['Chapter One', 'Chapter Two'],
    warningCodes: ['MARKDOWN_FORMATTING_NORMALIZED', 'MARKDOWN_CHAPTERS_FROM_HEADINGS'],
  });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await expect(page.getByText('Markdown UTF-8', { exact: false })).toBeVisible();
  await expect(page.locator('#work-file')).toHaveAttribute('accept', /\.md/);
  await expect(page.locator('#work-file')).toHaveAttribute('accept', /\.markdown/);

  await page.locator('#work-file').setInputFiles({
    name: 'thanh-pho-mua.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from(
      '# Thành phố mưa\n\n## Chapter One\n\nMở đầu.\n\n## Chapter Two\n\nTiếp tục.',
      'utf8',
    ),
  });
  await expect(page.locator('.selected-file')).toContainText('thanh-pho-mua.md');
  await page.locator('.account-rights-check').click();
  await page.getByRole('button', { name: 'Tải lên và phân tích' }).click();

  await expect(page).toHaveURL(/\/app\/import\/review$/);
  await expect(page.locator('.large-file-type')).toHaveText('MD');
  await expect(page.locator('.import-chapter-list')).toContainText('Chapter One');
  await expect(page.locator('.import-chapter-list')).toContainText('Chapter Two');
  await expect(page.locator('.import-warning-list')).toContainText('được dùng để chia chương');
  expect(api.createBodies[0]?.['source']).toMatchObject({
    kind: 'upload',
    filename: 'thanh-pho-mua.md',
    contentType: 'text/markdown',
  });
  expect(api.events).toEqual(['rights', 'accept', 'create', 'upload', 'parse']);
  await expectNoAxeViolations(page);
});

test('account import rejects Markdown larger than the parser limit before upload', async ({
  page,
}) => {
  const api = await installAccountRoutes(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await page.locator('#work-file').setInputFiles({
    name: 'oversized.md',
    mimeType: 'text/markdown',
    buffer: Buffer.alloc(16 * 1024 * 1024 + 1, 0x20),
  });
  await expect(page.locator('.field-error')).toContainText('không vượt quá 16 MiB');
  await expect(page.locator('.selected-file')).toHaveCount(0);
  expect(api.createBodies).toHaveLength(0);
  await expectNoAxeViolations(page);
});

test('account import rejects HTML larger than the parser limit before upload', async ({ page }) => {
  await installAccountRoutes(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/import');
  await page.locator('#work-file').setInputFiles({
    name: 'oversized.html',
    mimeType: 'text/html',
    buffer: Buffer.alloc(4 * 1024 * 1024 + 1, 0x20),
  });
  await expect(page.locator('.field-error')).toContainText('không vượt quá 4 MiB');
  await expect(page.locator('.selected-file')).toHaveCount(0);
  await expectNoAxeViolations(page);
});
