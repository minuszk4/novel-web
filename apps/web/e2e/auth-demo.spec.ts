import { expect, expectNoAxeViolations, test } from './support';
import type { Page } from '@playwright/test';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

const state = 'A'.repeat(43);

function bootstrapResponse(
  responseState = state,
  attemptExpiresAt = new Date(Date.now() + 5 * 60_000).toISOString(),
) {
  return {
    firebaseConfig: {
      apiKey: 'firebase-test-key-web-auth-not-a-credential',
      authDomain: 'novel-test.firebaseapp.com',
      projectId: 'novel-test',
      appId: '1:123456789012:web:abcdef1234567890',
    },
    state: responseState,
    locale: 'vi',
    attemptExpiresAt,
  };
}

async function routeBootstrap(page: Page, status: number): Promise<void> {
  await page.route('**/api/v1/auth/firebase/bootstrap**', (route) =>
    route.fulfill({
      status,
      contentType: 'application/json',
      body:
        status === 200
          ? JSON.stringify(bootstrapResponse())
          : JSON.stringify({ code: 'AUTH_PROVIDER_UNAVAILABLE' }),
    }),
  );
}

for (const viewport of viewports) {
  test(`sign-in keeps demo available when Firebase is unavailable at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await routeBootstrap(page, 503);
    await page.route('**/api/v1/system/status**', (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'API_UNAVAILABLE' }),
      }),
    );
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/sign-in');

    const providerStatus = page.getByRole('status');
    await expect(providerStatus).toContainText('Google chưa sẵn sàng');
    await expect(providerStatus).toContainText('Firebase');
    await expect(page.getByRole('button', { name: 'Tiếp tục với Google' })).toBeDisabled();
    const retryBox = await page.getByRole('button', { name: 'Thử lại' }).boundingBox();
    expect(retryBox?.width).toBeGreaterThanOrEqual(44);
    expect(retryBox?.height).toBeGreaterThanOrEqual(44);
    await expectNoAxeViolations(page);

    await page.getByRole('link', { name: 'Tiếp tục với bản demo' }).click();
    await expect(page).toHaveURL(/\/app\/setup$/);
    const apiStatusRetry = page.locator('.demo-banner .status-retry');
    await expect(apiStatusRetry).toBeVisible();
    const retryBounds = await apiStatusRetry.boundingBox();
    expect(retryBounds?.width).toBeGreaterThanOrEqual(44);
    expect(retryBounds?.height).toBeGreaterThanOrEqual(44);
    const setupHeading = page.getByRole('heading', { name: 'Một vài điều cần biết' });
    await expect(setupHeading).toBeFocused();
    await expect(setupHeading).toHaveCSS('outline-style', 'solid');
    await expectNoAxeViolations(page);
    if (viewport.width === 360) {
      await page.screenshot({
        path: testInfo.outputPath('audit-01-setup-route-heading-focus.png'),
      });
    }
  });

  test(`sign-in prepares Firebase Google popup at ${viewport.name}`, async ({ page }) => {
    await routeBootstrap(page, 200);
    await page.route('https://novel-test.firebaseapp.com/**', (route) =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Firebase Auth</title>' }),
    );
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/sign-in?returnTo=%2Fapp%2Flibrary%3Ffilter%3Dreading');

    const signInButton = page.getByRole('button', { name: 'Tiếp tục với Google' });
    await expect(signInButton).toBeEnabled();
    await expect(page.getByRole('status')).toContainText('bạn sẽ quay lại Novel');
    await expectNoAxeViolations(page);

    const browserState = await page.evaluate(() => ({
      storageKeys: Object.keys(localStorage),
      gisScripts: document.querySelectorAll('script[src*="accounts.google.com/gsi"]').length,
    }));
    expect(browserState.storageKeys.filter((key) => /token|credential|nonce/i.test(key))).toEqual(
      [],
    );
    expect(browserState.gisScripts).toBe(0);
  });
}

test('sign-in waits after bootstrap rate limiting and lets the user retry', async ({ page }) => {
  let attempts = 0;
  await page.route('**/api/v1/auth/firebase/bootstrap**', (route) => {
    attempts += 1;
    return route.fulfill({
      status: attempts === 1 ? 429 : 200,
      contentType: 'application/json',
      body:
        attempts === 1
          ? JSON.stringify({ code: 'RATE_LIMITED' })
          : JSON.stringify(bootstrapResponse()),
    });
  });
  await page.goto('/sign-in');

  await expect(page.getByRole('status')).toContainText('Hãy chờ trước khi thử lại');
  await page.getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.getByRole('button', { name: 'Tiếp tục với Google' })).toBeEnabled();
  expect(attempts).toBe(2);
  await expectNoAxeViolations(page);
});

test('sign-in rejects an expired bootstrap and requests a fresh attempt on retry', async ({
  page,
}) => {
  let bootstrapAttempts = 0;
  await page.route('**/api/v1/auth/firebase/bootstrap**', (route) => {
    bootstrapAttempts += 1;
    const response =
      bootstrapAttempts === 1
        ? bootstrapResponse('A'.repeat(43), new Date(Date.now() - 1_000).toISOString())
        : bootstrapResponse('B'.repeat(43));
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(response),
    });
  });
  await page.goto('/sign-in');

  await expect(page.getByRole('status')).toContainText('Chưa chuẩn bị được Firebase Authentication');
  await page.getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.getByRole('button', { name: 'Tiếp tục với Google' })).toBeEnabled();
  expect(bootstrapAttempts).toBe(2);
});

test('sign-in replaces an untrusted return path with the server default', async ({ page }) => {
  let attempts = 0;
  const untrustedPaths = [
    'https://attacker.example/callback',
    '/app/../outside',
    '/%2F%2Fattacker.example',
    '/app/%5C%5Coutside',
  ];
  await page.route('**/api/v1/auth/firebase/bootstrap**', (route) => {
    const requestUrl = new URL(route.request().url());
    expect(requestUrl.searchParams.get('returnTo')).toBe('/app/home');
    attempts += 1;
    return route.fulfill({
      status: 503,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'AUTH_PROVIDER_UNAVAILABLE' }),
    });
  });
  for (const returnTo of untrustedPaths) {
    await page.goto(`/sign-in?returnTo=${encodeURIComponent(returnTo)}`);
    await expect(page.getByRole('status')).toContainText('Firebase');
  }
  expect(attempts).toBe(untrustedPaths.length);
});
