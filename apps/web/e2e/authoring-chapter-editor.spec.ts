import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const workId = '11111111-1111-4111-8111-111111111111';
const chapterId = '22222222-2222-4222-8222-222222222222';
const revisionId = '44444444-4444-4444-8444-444444444444';
const csrfToken = 'A'.repeat(43);

async function json(route: Route, body: unknown, status = 200, headers = {}): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers,
    body: JSON.stringify(body),
  });
}

async function installAccount(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '6.24.0',
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
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
      user: {
        id: 'user-1',
        displayName: 'Tác giả',
        contactEmail: 'author@example.test',
        avatarUrl: null,
        status: 'ACTIVE',
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
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Điều khoản' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Quyền riêng tư' }),
  );
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route(`**/api/v1/authoring/manuscripts/${workId}`, (route) =>
    json(
      route,
      {
        id: workId,
        title: 'Bản thảo mẫu',
        author: 'Tác giả',
        language: 'vi',
        visibility: 'private',
        publicationState: 'draft',
        chapterCount: 1,
        createdAt: '2026-11-14T12:00:00Z',
        metadataVersion: 1,
      },
      200,
      { 'Cache-Control': 'no-store', ETag: '"manuscript-metadata-1"' },
    ),
  );
}

for (const viewport of [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: '200% zoom equivalent 640×450', width: 640, height: 450 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const) {
  test(`private chapter editor is usable without overflow at ${viewport.name}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccount(page);
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/draft`,
      (route) =>
        json(
          route,
          {
            id: chapterId,
            workId,
            title: 'Chương mở đầu',
            status: 'draft',
            revisionNo: 0,
            sourceFormat: null,
            normalizedContent: '',
            segments: [],
            updatedAt: '2026-11-14T12:00:00Z',
          },
          200,
          { 'Cache-Control': 'no-store', ETag: '"draft-0"' },
        ),
    );

    await page.goto(`/app/authoring/${workId}/chapters/${chapterId}`);
    await expect(page.getByRole('heading', { name: 'Chương mở đầu' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Chương mở đầu' })).toBeFocused();
    await expect(page.getByLabel('Văn bản bản thảo')).toBeVisible();
    await expect(page.getByText('Bản thảo riêng tư', { exact: true })).toBeVisible();
    await expectNoAxeViolations(page);

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  });
}

test('chapter editor saves plain text with CSRF and version headers without browser-side storage', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installAccount(page);
  await page.route(
    `**/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/draft`,
    (route) =>
      json(
        route,
        {
          id: chapterId,
          workId,
          title: 'Chương mở đầu',
          status: 'draft',
          revisionNo: 0,
          sourceFormat: null,
          normalizedContent: '',
          segments: [],
          updatedAt: '2026-11-14T12:00:00Z',
        },
        200,
        { 'Cache-Control': 'no-store', ETag: '"draft-0"' },
      ),
  );

  let savedRequest: {
    body: unknown;
    ifMatch: string | null;
    csrf: string | null;
    idempotencyKey: string | null;
  } | null = null;
  await page.route(
    `**/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/revisions`,
    async (route) => {
      savedRequest = {
        body: route.request().postDataJSON(),
        ifMatch: route.request().headers()['if-match'] ?? null,
        csrf: route.request().headers()['x-csrf-token'] ?? null,
        idempotencyKey: route.request().headers()['idempotency-key'] ?? null,
      };
      await json(
        route,
        {
          id: revisionId,
          chapterId,
          revisionNo: 1,
          sourceFormat: 'plain_text',
          createdAt: '2026-11-14T12:01:00Z',
        },
        201,
        { 'Cache-Control': 'no-store', ETag: '"draft-1"' },
      );
    },
  );

  await page.clock.install();
  await page.goto(`/app/authoring/${workId}/chapters/${chapterId}`);
  await expect(page.getByRole('heading', { name: 'Chương mở đầu' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Chương mở đầu' })).toBeFocused();
  // Pause slightly in the future to avoid crossing the real browser clock between navigation and this call.
  await page.clock.pauseAt(new Date(Date.now() + 5_000));
  const editor = page.getByLabel('Văn bản bản thảo');
  await page.keyboard.press('Tab');
  await expect(editor).toBeFocused();
  await page.keyboard.insertText('Đêm xuống.\n\nÁnh trăng hiện ra.');
  // Flush Angular input updates without reaching the 900 ms autosave deadline.
  await page.clock.runFor(100);
  const saveButton = page.getByRole('button', { name: 'Lưu ngay' });
  await expect(saveButton).toBeEnabled();
  expect(savedRequest).toBeNull();
  await page.keyboard.press('Tab');
  await expect(saveButton).toBeFocused();
  const csrfResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/csrf',
  );
  const saveResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname ===
        `/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/revisions`,
  );
  await page.keyboard.press('Enter');
  const [csrf, saved] = await Promise.all([csrfResponse, saveResponse]);
  expect(csrf.status()).toBe(200);
  expect(saved.status()).toBe(201);
  expect(savedRequest).toEqual({
    body: {
      sourceFormat: 'plain_text',
      normalizedContent: 'Đêm xuống.\n\nÁnh trăng hiện ra.',
      segments: [
        {
          ordinal: 1,
          type: 'paragraph',
          sourceText: 'Đêm xuống.',
          stableAnchor: 'chapter-22222222-p1',
        },
        {
          ordinal: 2,
          type: 'paragraph',
          sourceText: 'Ánh trăng hiện ra.',
          stableAnchor: 'chapter-22222222-p2',
        },
      ],
    },
    ifMatch: '"draft-0"',
    csrf: csrfToken,
    idempotencyKey: expect.stringMatching(/^[A-Za-z0-9._~-]{16,128}$/),
  });
  await page.clock.resume();
  await expect(page.getByRole('status').filter({ hasText: 'Đã lưu · phiên bản 1' })).toBeVisible();
  const storedContent = await page.evaluate(() =>
    JSON.stringify({ local: Object.values(localStorage), session: Object.values(sessionStorage) }),
  );
  expect(storedContent).not.toContain('Đêm xuống.');
  await expectNoAxeViolations(page);
});

for (const viewport of [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: '200% zoom equivalent 640×450', width: 640, height: 450 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const) {
  test(`saved private chapter preview is read-only and responsive at ${viewport.name}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccount(page);
    const savedText = '<script>không chạy</script>\n\nĐêm xuống.';
    let draftReads = 0;
    let revisionWrites = 0;
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (url.pathname.endsWith(`/chapters/${chapterId}/draft`)) draftReads += 1;
      if (url.pathname.endsWith(`/chapters/${chapterId}/revisions`)) revisionWrites += 1;
    });
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/draft`,
      (route) =>
        json(
          route,
          {
            id: chapterId,
            workId,
            title: 'Chương mở đầu',
            status: 'draft',
            revisionNo: 4,
            sourceFormat: 'plain_text',
            normalizedContent: savedText,
            segments: [],
            updatedAt: '2026-11-14T12:00:00Z',
          },
          200,
          { 'Cache-Control': 'no-store', ETag: '"draft-4"' },
        ),
    );

    await page.goto(`/app/authoring/${workId}/chapters/${chapterId}`);
    await expect(page.getByRole('button', { name: 'Xem bản đã lưu' })).toBeEnabled();
    await page.getByRole('button', { name: 'Xem bản đã lưu' }).click();
    await expect(page.getByRole('article')).toContainText('Phiên bản 4 · Chưa xuất bản');
    await expect(page.locator('.chapter-editor-preview-content')).toHaveText(savedText);
    await expect(page.locator('.chapter-editor-preview script')).toHaveCount(0);
    await expect(page.getByLabel('Văn bản bản thảo')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Tiếp tục biên tập' })).toBeVisible();
    await expectNoAxeViolations(page);

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    expect(draftReads).toBe(1);
    expect(revisionWrites).toBe(0);
    await page.getByRole('button', { name: 'Tiếp tục biên tập' }).click();
    await expect(page.getByLabel('Văn bản bản thảo')).toHaveValue(savedText);
  });
}

test('chapter editor explains the per-paragraph byte limit before attempting a save', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installAccount(page);
  await page.route(
    `**/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/draft`,
    (route) =>
      json(
        route,
        {
          id: chapterId,
          workId,
          title: 'Chương mở đầu',
          status: 'draft',
          revisionNo: 0,
          sourceFormat: null,
          normalizedContent: '',
          segments: [],
          updatedAt: '2026-11-14T12:00:00Z',
        },
        200,
        { 'Cache-Control': 'no-store', ETag: '"draft-0"' },
      ),
  );
  let revisionAttempted = false;
  await page.route(
    `**/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/revisions`,
    async (route) => {
      revisionAttempted = true;
      await route.abort();
    },
  );

  await page.goto(`/app/authoring/${workId}/chapters/${chapterId}`);
  const editor = page.getByLabel('Văn bản bản thảo');
  await editor.fill('x'.repeat(1_048_577));
  await expect(
    page.getByText('Một đoạn văn vượt giới hạn 1 MiB. Hãy chia nội dung thành các đoạn ngắn hơn.'),
  ).toBeVisible();
  await expect(editor).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByRole('button', { name: 'Lưu ngay' })).toBeDisabled();
  expect(revisionAttempted).toBe(false);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  await expectNoAxeViolations(page);
});
