import { expect, expectNoAxeViolations, test } from './support';
import type { Page } from '@playwright/test';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

const nonce = 'A'.repeat(43);

function bootstrapResponse(
  responseNonce = nonce,
  attemptExpiresAt = new Date(Date.now() + 5 * 60_000).toISOString(),
) {
  return {
    clientId: 'novel-test-client.apps.googleusercontent.com',
    loginUri: 'http://localhost:8080/api/v1/auth/google/credential',
    nonce: responseNonce,
    locale: 'vi',
    attemptExpiresAt,
  };
}

async function routeBootstrap(page: Page, status: number): Promise<void> {
  await page.route('**/api/v1/auth/google/bootstrap**', (route) =>
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

async function routeGoogleIdentitySdk(page: Page): Promise<void> {
  await page.route('https://accounts.google.com/gsi/client', (route) =>
    route.fulfill({
      contentType: 'application/javascript',
      body: `
        window.google = { accounts: { id: {
          initialize(configuration) { window.__novelGoogleConfiguration = configuration; },
          renderButton(parent, configuration) {
            window.__novelGoogleButtonConfiguration = configuration;
            const button = document.createElement('button');
            button.type = 'button';
            button.setAttribute('aria-label', 'Sign in with Google');
            button.textContent = 'Sign in with Google';
            parent.append(button);
          }
        } } };
      `,
    }),
  );
}

for (const viewport of viewports) {
  test(`sign-in keeps demo available when Google is unavailable at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await routeBootstrap(page, 503);
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/sign-in');

    const providerStatus = page.getByRole('status');
    await expect(providerStatus).toContainText('Google chưa sẵn sàng');
    await expect(providerStatus).toContainText('chưa được cấu hình');
    await expect(page.getByRole('button', { name: 'Sign in with Google' })).toHaveCount(0);
    const retryBox = await page.getByRole('button', { name: 'Thử lại' }).boundingBox();
    expect(retryBox?.width).toBeGreaterThanOrEqual(44);
    expect(retryBox?.height).toBeGreaterThanOrEqual(44);
    await expectNoAxeViolations(page);

    await page.getByRole('link', { name: 'Tiếp tục với bản demo' }).click();
    await expect(page).toHaveURL(/\/app\/setup$/);
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

  test(`sign-in renders the official Google redirect button at ${viewport.name}`, async ({
    page,
  }) => {
    await routeBootstrap(page, 200);
    await routeGoogleIdentitySdk(page);
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/sign-in?returnTo=%2Fapp%2Flibrary%3Ffilter%3Dreading');

    await expect(page.getByRole('button', { name: 'Sign in with Google' })).toBeVisible();
    await expect(page.getByRole('status')).toContainText('bạn sẽ quay lại Novel');
    await expectNoAxeViolations(page);

    const browserConfiguration = await page.evaluate(() => {
      const testWindow = window as unknown as {
        __novelGoogleConfiguration?: Record<string, unknown>;
        __novelGoogleButtonConfiguration?: Record<string, unknown>;
      };
      return {
        identity: testWindow.__novelGoogleConfiguration,
        button: testWindow.__novelGoogleButtonConfiguration,
        storageKeys: Object.keys(localStorage),
      };
    });

    expect(browserConfiguration.identity).toMatchObject({
      client_id: 'novel-test-client.apps.googleusercontent.com',
      ux_mode: 'redirect',
      login_uri: 'http://localhost:8080/api/v1/auth/google/credential',
      nonce,
      auto_select: false,
    });
    expect(browserConfiguration.button).toMatchObject({
      type: 'standard',
      text: 'signin_with',
      locale: 'vi',
    });
    expect(browserConfiguration.button?.['width']).toBeGreaterThanOrEqual(120);
    expect(browserConfiguration.button?.['width']).toBeLessThanOrEqual(400);
    expect(
      browserConfiguration.storageKeys.filter((key) => /token|credential|nonce/i.test(key)),
    ).toEqual([]);
  });
}

test('sign-in waits after bootstrap rate limiting and lets the user retry', async ({ page }) => {
  let attempts = 0;
  await page.route('**/api/v1/auth/google/bootstrap**', (route) => {
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
  await routeGoogleIdentitySdk(page);
  await page.goto('/sign-in');

  await expect(page.getByRole('status')).toContainText('Hãy chờ trước khi thử lại');
  await page.getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.getByRole('button', { name: 'Sign in with Google' })).toBeVisible();
  expect(attempts).toBe(2);
  await expectNoAxeViolations(page);
});

test('sign-in rejects an expired bootstrap and requests a fresh attempt on retry', async ({
  page,
}) => {
  let bootstrapAttempts = 0;
  let sdkLoads = 0;
  await page.route('**/api/v1/auth/google/bootstrap**', (route) => {
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
  await page.route('https://accounts.google.com/gsi/client', (route) => {
    sdkLoads += 1;
    return route.fulfill({
      contentType: 'application/javascript',
      body: `window.google = { accounts: { id: {
        initialize(configuration) { window.__novelGoogleConfiguration = configuration; },
        renderButton(parent) { parent.innerHTML = '<button aria-label="Sign in with Google">Sign in with Google</button>'; }
      } } };`,
    });
  });
  await page.goto('/sign-in');

  await expect(page.getByRole('status')).toContainText('Chưa tải được dịch vụ đăng nhập Google');
  await expect(page.getByRole('button', { name: 'Sign in with Google' })).toHaveCount(0);
  expect(sdkLoads).toBe(0);
  await page.getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.getByRole('button', { name: 'Sign in with Google' })).toBeVisible();
  expect(bootstrapAttempts).toBe(2);
  expect(sdkLoads).toBe(1);
  await expectNoAxeViolations(page);
});

test('sign-in recovers when the GIS script fails to load', async ({ page }) => {
  let bootstrapAttempts = 0;
  let sdkLoads = 0;
  await page.route('**/api/v1/auth/google/bootstrap**', (route) => {
    bootstrapAttempts += 1;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(
        bootstrapResponse(String.fromCharCode(64 + bootstrapAttempts).repeat(43)),
      ),
    });
  });
  await page.route('https://accounts.google.com/gsi/client', (route) => {
    sdkLoads += 1;
    if (sdkLoads === 1) return route.abort('failed');
    return route.fulfill({
      contentType: 'application/javascript',
      body: `window.google = { accounts: { id: {
        initialize(configuration) { window.__novelGoogleConfiguration = configuration; },
        renderButton(parent) { parent.innerHTML = '<button aria-label="Sign in with Google">Sign in with Google</button>'; }
      } } };`,
    });
  });
  await page.goto('/sign-in');

  await expect(page.getByRole('status')).toContainText('Chưa tải được dịch vụ đăng nhập Google');
  await page.getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.getByRole('button', { name: 'Sign in with Google' })).toBeVisible();
  expect(bootstrapAttempts).toBe(2);
  expect(sdkLoads).toBe(2);
  await expectNoAxeViolations(page);
});

test('sign-in replaces an untrusted return path with the server default', async ({ page }) => {
  let attempts = 0;
  const untrustedPaths = [
    'https://attacker.example/callback',
    '/app/../outside',
    '/%2F%2Fattacker.example',
    '/app/%5C%5Coutside',
  ];
  await page.route('**/api/v1/auth/google/bootstrap**', (route) => {
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
    await expect(page.getByRole('status')).toContainText('Google chưa sẵn sàng');
  }
  expect(attempts).toBe(untrustedPaths.length);
});
