import { expect, test } from './support';
import type { Response } from '@playwright/test';
import { writeFile } from 'node:fs/promises';

const baseUrl = 'http://localhost:4300';

test('account Reader awards cultivation XP through the real API and PostgreSQL', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const workId = process.env['READER_LIVE_TEST_WORK_ID'];
  const chapterId = process.env['READER_LIVE_TEST_CHAPTER_ID'];
  const rankUpJourney = process.env['CULTIVATION_LIVE_TEST_RANK_UP'] === 'true';
  const correlationIds: string[] = [];
  const rememberCorrelationId = (value: string | undefined): string | undefined => {
    if (value) correlationIds.push(value);
    return value;
  };

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
  rememberCorrelationId(systemStatus.headers()['x-correlation-id']);
  rememberCorrelationId(initialProfile.headers()['x-correlation-id']);
  expect(systemStatus.status()).toBe(200);
  expect((await systemStatus.json()).features.cultivationEnabled).toBe(true);
  expect(initialProfile.status()).toBe(200);
  const initialProfileBody = await initialProfile.json();
  expect(initialProfileBody.enabled).toBe(rankUpJourney);
  if (rankUpJourney) {
    expect(initialProfileBody.totalXp).toBe(99);
    expect(initialProfileBody.todayXpEarned).toBe(99);
    expect(initialProfileBody.currentRank).toMatchObject({ key: 'mortal', name: 'Phàm nhân' });
    expect(initialProfileBody.nextRank).toMatchObject({ key: 'qi-1', name: 'Luyện Khí tầng 1' });
    expect(initialProfileBody.xpToNextRank).toBe(1);
    await expect(page.getByRole('heading', { name: 'Phàm nhân' })).toBeVisible();
  } else {
    await expect(page.getByRole('button', { name: 'Bật hành trình' })).toBeVisible();

    const enableProfilePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === '/api/v1/me/cultivation/settings',
    );
    await page.getByRole('button', { name: 'Bật hành trình' }).click();
    const enableProfile = await enableProfilePromise;
    rememberCorrelationId(enableProfile.headers()['x-correlation-id']);
    expect(enableProfile.status()).toBe(200);
    expect((await enableProfile.json()).enabled).toBe(true);
  }
  await expect(page.getByRole('heading', { name: 'Hành trình tu luyện đang bật' })).toBeVisible();

  const baseSessionPath = '/api/v1/me/reading-sessions';
  const heartbeatAwards: number[] = [];
  const heartbeatRequests: Array<{
    sequence: number;
    active: boolean;
    idempotencyKey: string | undefined;
  }> = [];
  const heartbeatClientStatuses: number[] = [];
  const heartbeatBackendResponses: Array<{
    sequence: number;
    active: boolean;
    idempotencyKey: string | undefined;
    status: number;
    replayed: boolean;
    awardedXp: number;
    totalActiveSeconds: number;
    correlationId: string | undefined;
  }> = [];
  let loseFirstCommittedHeartbeatResponse = true;

  await page.route('**/api/v1/me/reading-sessions/**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (request.method() !== 'POST' || !pathname.endsWith(':heartbeat')) {
      await route.continue();
      return;
    }

    // Let the real API commit to PostgreSQL, then drop only the response seen by the browser.
    const upstream = await route.fetch();
    const requestBody = request.postDataJSON() as { sequence: number; active: boolean };
    const heartbeatBody = (await upstream.json()) as {
      awardedXp: number;
      totalActiveSeconds: number;
    };
    const correlationId = rememberCorrelationId(upstream.headers()['x-correlation-id']);
    heartbeatBackendResponses.push({
      ...requestBody,
      idempotencyKey: request.headers()['idempotency-key'],
      status: upstream.status(),
      replayed: upstream.headers()['idempotency-replayed'] === 'true',
      awardedXp: heartbeatBody.awardedXp,
      totalActiveSeconds: heartbeatBody.totalActiveSeconds,
      correlationId,
    });

    if (loseFirstCommittedHeartbeatResponse) {
      loseFirstCommittedHeartbeatResponse = false;
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'SIMULATED_RESPONSE_LOST' }),
      });
      return;
    }

    await route.fulfill({ response: upstream });
  });

  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith(':heartbeat')) {
      const body = request.postDataJSON() as { sequence: number; active: boolean };
      heartbeatRequests.push({
        ...body,
        idempotencyKey: request.headers()['idempotency-key'],
      });
    }
  });
  page.on('response', async (response: Response) => {
    if (
      response.request().method() !== 'POST' ||
      !new URL(response.url()).pathname.endsWith(':heartbeat')
    ) {
      return;
    }
    heartbeatClientStatuses.push(response.status());
    if (response.status() !== 200) return;
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
  rememberCorrelationId(start.headers()['x-correlation-id']);
  expect(start.status()).toBe(201);
  const startBody = await start.json();
  expect(startBody.state).toBe('active');
  expect(start.request().headers()['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);

  await expect
    .poll(() => heartbeatRequests.length, { timeout: 40_000, intervals: [250, 500, 1000] })
    .toBeGreaterThanOrEqual(2);
  expect(heartbeatRequests[0]).toMatchObject({ sequence: 1, active: true });
  expect(heartbeatRequests[1]).toEqual(heartbeatRequests[0]);
  await expect.poll(() => heartbeatBackendResponses.length).toBeGreaterThanOrEqual(2);
  expect(heartbeatBackendResponses[0]).toMatchObject({
    sequence: 1,
    active: true,
    status: 200,
    replayed: false,
    awardedXp: 0,
  });
  const firstHeartbeatBackendResponse = heartbeatBackendResponses[0];
  expect(heartbeatBackendResponses[1]).toMatchObject({
    sequence: firstHeartbeatBackendResponse?.sequence,
    active: firstHeartbeatBackendResponse?.active,
    idempotencyKey: firstHeartbeatBackendResponse?.idempotencyKey,
    status: 200,
    replayed: true,
    awardedXp: firstHeartbeatBackendResponse?.awardedXp,
    totalActiveSeconds: firstHeartbeatBackendResponse?.totalActiveSeconds,
  });
  expect(heartbeatBackendResponses[1]?.correlationId).not.toBe(
    heartbeatBackendResponses[0]?.correlationId,
  );
  await expect.poll(() => heartbeatClientStatuses.length).toBeGreaterThanOrEqual(2);
  expect(heartbeatClientStatuses.slice(0, 2)).toEqual([503, 200]);
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
  expect(heartbeatRequests[2]).toMatchObject({ sequence: 2, active: true });
  expect(heartbeatRequests[2]?.idempotencyKey).not.toBe(heartbeatRequests[0]?.idempotencyKey);

  const closePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname.startsWith(`${baseSessionPath}/`) &&
      new URL(response.url()).pathname.endsWith(':close'),
  );
  await page.getByRole('link', { name: 'Quay lại chi tiết tác phẩm' }).click();
  const close = await closePromise;
  rememberCorrelationId(close.headers()['x-correlation-id']);
  expect(close.status()).toBe(200);
  expect((await close.json()).state).toBe('closed');

  const persistedProfilePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/me/cultivation',
  );
  await page.goto(`${baseUrl}/app/cultivation`);
  const persistedProfileResponse = await persistedProfilePromise;
  rememberCorrelationId(persistedProfileResponse.headers()['x-correlation-id']);
  expect(persistedProfileResponse.status()).toBe(200);
  const persistedProfile = await persistedProfileResponse.json();
  expect(persistedProfile.enabled).toBe(true);
  if (rankUpJourney) {
    expect(persistedProfile.totalXp).toBe(100);
    expect(persistedProfile.todayXpEarned).toBe(100);
    expect(persistedProfile.currentRank).toMatchObject({ key: 'qi-1', name: 'Luyện Khí tầng 1' });
    expect(persistedProfile.nextRank).toMatchObject({ key: 'qi-2', name: 'Luyện Khí tầng 2' });
    expect(persistedProfile.xpToNextRank).toBe(100);
    await expect(page.getByRole('heading', { name: 'Luyện Khí tầng 1' })).toBeVisible();
    const screenshotPath = process.env['CULTIVATION_LIVE_TEST_SCREENSHOT'];
    if (screenshotPath) await page.screenshot({ path: screenshotPath, fullPage: true });
    expect(correlationIds.length).toBeGreaterThanOrEqual(7);
    expect(
      correlationIds.every((id) =>
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id),
      ),
    ).toBe(true);
    expect(new Set(correlationIds).size).toBe(correlationIds.length);
    const tracePath = process.env['CULTIVATION_LIVE_TEST_TRACE'];
    if (tracePath) {
      await writeFile(
        tracePath,
        `${JSON.stringify(
          {
            rankBefore: {
              totalXp: initialProfileBody.totalXp,
              rank: initialProfileBody.currentRank.name,
              nextRank: initialProfileBody.nextRank.name,
            },
            rankAfter: {
              totalXp: persistedProfile.totalXp,
              rank: persistedProfile.currentRank.name,
              nextRank: persistedProfile.nextRank.name,
            },
            heartbeatResponses: heartbeatBackendResponses.map(
              ({ sequence, status, replayed, awardedXp, totalActiveSeconds, correlationId }) => ({
                sequence,
                status,
                replayed,
                awardedXp,
                totalActiveSeconds,
                correlationId,
              }),
            ),
            correlationIds,
          },
          null,
          2,
        )}\n`,
        'utf8',
      );
    }
  } else {
    expect(persistedProfile.totalXp).toBeGreaterThan(0);
    expect(persistedProfile.todayXpEarned).toBeGreaterThan(0);
  }
});
