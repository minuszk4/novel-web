import { expect, test } from './support';

const nonce = 'S'.repeat(43);

test('static container applies CSP, supports Google GIS and proxies API same-origin', async ({
  page,
}) => {
  await page.addInitScript(() => {
    const monitoredWindow = window as unknown as {
      __cspViolations: Array<{ directive: string; blockedURI: string }>;
    };
    monitoredWindow.__cspViolations = [];
    document.addEventListener('securitypolicyviolation', (event) => {
      monitoredWindow.__cspViolations.push({
        directive: event.effectiveDirective,
        blockedURI: event.blockedURI,
      });
    });
  });

  await page.route('**/api/v1/auth/google/bootstrap**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        clientId: 'novel-static-test.apps.googleusercontent.com',
        loginUri: new URL(
          '/api/v1/auth/google/credential',
          process.env['GOOGLE_CALLBACK_ORIGIN'] ?? 'https://api.example.test',
        ).href,
        nonce,
        locale: 'vi',
        attemptExpiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      }),
    }),
  );
  await page.route('https://accounts.google.com/gsi/client', (route) =>
    route.fulfill({
      contentType: 'application/javascript',
      body: `window.google = { accounts: { id: {
        initialize() {},
        renderButton(parent) {
          const button = document.createElement('button');
          button.type = 'button';
          button.setAttribute('aria-label', 'Sign in with Google');
          button.textContent = 'Sign in with Google';
          parent.append(button);
        }
      } } };`,
    }),
  );

  const pageResponse = await page.goto('/sign-in');
  expect(pageResponse?.status()).toBe(200);
  const csp = pageResponse?.headers()['content-security-policy'] ?? '';
  expect(csp).toContain("default-src 'self'");
  expect(csp).toContain("form-action 'self' https://api.example.test");
  expect(csp).toContain("frame-ancestors 'none'");
  expect(csp).toContain('https://accounts.google.com/gsi/client');
  expect(csp).toContain('https://accounts.google.com/gsi/');
  expect(pageResponse?.headers()['x-content-type-options']).toBe('nosniff');
  expect(pageResponse?.headers()['x-frame-options']).toBe('DENY');
  expect(pageResponse?.headers()['referrer-policy']).toBe('strict-origin-when-cross-origin');

  await expect(page.getByRole('button', { name: 'Sign in with Google' })).toBeVisible();
  const appViolations = await page.evaluate(() => {
    const monitoredWindow = window as unknown as {
      __cspViolations: Array<{ directive: string; blockedURI: string }>;
    };
    return monitoredWindow.__cspViolations;
  });
  expect(appViolations).toEqual([]);

  const proxiedApi = await page.request.get('/api/v1/static-host-probe');
  expect(proxiedApi.status()).toBe(200);
  expect(proxiedApi.headers()['x-static-host-proxy']).toBe('reached-api');
  expect(await proxiedApi.json()).toEqual({ proxied: true, path: '/api/v1/static-host-probe' });

  const enforcement = await page.evaluate(async () => {
    const inlineScript = document.createElement('script');
    inlineScript.textContent = 'window.__unexpectedInlineScript = true';
    document.head.append(inlineScript);

    const externalForm = document.createElement('form');
    externalForm.method = 'post';
    externalForm.action = 'https://collector.invalid/collect';
    document.body.append(externalForm);
    externalForm.requestSubmit();
    await new Promise((resolve) => window.setTimeout(resolve, 50));

    const monitoredWindow = window as unknown as {
      __cspViolations: Array<{ directive: string; blockedURI: string }>;
      __unexpectedInlineScript?: boolean;
    };
    return {
      inlineRan: monitoredWindow.__unexpectedInlineScript === true,
      violations: monitoredWindow.__cspViolations,
      url: window.location.href,
    };
  });

  expect(enforcement.inlineRan).toBe(false);
  expect(enforcement.violations).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ directive: 'script-src-elem', blockedURI: 'inline' }),
      expect.objectContaining({
        directive: 'form-action',
        blockedURI: 'https://collector.invalid/collect',
      }),
    ]),
  );
  expect(enforcement.url).toContain('/sign-in');

  const spaRefresh = await page.request.get('/sign-in');
  expect(spaRefresh.status()).toBe(200);
  expect(spaRefresh.headers()['content-type']).toContain('text/html');
});
