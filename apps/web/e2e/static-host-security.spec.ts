import { expect, test } from './support';

const nonce = 'S'.repeat(43);
const accessLogSentinel =
  process.env['STATIC_HOST_LOG_SENTINEL'] ?? 'static-host-access-log-sentinel-20261126';

test('static container applies Firebase CSP and proxies API same-origin', async ({ page }) => {
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

  await page.route('**/api/v1/auth/firebase/bootstrap**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        firebaseConfig: {
          apiKey: 'firebase-test-key-static-host-not-a-credential',
          authDomain: 'novel-static-test.firebaseapp.com',
          projectId: 'novel-static-test',
          appId: '1:123456789012:web:abcdef1234567890',
        },
        state: nonce,
        locale: 'vi',
        attemptExpiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
      }),
    }),
  );
  await page.route('https://novel-static-test.firebaseapp.com/**', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: '<!doctype html><title>Firebase Auth</title>',
    }),
  );

  const pageResponse = await page.goto('/sign-in');
  expect(pageResponse?.status()).toBe(200);
  const csp = pageResponse?.headers()['content-security-policy'] ?? '';
  expect(csp).toContain("default-src 'self'");
  expect(csp).toContain("form-action 'self'");
  expect(csp).toContain("frame-ancestors 'none'");
  expect(csp).toContain('https://apis.google.com');
  expect(csp).toContain('https://identitytoolkit.googleapis.com');
  expect(csp).toContain('https://novel-static-test.firebaseapp.com');
  expect(pageResponse?.headers()['x-content-type-options']).toBe('nosniff');
  expect(pageResponse?.headers()['x-frame-options']).toBe('DENY');
  expect(pageResponse?.headers()['referrer-policy']).toBe('strict-origin-when-cross-origin');

  await expect(page.getByRole('button', { name: 'Tiếp tục với Google' })).toBeEnabled();
  const appViolations = await page.evaluate(() => {
    const monitoredWindow = window as unknown as {
      __cspViolations: Array<{ directive: string; blockedURI: string }>;
    };
    return monitoredWindow.__cspViolations;
  });
  expect(appViolations).toEqual([]);

  const proxiedApi = await page.request.get(
    `/api/v1/static-host-probe?cursor=${encodeURIComponent(accessLogSentinel)}`,
  );
  expect(proxiedApi.status()).toBe(200);
  expect(proxiedApi.headers()['x-static-host-proxy']).toBe('reached-api');
  expect(proxiedApi.headers()['cache-control']).toBe('no-store');
  expect(proxiedApi.headers()['pragma']).toBe('no-cache');
  expect(await proxiedApi.json()).toEqual({
    proxied: true,
    path: `/api/v1/static-host-probe?cursor=${encodeURIComponent(accessLogSentinel)}`,
  });

  const invitationLanding = await page.request.get(
    `/app/invitations/${encodeURIComponent(accessLogSentinel)}`,
  );
  expect(invitationLanding.status()).toBe(200);
  expect(invitationLanding.headers()['content-type']).toContain('text/html');

  const proxiedApiError = await page.request.get('/api/v1/static-host-probe-error');
  expect(proxiedApiError.status()).toBe(403);
  expect(proxiedApiError.headers()['x-static-host-proxy']).toBe('reached-api');
  expect(proxiedApiError.headers()['cache-control']).toBe('no-store');
  expect(proxiedApiError.headers()['pragma']).toBe('no-cache');
  expect(proxiedApiError.headers()['content-type']).toContain('application/problem+json');
  expect(await proxiedApiError.json()).toMatchObject({ status: 403, code: 'FORBIDDEN' });

  const failedApiProxy = await page.request.get(
    `/api/v1/static-host-probe-upstream-failure?cursor=${encodeURIComponent(accessLogSentinel)}`,
  );
  expect(failedApiProxy.status()).toBe(502);

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
