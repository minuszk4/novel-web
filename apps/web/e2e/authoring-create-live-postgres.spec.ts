import { expect, test } from './support';

const baseUrl = 'http://localhost:4300';
const author = 'E2E Pen Name';
const language = 'vi';

test('Author Studio creates and replays a private draft through the real API and PostgreSQL', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const title = process.env['AUTHORING_LIVE_TEST_TITLE'];
  const screenshotPath = process.env['AUTHORING_LIVE_TEST_SCREENSHOT'];

  test.skip(
    !sessionToken || !title || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 to supply a disposable authoring account and database',
  );
  expect(sessionToken, 'run with the disposable PostgreSQL fixture harness').toMatch(
    /^[A-Za-z0-9_-]{43}$/,
  );
  expect(title).toMatch(/^Live E2E Authored Draft [0-9a-f-]{36}$/i);

  await page.setViewportSize({ width: 1280, height: 900 });
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

  const currentPolicyPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/policies/current',
  );
  const accountPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/me',
  );
  await page.goto(`${baseUrl}/app/authoring/new`);
  const [currentPolicy, account] = await Promise.all([currentPolicyPromise, accountPromise]);
  expect(currentPolicy.status()).toBe(200);
  expect(account.status()).toBe(200);
  expect((await account.json()).policy.requiredAcceptanceComplete).toBe(true);
  await expect(page.getByRole('heading', { name: 'Tác phẩm mới' })).toBeVisible();

  await page.getByLabel('Tên tác phẩm').fill(title!);
  await page.getByLabel('Bút danh').fill(author);
  await page.getByLabel('Ngôn ngữ sáng tác').fill(language);

  const csrfPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/csrf',
  );
  const createPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/authoring/manuscripts',
  );
  await page.getByRole('button', { name: 'Tạo bản thảo riêng tư' }).click();
  const [csrf, created] = await Promise.all([csrfPromise, createPromise]);
  expect(csrf.status()).toBe(200);
  expect(created.status()).toBe(201);

  const csrfBody = (await csrf.json()) as { token: string };
  const createdBody = (await created.json()) as {
    id: string;
    title: string;
    author: string;
    language: string;
    publicationState: string;
    visibility: string;
  };
  const createHeaders = created.request().headers();
  const requestBody = created.request().postDataJSON() as {
    title: string;
    author: string;
    language: string;
  };
  expect(createHeaders['x-csrf-token']).toBe(csrfBody.token);
  expect(createHeaders['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
  expect(requestBody).toEqual({ title: title!.trim(), author, language });
  expect(createdBody).toMatchObject({
    title,
    author,
    language,
    publicationState: 'draft',
    visibility: 'private',
  });
  expect(createdBody.id).toMatch(/^[0-9a-f-]{36}$/i);

  await expect(page.getByRole('heading', { name: title! })).toBeVisible();
  await expect(page.getByText(/chưa có màn hình soạn chương/)).toBeVisible();
  await expect(page.getByText(/chưa được xuất bản hay chia sẻ/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tạo bản thảo riêng tư' })).toHaveCount(0);

  const replay = await page.evaluate(
    async ({ token, key, body }) => {
      const response = await fetch('/api/v1/authoring/manuscripts', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-Token': token,
          'Idempotency-Key': key,
        },
        body: JSON.stringify(body),
      });
      return { status: response.status, body: await response.json() };
    },
    {
      token: csrfBody.token,
      key: createHeaders['idempotency-key']!,
      body: requestBody,
    },
  );
  expect(replay.status).toBe(201);
  expect(replay.body.id).toBe(createdBody.id);

  const conflictingReplay = await page.evaluate(
    async ({ token, key, body }) => {
      const response = await fetch('/api/v1/authoring/manuscripts', {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-CSRF-Token': token,
          'Idempotency-Key': key,
        },
        body: JSON.stringify(body),
      });
      return { status: response.status, body: await response.json() };
    },
    {
      token: csrfBody.token,
      key: createHeaders['idempotency-key']!,
      body: { ...requestBody, title: `${title} altered` },
    },
  );
  expect(conflictingReplay.status).toBe(409);
  expect(conflictingReplay.body.code).toBe('IDEMPOTENCY_KEY_REUSED');

  const documentWidths = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(documentWidths.scroll).toBeLessThanOrEqual(documentWidths.client);
  await page.screenshot({ path: screenshotPath!, fullPage: true });

  const authoringResponse = apiResponses.find(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/authoring/manuscripts',
  );
  expect(authoringResponse?.status()).toBe(201);
});
