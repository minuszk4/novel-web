import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const csrfToken = 'A'.repeat(43);
const draftId = '11111111-1111-4111-8111-111111111111';

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installDemo(page: Page): Promise<void> {
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
  await page.route('**/api/v1/auth/me', (route) => json(route, { code: 'UNAUTHENTICATED' }, 401));
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
        importProcessingEnabled: true,
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
}

test('Author Studio asks demo readers to sign in and preserves the destination', async ({
  page,
}) => {
  await installDemo(page);
  await page.goto('/app/authoring/new');

  await expect(page.getByRole('heading', { name: 'Tạo bản thảo' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Đăng nhập để bắt đầu viết' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Đăng nhập bằng Google' })).toHaveAttribute(
    'href',
    '/sign-in?returnTo=%2Fapp%2Fauthoring%2Fnew',
  );
  await expectNoAxeViolations(page);
});

for (const viewport of [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: '200% zoom equivalent 640×450', width: 640, height: 450 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const) {
  test(`creates a private metadata draft without horizontal overflow at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccount(page);
    let receivedRequest: { body: unknown; csrf: string | null; key: string | null } | null = null;
    await page.route('**/api/v1/authoring/manuscripts', async (route) => {
      receivedRequest = {
        body: route.request().postDataJSON(),
        csrf: route.request().headers()['x-csrf-token'] ?? null,
        key: route.request().headers()['idempotency-key'] ?? null,
      };
      await json(
        route,
        {
          id: draftId,
          title: 'Hành trình tu luyện',
          author: 'Bút danh',
          language: 'vi',
          publicationState: 'draft',
          visibility: 'private',
          createdAt: '2026-11-14T12:00:00Z',
        },
        201,
      );
    });

    await page.goto('/app/authoring/new');
    await page.getByLabel('Tên tác phẩm').fill('Hành trình tu luyện');
    await page.getByLabel('Bút danh').fill('Bút danh');
    await page.getByLabel('Ngôn ngữ sáng tác').fill('vi');
    await expectNoAxeViolations(page);
    await page.screenshot({ path: testInfo.outputPath('authoring-create-form.png') });

    await page.getByRole('button', { name: 'Tạo bản thảo riêng tư' }).click();
    await expect(page.getByRole('heading', { name: 'Hành trình tu luyện' })).toBeVisible();
    await expect(page.locator('.authoring-success')).toContainText(
      /chưa thể nhập nội dung chương, xuất bản hay chia sẻ tác phẩm/,
    );
    expect(receivedRequest).toEqual({
      body: { title: 'Hành trình tu luyện', author: 'Bút danh', language: 'vi' },
      csrf: csrfToken,
      key: expect.stringMatching(/^[A-Za-z0-9._~-]{16,128}$/),
    });

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    await expectNoAxeViolations(page);
  });
}

for (const viewport of [
  { name: 'effective 200% zoom proxy 360×800 → 180×400 CSS', width: 180, height: 400 },
  { name: 'mobile 360×800', width: 360, height: 800 },
] as const) {
  test(`validation moves keyboard focus to the first invalid field at ${viewport.name}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccount(page);
    await page.goto('/app/authoring/new');

    await page.getByRole('button', { name: 'Tạo bản thảo riêng tư' }).click();
    const title = page.getByLabel('Tên tác phẩm');
    await expect(title).toBeFocused();
    await expect(title).toHaveAttribute('aria-invalid', 'true');
    await expect(title).toHaveAttribute('aria-errormessage', 'manuscript-title-error');
    await expect(page.locator('#manuscript-title-error')).toBeVisible();
    await expect(title).toBeInViewport();
    await expectNoAxeViolations(page);

    await title.fill('Bản thảo hợp lệ');
    const language = page.getByLabel('Ngôn ngữ sáng tác');
    await language.fill('x');
    await page.getByRole('button', { name: 'Tạo bản thảo riêng tư' }).click();
    await expect(language).toBeFocused();
    await expect(language).toHaveAttribute('aria-invalid', 'true');
    await expect(language).toHaveAttribute('aria-errormessage', 'manuscript-language-error');
    await expect(page.locator('#manuscript-language-error')).toBeVisible();
    await expect(language).toBeInViewport();
    await expectNoAxeViolations(page);
  });
}
