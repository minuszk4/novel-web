import { expect, test } from '@playwright/test';

test('production web reaches the PostgreSQL API through its same-origin proxy', async ({
  page,
}) => {
  const documentResponse = await page.goto('/sign-in');
  expect(documentResponse?.status()).toBe(200);
  expect(documentResponse?.headers()['content-security-policy']).toContain("default-src 'self'");

  const statusResponsePromise = page.waitForResponse(
    (response) => new URL(response.url()).searchParams.get('probe') === 'full-stack',
  );
  const browserResult = await page.evaluate(async () => {
    const response = await fetch('/api/v1/system/status?probe=full-stack');
    return {
      ok: response.ok,
      status: response.status,
      contentType: response.headers.get('content-type'),
      payload: await response.json(),
    };
  });
  const statusResponse = await statusResponsePromise;
  expect(statusResponse.request().resourceType()).not.toBe('document');
  expect(browserResult.ok).toBe(true);
  expect(browserResult.status).toBe(200);
  expect(browserResult.contentType).toContain('application/json');
  expect(browserResult.payload).toMatchObject({
    apiVersion: 'v1',
    environment: 'local-docker',
    integrations: {
      googleOidcEnabled: false,
      vnpayTestEnabled: false,
      vnpayEnvironment: 'TEST',
    },
    features: {
      commerceSandboxEnabled: false,
      publicationReleaseEnabled: false,
      publicationSchedulerApiEnabled: false,
      translationFakePreviewEnabled: false,
    },
  });
});
