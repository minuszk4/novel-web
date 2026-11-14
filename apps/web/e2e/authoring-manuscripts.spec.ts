import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const workId = '11111111-1111-4111-8111-111111111111';

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installDemo(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '6.30.0',
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
    }),
  );
  await page.route('**/api/v1/auth/me', (route) => json(route, { code: 'UNAUTHENTICATED' }, 401));
}

async function installAccount(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '6.30.0',
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
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
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
}

test('demo visitor sees sign-in and never requests the owner manuscript list', async ({ page }) => {
  await installDemo(page);
  let listRequested = false;
  await page.route('**/api/v1/authoring/manuscripts**', (route) => {
    if (new URL(route.request().url()).pathname !== '/api/v1/authoring/manuscripts') {
      return route.continue();
    }
    listRequested = true;
    return json(route, { items: [], nextCursor: null });
  });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/authoring');

  await expect(page.getByRole('heading', { name: 'Đăng nhập để xem bản thảo' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Đăng nhập bằng Google' })).toHaveAttribute(
    'href',
    '/sign-in?returnTo=%2Fapp%2Fauthoring',
  );
  expect(listRequested).toBe(false);
  await expectNoAxeViolations(page);
});

for (const viewport of [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'reflow 640×450', width: 640, height: 450 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const) {
  test(`lists private owner metadata and opens its outline at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccount(page);
    let listRequestCount = 0;
    await page.route('**/api/v1/authoring/manuscripts**', async (route) => {
      if (route.request().method() !== 'GET') return route.continue();
      if (new URL(route.request().url()).pathname !== '/api/v1/authoring/manuscripts') {
        return route.continue();
      }
      listRequestCount += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
        body: JSON.stringify({
          items: [
            {
              id: workId,
              title: 'Hành trình tu luyện',
              author: 'Bút danh',
              language: 'vi',
              visibility: 'private',
              publicationState: 'draft',
              chapterCount: 3,
              createdAt: '2026-11-15T12:00:00Z',
            },
          ],
          nextCursor: null,
        }),
      });
    });
    await page.route(`**/api/v1/authoring/manuscripts/${workId}/chapters?**`, (route) =>
      json(route, { items: [], nextCursor: null }),
    );

    await page.goto('/app/authoring');
    await expect(page.getByRole('heading', { name: 'Tác phẩm của tôi' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Hành trình tu luyện' })).toBeVisible();
    await expect(page.getByText('Bản nháp riêng tư')).toBeVisible();
    await expect(page.getByText('3 chương')).toBeVisible();
    expect(listRequestCount).toBe(1);
    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    await expectNoAxeViolations(page);
    await page.screenshot({ path: testInfo.outputPath(`authoring-list-${viewport.width}.png`) });

    await page.getByRole('link', { name: 'Mở dàn ý chương của Hành trình tu luyện' }).click();
    await expect(page).toHaveURL(`/app/authoring/${workId}/chapters`);
    await expect(page.getByRole('heading', { name: 'Dàn ý bản thảo' })).toBeVisible();
    await expectNoAxeViolations(page);
  });
}

test('shows a private first-work empty state and offers creation', async ({ page }) => {
  await installAccount(page);
  await page.route('**/api/v1/authoring/manuscripts**', (route) => {
    if (new URL(route.request().url()).pathname !== '/api/v1/authoring/manuscripts') {
      return route.continue();
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
      body: JSON.stringify({ items: [], nextCursor: null }),
    });
  });
  await page.goto('/app/authoring');

  await expect(page.getByRole('heading', { name: 'Chưa có bản thảo nào' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Tạo bản thảo đầu tiên' })).toHaveAttribute(
    'href',
    '/app/authoring/new',
  );
  await expectNoAxeViolations(page);
});
