import { expect, test } from './support';

const baseUrl = 'http://localhost:4300';

test('account library renders data from the real API and PostgreSQL session', async ({ page }) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const libraryId = process.env['LIBRARY_LIVE_TEST_LIBRARY_ID'];
  const workIds = (process.env['LIBRARY_LIVE_TEST_WORK_IDS'] ?? '').split(',').filter(Boolean);
  const screenshotPath = process.env['LIBRARY_LIVE_TEST_SCREENSHOT'];
  const authoredDraftTitle = process.env['AUTHORING_LIVE_TEST_TITLE'];
  const expectedSecondWorkTitle =
    process.env['AUTHORING_OUTLINE_LIVE_TEST_EXPECTED_TITLE'] ?? 'Live E2E Novel Two';

  test.skip(
    !sessionToken || !libraryId || workIds.length !== 2 || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 to supply an isolated real API/database fixture',
  );
  expect(sessionToken, 'run with the disposable PostgreSQL fixture harness').toMatch(
    /^[A-Za-z0-9_-]{43}$/,
  );
  expect(libraryId).toMatch(/^[0-9a-f-]{36}$/i);
  expect(workIds).toHaveLength(2);
  expect(screenshotPath).toBeTruthy();

  await page.context().addCookies([
    {
      name: '__Host-novel_session',
      value: sessionToken!,
      domain: 'localhost',
      path: '/',
      httpOnly: true,
      secure: true,
      sameSite: 'Lax',
    },
  ]);

  const apiResponses: import('@playwright/test').Response[] = [];
  page.on('response', (response) => {
    if (new URL(response.url()).pathname.startsWith('/api/v1/')) apiResponses.push(response);
  });

  await page.goto(`${baseUrl}/app/library`);
  await expect(page).toHaveURL(/\/app\/library$/);
  await expect(page.getByText('Bộ sưu tập đã được tải từ tài khoản.')).toBeVisible();
  await expect(page.locator('.demo-user strong')).toHaveText('Live E2E Reader');
  await expect(page.locator('.demo-user small')).toHaveText('Tài khoản server');
  await expect(page.locator('.topbar-avatar')).toHaveText('L');
  await expect(page.getByText('Thư viện tài khoản được tải qua API')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Live E2E Novel One' })).toBeVisible();
  await expect(page.getByRole('heading', { name: expectedSecondWorkTitle })).toBeVisible();
  await expect(page.getByText('RIÊNG TƯ · TÀI KHOẢN')).toHaveCount(authoredDraftTitle ? 3 : 2);
  if (authoredDraftTitle) {
    await expect(page.getByRole('heading', { name: authoredDraftTitle })).toBeVisible();
  }
  await expect(page.getByLabel('Trạng thái đọc: Live E2E Novel One')).toHaveValue('want-to-read');
  await expect(page.getByLabel(`Trạng thái đọc: ${expectedSecondWorkTitle}`)).toHaveValue(
    'want-to-read',
  );

  const responseFor = (path: string) =>
    apiResponses.find(
      (response) =>
        response.request().method() === 'GET' && new URL(response.url()).pathname === path,
    );
  const requiredPaths = [
    '/api/v1/auth/me',
    '/api/v1/auth/policies/current',
    '/api/v1/auth/policies/terms/terms-live-e2e-v1',
    '/api/v1/auth/policies/privacy-notice/privacy-live-e2e-v1',
    '/api/v1/libraries',
    '/api/v1/works',
    '/api/v1/me/library/collections',
    ...workIds.map((workId) => `/api/v1/me/works/${workId}/library-state`),
  ];
  for (const path of requiredPaths) {
    const response = responseFor(path);
    expect(response, `expected a real API response for ${path}`).toBeDefined();
    expect(response!.status(), `${path} should succeed without an API stub`).toBe(200);
  }

  const librariesResponse = responseFor('/api/v1/libraries')!;
  const librariesBody = (await librariesResponse.json()) as {
    items: Array<{ id: string; name: string }>;
  };
  expect(librariesBody.items).toEqual(
    expect.arrayContaining([expect.objectContaining({ id: libraryId, name: 'Live E2E Library' })]),
  );

  const worksResponse = responseFor('/api/v1/works')!;
  const worksBody = (await worksResponse.json()) as { items: Array<{ id: string; title: string }> };
  expect(worksBody.items).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ id: workIds[0], title: 'Live E2E Novel One' }),
      expect.objectContaining({ id: workIds[1], title: expectedSecondWorkTitle }),
    ]),
  );

  const otherSessionToken = process.env['MOMO_COMMERCE_LIVE_TEST_OTHER_SESSION_TOKEN'];
  if (otherSessionToken) {
    expect(otherSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    const ownerCsrfResponse = await page.request.get(`${baseUrl}/api/v1/auth/csrf`);
    expect(ownerCsrfResponse.status()).toBe(200);
    const ownerCsrf = (await ownerCsrfResponse.json()) as { token: string };
    const createCollection = await page.request.post(`${baseUrl}/api/v1/me/library/collections`, {
      headers: { 'X-CSRF-Token': ownerCsrf.token },
      data: { name: 'Private E2E Owner Collection' },
    });
    expect(createCollection.status()).toBe(201);
    expect(createCollection.headers()['cache-control']).toContain('no-store');
    const ownerCollection = (await createCollection.json()) as { id: string; name: string };
    expect(ownerCollection.name).toBe('Private E2E Owner Collection');

    await page.context().clearCookies();
    await page.context().addCookies([
      {
        name: '__Host-novel_session',
        value: otherSessionToken,
        domain: 'localhost',
        path: '/',
        httpOnly: true,
        secure: true,
        sameSite: 'Lax',
      },
    ]);
    const outsiderCsrfResponse = await page.request.get(`${baseUrl}/api/v1/auth/csrf`);
    expect(outsiderCsrfResponse.status()).toBe(200);
    const outsiderCsrf = (await outsiderCsrfResponse.json()) as { token: string };
    const foreignDelete = await page.request.delete(
      `${baseUrl}/api/v1/me/library/collections/${ownerCollection.id}`,
      { headers: { 'X-CSRF-Token': outsiderCsrf.token } },
    );
    expect(foreignDelete.status()).toBe(404);
    expect(foreignDelete.headers()['cache-control']).toContain('no-store');
    expect(foreignDelete.headers().pragma).toBe('no-cache');
    const foreignDeleteBody = await foreignDelete.text();
    expect(foreignDeleteBody).toContain('NOT_FOUND');
    expect(foreignDeleteBody).not.toContain(ownerCollection.name);

    await page.context().clearCookies();
    await page.context().addCookies([
      {
        name: '__Host-novel_session',
        value: sessionToken!,
        domain: 'localhost',
        path: '/',
        httpOnly: true,
        secure: true,
        sameSite: 'Lax',
      },
    ]);
    const ownerCollections = await page.request.get(`${baseUrl}/api/v1/me/library/collections`);
    expect(ownerCollections.status()).toBe(200);
    expect(ownerCollections.headers()['cache-control']).toContain('no-store');
    expect(await ownerCollections.json()).toEqual(
      expect.arrayContaining([expect.objectContaining(ownerCollection)]),
    );
  }

  await page.screenshot({ path: screenshotPath!, fullPage: true });
});
