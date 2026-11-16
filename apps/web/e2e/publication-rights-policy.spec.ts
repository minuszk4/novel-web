import { expect, expectNoAxeViolations, test } from './support';

const workId = '11111111-1111-4111-8111-111111111111';
const csrfToken = 'A'.repeat(43);
const policy = {
  version: 'rights-ui-v1',
  effectiveAt: '2026-11-15T00:00:00Z',
  displayText:
    'Chính sách thử nghiệm quyền xuất bản.\n<script>nội dung này phải hiển thị như văn bản thuần</script>',
  policySha256: 'a'.repeat(64),
  acceptedAt: null,
};

async function installAccount(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        apiVersion: '8.4.0',
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
          translationFakePreviewEnabled: false,
          publicCatalogEnabled: false,
        },
      }),
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        user: {
          id: '11111111-aaaa-4aaa-8aaa-111111111111',
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
    }),
  );
  await page.route('**/api/v1/auth/policies/current', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
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
    }),
  );
  await page.route('**/api/v1/auth/policies/terms/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Điều khoản' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Quyền riêng tư' }),
  );
}

async function installPrivateManuscript(page: import('@playwright/test').Page): Promise<void> {
  await page.route(`**/api/v1/authoring/manuscripts/${workId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store', ETag: '"manuscript-metadata-1"' },
      body: JSON.stringify({
        id: workId,
        title: 'Hành trình tu luyện',
        author: 'Bút danh',
        language: 'vi',
        visibility: 'private',
        publicationState: 'draft',
        chapterCount: 0,
        createdAt: '2026-11-15T12:00:00Z',
        metadataVersion: 1,
      }),
    }),
  );
  await page.route(`**/api/v1/authoring/manuscripts/${workId}/chapters?**`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
      body: JSON.stringify({ items: [], nextCursor: null }),
    }),
  );
}

for (const viewport of [
  { name: 'effective 200% width 180×400', width: 180, height: 400 },
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'tablet 768×1024', width: 768, height: 1024 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const) {
  test(`author reads and explicitly acknowledges the exact rights policy at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccount(page);
    await installPrivateManuscript(page);

    let policyReads = 0;
    let acceptanceRequest: { body: unknown; idempotencyKey: string | undefined } | null = null;
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/publication/rights-policy`,
      async (route) => {
        policyReads += 1;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
          body: JSON.stringify(policy),
        });
      },
    );
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/publication/rights-policy/acceptance`,
      async (route) => {
        acceptanceRequest = {
          body: route.request().postDataJSON(),
          idempotencyKey: route.request().headers()['idempotency-key'],
        };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
          body: JSON.stringify({
            id: '55555555-5555-4555-8555-555555555555',
            policyVersion: policy.version,
            policySha256: policy.policySha256,
            acceptedAt: '2026-11-15T12:30:00Z',
          }),
        });
      },
    );
    await page.route('**/api/v1/auth/csrf', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ token: csrfToken }),
      }),
    );

    await page.goto(`/app/authoring/${workId}/chapters`);
    await expect(page.getByRole('heading', { name: 'Hành trình tu luyện' })).toBeVisible();
    await expect(page.getByText('Bản thảo riêng tư · Chưa xuất bản')).toBeVisible();
    expect(policyReads).toBe(0);

    await page.getByRole('button', { name: 'Kiểm tra chính sách quyền xuất bản' }).click();
    await expect(page.getByRole('heading', { name: 'Chính sách quyền xuất bản' })).toBeFocused();
    expect(policyReads).toBe(1);
    await expect(page.getByText(policy.displayText, { exact: true })).toBeVisible();
    await expect(page.locator('.authoring-rights-document script')).toHaveCount(0);
    const checkbox = page.getByRole('checkbox', {
      name: 'Tôi đã đọc và xác nhận chính sách này cho bản thảo Hành trình tu luyện',
    });
    await expect(checkbox).not.toBeChecked();
    const confirm = page.getByRole('button', { name: 'Xác nhận đã đọc chính sách' });
    await expect(confirm).toBeDisabled();
    await expectNoAxeViolations(page);

    await checkbox.check();
    await expect(confirm).toBeEnabled();
    expect(acceptanceRequest).toBeNull();
    await expect(page.getByText(policy.displayText, { exact: true })).toBeVisible();
    const submit = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname.endsWith('/rights-policy/acceptance'),
    );
    await confirm.click();
    expect((await submit).status()).toBe(200);
    await expect(
      page.getByText(/Đã ghi nhận xác nhận policy phiên bản rights-ui-v1/),
    ).toBeVisible();
    await expect(page.locator('.authoring-rights-success')).toContainText(
      'không xác minh quyền tác giả hoặc giấy phép',
    );
    await expect(page.getByRole('checkbox')).toHaveCount(0);
    await expectNoAxeViolations(page);

    expect(acceptanceRequest).toEqual({
      body: { policyVersion: 'rights-ui-v1', confirmed: true },
      idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
    });
    const widths = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(widths.document).toBeLessThanOrEqual(widths.viewport);
    await page.screenshot({
      path: testInfo.outputPath(`publication-rights-${viewport.width}.png`),
    });
  });
}

test('missing policy stays unavailable, private and impossible to acknowledge', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installAccount(page);
  await installPrivateManuscript(page);
  await page.route(`**/api/v1/authoring/manuscripts/${workId}/publication/rights-policy`, (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
      body: JSON.stringify({ code: 'PUBLICATION_RIGHTS_POLICY_NOT_CONFIGURED' }),
    }),
  );
  let acceptanceRequests = 0;
  await page.route(
    `**/api/v1/authoring/manuscripts/${workId}/publication/rights-policy/acceptance`,
    async (route) => {
      acceptanceRequests += 1;
      await route.continue();
    },
  );

  await page.goto(`/app/authoring/${workId}/chapters`);
  await page.getByRole('button', { name: 'Kiểm tra chính sách quyền xuất bản' }).click();
  await expect(page.getByText(/chưa được cấu hình hoặc chưa thể xác minh/)).toBeVisible();
  await expect(
    page.getByText('Không thể gửi duyệt hoặc công khai khi chưa có chính sách được phê duyệt.'),
  ).toBeVisible();
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Xác nhận đã đọc chính sách' })).toHaveCount(0);
  expect(acceptanceRequests).toBe(0);
  const widths = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(widths.document).toBeLessThanOrEqual(widths.viewport);
  await expectNoAxeViolations(page);
});
