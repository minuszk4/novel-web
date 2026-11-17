import { expect, expectNoAxeViolations, test } from './support';
import type { Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';
const sessionCookie = '__Host-novel_session';

test('owner-confirmed chapter release reaches the real inbox and read state persists', async ({
  page,
}) => {
  const ownerSessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const reviewerSessionToken = process.env['PUBLICATION_REVIEW_LIVE_TEST_REVIEWER_SESSION_TOKEN'];
  const screenshotPath = process.env['NOTIFICATION_LIVE_TEST_SCREENSHOT'];
  const inboxPath = '/api/v1/me/notifications';

  test.skip(
    !ownerSessionToken || !reviewerSessionToken || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 -IncludeNotificationInbox for a released chapter and follower fixture',
  );
  expect(ownerSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(reviewerSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);

  await page.setViewportSize({ width: 360, height: 800 });
  const addSession = async (value: string) => {
    await page.context().clearCookies({ name: sessionCookie });
    await page.context().addCookies([
      {
        name: sessionCookie,
        value,
        domain: 'localhost',
        path: '/',
        httpOnly: true,
        secure: true,
        sameSite: 'Lax',
      },
    ]);
  };

  await addSession(ownerSessionToken!);

  let storedNotification: Record<string, unknown> | null = null;
  let unreadCount: number | undefined;
  let initialResponse: Response | null = null;
  let latestInboxBody = '(no inbox response received)';
  await expect
    .poll(
      async () => {
        const response = await page.request.get(`${baseUrl}${inboxPath}`);
        initialResponse = response;
        const responseText = await response.text();
        latestInboxBody = `HTTP ${response.status()}: ${responseText}`;
        if (!response.ok()) return latestInboxBody;
        const body = JSON.parse(responseText) as {
          items?: Array<Record<string, unknown>>;
          unreadCount?: number;
        };
        unreadCount = body.unreadCount;
        storedNotification =
          body.items?.find((item) => item['chapterTitle'] === 'Live E2E Staged Chapter') ?? null;
        return responseText;
      },
      {
        timeout: 30_000,
        intervals: [250, 500, 1_000],
        message: `Wait for the owner-confirmed release notification. Last inbox response: ${latestInboxBody}`,
      },
    )
    .toContain('"chapterTitle":"Live E2E Staged Chapter"');

  expect(initialResponse?.status()).toBe(200);
  expect(unreadCount).toBe(1);
  expect(initialResponse?.headers()['cache-control']).toContain('no-store');
  expect(initialResponse?.headers()['pragma']).toBe('no-cache');
  expect(storedNotification).toMatchObject({
    eventType: 'CHAPTER_PUBLISHED',
    available: true,
    workTitle: 'Live E2E Novel Two',
    chapterTitle: 'Live E2E Staged Chapter',
    chapterOrdinal: 2,
    readAt: null,
  });
  expect(JSON.stringify(storedNotification)).not.toContain(
    'A privately staged chapter, released only after review.',
  );

  const browserInboxResponses: Response[] = [];
  page.on('response', (response) => {
    if (new URL(response.url()).pathname === inboxPath) browserInboxResponses.push(response);
  });
  await page.goto('/app/notifications');
  await expect(page.getByRole('heading', { name: 'Thông báo' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Live E2E Staged Chapter' })).toBeVisible();
  await expect(page.getByText('Live E2E Novel Two · Chương 2')).toBeVisible();
  await expect(page.getByText('1 thông báo chưa đọc', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Thông báo 1, 1 chưa đọc' })).toBeVisible();
  await expectNoAxeViolations(page);
  await page.screenshot({ path: screenshotPath!, fullPage: true });

  const readResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname.endsWith('/read'),
  );
  await page.getByRole('button', { name: 'Đánh dấu đã đọc: Live E2E Staged Chapter' }).click();
  const readReceipt = await readResponse;
  expect(readReceipt.status()).toBe(200);
  expect(readReceipt.headers()['cache-control']).toContain('no-store');
  expect(readReceipt.request().headers()['x-csrf-token']).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(await readReceipt.json()).toMatchObject({
    notificationId: storedNotification?.['notificationId'],
  });
  await expect(page.getByText('Đã đọc', { exact: true })).toBeVisible();
  await expect(page.getByText('0 thông báo chưa đọc', { exact: true })).toBeVisible();

  const refreshedPage = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === inboxPath,
  );
  await page.reload();
  expect((await refreshedPage).status()).toBe(200);
  await expect(page.getByText('Đã đọc', { exact: true })).toBeVisible();
  await expect(page.getByText('0 thông báo chưa đọc', { exact: true })).toBeVisible();
  expect(browserInboxResponses.some((response) => response.status() === 200)).toBe(true);

  await page.goto('/app/library');
  await expect(page.getByRole('heading', { name: 'Live E2E Novel Two' })).toBeVisible();
  await expect(page.getByText('CÔNG KHAI · TÀI KHOẢN', { exact: true })).toBeVisible();

  await addSession(reviewerSessionToken!);
  const otherActorList = await page.request.get(`${baseUrl}${inboxPath}`);
  expect(otherActorList.status()).toBe(200);
  expect(otherActorList.headers()['cache-control']).toContain('no-store');
  expect(await otherActorList.json()).toMatchObject({ items: [], unreadCount: 0 });

  const otherActorCsrf = await page.request.get(`${baseUrl}/api/v1/auth/csrf`);
  expect(otherActorCsrf.status()).toBe(200);
  const otherActorCsrfToken = (await otherActorCsrf.json()) as { token: string };
  const foreignRead = await page.request.post(
    `${baseUrl}${inboxPath}/${storedNotification?.['notificationId']}/read`,
    { headers: { 'X-CSRF-Token': otherActorCsrfToken.token } },
  );
  expect(foreignRead.status()).toBe(404);
  expect(await foreignRead.json()).toMatchObject({ code: 'NOTIFICATION_NOT_FOUND' });
});
