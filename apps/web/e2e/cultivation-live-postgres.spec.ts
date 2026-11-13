import { expect, test } from './support';
import type { Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';

test('account Reader awards cultivation XP through the real API and PostgreSQL', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const workId = process.env['READER_LIVE_TEST_WORK_ID'];
  const chapterId = process.env['READER_LIVE_TEST_CHAPTER_ID'];

  test.skip(
    !sessionToken || !workId || !chapterId,
    'run tools/e2e/run-library-postgres.ps1 to supply an isolated Reader account and database',
  );
  expect(sessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(workId).toMatch(/^[0-9a-f-]{36}$/i);
  expect(chapterId).toMatch(/^[0-9a-f-]{36}$/i);

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
  await page.bringToFront();

  const systemStatusPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/system/status',
  );
  const initialProfilePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/me/cultivation',
  );
  await page.goto(`${baseUrl}/app/cultivation`);
  const [systemStatus, initialProfile] = await Promise.all([
    systemStatusPromise,
    initialProfilePromise,
  ]);
  expect(systemStatus.status()).toBe(200);
  expect((await systemStatus.json()).features.cultivationEnabled).toBe(true);
  expect(initialProfile.status()).toBe(200);
  expect((await initialProfile.json()).enabled).toBe(false);
  await expect(page.getByRole('button', { name: 'Bật hành trình' })).toBeVisible();

  const enableProfilePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname === '/api/v1/me/cultivation/settings',
  );
  await page.getByRole('button', { name: 'Bật hành trình' }).click();
  const enableProfile = await enableProfilePromise;
  expect(enableProfile.status()).toBe(200);
  expect((await enableProfile.json()).enabled).toBe(true);
  await expect(page.getByRole('heading', { name: 'Hành trình tu luyện đang bật' })).toBeVisible();

  const baseSessionPath = '/api/v1/me/reading-sessions';
  const heartbeatAwards: number[] = [];
  const heartbeatRequests: Array<{ sequence: number; active: boolean }> = [];
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith(':heartbeat')) {
      heartbeatRequests.push(request.postDataJSON() as { sequence: number; active: boolean });
    }
  });
  page.on('response', async (response: Response) => {
    if (
      response.request().method() !== 'POST' ||
      !new URL(response.url()).pathname.endsWith(':heartbeat') ||
      response.status() !== 200
    ) {
      return;
    }
    try {
      const body = (await response.json()) as { awardedXp: number };
      heartbeatAwards.push(body.awardedXp);
    } catch {
      // The main assertion reports a timeout if a successful award receipt never arrives.
    }
  });

  const startPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === baseSessionPath,
  );
  await page.goto(`${baseUrl}/app/reader/${workId}/${chapterId}`);
  await expect(page.getByRole('heading', { name: 'Live Reader Chapter' })).toBeVisible();
  await expect(
    page.getByText('Live E2E source text is served from PostgreSQL.', { exact: true }),
  ).toBeVisible();
  const start = await startPromise;
  expect(start.status()).toBe(201);
  const startBody = await start.json();
  expect(startBody.state).toBe('active');
  expect(start.request().headers()['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);

  await expect
    .poll(() => heartbeatRequests.length, { timeout: 40_000, intervals: [250, 500, 1000] })
    .toBeGreaterThanOrEqual(1);
  expect(heartbeatRequests[0]).toMatchObject({ sequence: 1, active: true });
  await expect
    .poll(() => heartbeatAwards.length, { timeout: 5_000, intervals: [250, 500, 1000] })
    .toBeGreaterThanOrEqual(1);
  expect(heartbeatAwards[0]).toBe(0);
  await expect
    .poll(() => heartbeatAwards.some((award) => award >= 1), {
      timeout: 50_000,
      intervals: [500, 1000, 2000],
    })
    .toBe(true);
  expect(heartbeatRequests.slice(0, 2)).toEqual([
    { sequence: 1, active: true },
    { sequence: 2, active: true },
  ]);

  const closePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname.startsWith(`${baseSessionPath}/`) &&
      new URL(response.url()).pathname.endsWith(':close'),
  );
  await page.getByRole('link', { name: 'Quay lại chi tiết tác phẩm' }).click();
  const close = await closePromise;
  expect(close.status()).toBe(200);
  expect((await close.json()).state).toBe('closed');

  const persistedProfilePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/me/cultivation',
  );
  await page.goto(`${baseUrl}/app/cultivation`);
  const persistedProfileResponse = await persistedProfilePromise;
  expect(persistedProfileResponse.status()).toBe(200);
  const persistedProfile = await persistedProfileResponse.json();
  expect(persistedProfile.enabled).toBe(true);
  expect(persistedProfile.totalXp).toBeGreaterThan(0);
  expect(persistedProfile.todayXpEarned).toBeGreaterThan(0);
});
