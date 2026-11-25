import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const notificationId = '11111111-1111-4111-8111-111111111111';
const workId = '22222222-2222-4222-8222-222222222222';
const chapterId = '33333333-3333-4333-8333-333333333333';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const readerAccount = {
  user: {
    id: 'notifications-reader',
    displayName: 'Độc giả',
    contactEmail: 'reader@example.test',
    avatarUrl: null,
    status: 'ACTIVE',
  },
  identityProviders: ['google'],
  policy: {
    termsAcceptedVersion: 'terms-1',
    privacyNoticeAcknowledgedVersion: 'privacy-1',
    requiredAcceptanceComplete: true,
  },
  roles: [],
  features: {
    privateLibraryEnabled: true,
    importEnabled: false,
    readerEnabled: true,
    commerceEnabled: false,
    cultivationEnabled: false,
  },
};

const chapterNotification = {
  notificationId,
  eventType: 'CHAPTER_PUBLISHED',
  createdAt: '2026-11-18T10:00:00Z',
  readAt: null,
  available: true,
  workId,
  workTitle: 'Tác phẩm công khai',
  chapterId,
  chapterTitle: 'Chương mới',
  chapterOrdinal: 5,
};

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installAcceptedAccountRoutes(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: 'v1',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled: false,
        vnpayEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: false,
        translationFakePreviewEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route('**/api/v1/auth/me', (route) => json(route, readerAccount));
  await page.route('**/api/v1/auth/policies/current', (route) =>
    json(route, {
      terms: {
        version: 'terms-1',
        effectiveAt: '2026-11-01T00:00:00Z',
        documentUrl: '/api/v1/auth/policies/terms/terms-1',
      },
      privacyNotice: {
        version: 'privacy-1',
        effectiveAt: '2026-11-01T00:00:00Z',
        documentUrl: '/api/v1/auth/policies/privacy-notice/privacy-1',
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/terms/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Terms' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Privacy' }),
  );
}

test('inbox updates an unread notification and announces the capped header count', async ({
  page,
}) => {
  await installAcceptedAccountRoutes(page);
  let unreadCount = 125;
  let read = false;
  await page.route('**/api/v1/me/notifications**', async (route) => {
    const request = route.request();
    if (request.method() === 'POST') {
      read = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
        body: JSON.stringify({ notificationId, readAt: '2026-11-18T10:01:00Z' }),
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
      body: JSON.stringify({
        items: [{ ...chapterNotification, readAt: read ? '2026-11-18T10:01:00Z' : null }],
        nextCursor: null,
        unreadCount,
      }),
    });
  });

  await page.goto('/app/notifications');
  const bell = page.getByRole('link', { name: 'Thông báo 99+, 125 chưa đọc' });
  await expect(bell).toBeVisible();
  await expect(bell.locator('.notification-badge')).toHaveText('99+');
  await expect(page.getByRole('heading', { name: 'Chương mới' })).toBeVisible();
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Đánh dấu đã đọc: Chương mới' }).click();
  unreadCount -= 1;
  await expect(page.locator('.notifications-status')).toContainText(
    'Đã đánh dấu thông báo là đã đọc.',
  );
  await expect(page.getByText('Đã đọc', { exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Thông báo 99+, 124 chưa đọc' })).toBeVisible();
});

test('withdrawn update stays neutral and notification controls reflow at 200% zoom', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installAcceptedAccountRoutes(page);
  await page.route('**/api/v1/me/notifications**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
      body: JSON.stringify({
        items: [
          {
            ...chapterNotification,
            eventType: 'UPDATE_UNAVAILABLE',
            available: false,
            workId: null,
            workTitle: null,
            chapterId: null,
            chapterTitle: null,
            chapterOrdinal: null,
          },
        ],
        nextCursor: null,
        unreadCount: 1,
      }),
    }),
  );

  await page.goto('/app/notifications');
  await expect(page.getByText('Cập nhật không khả dụng')).toBeVisible();
  await expect(page.getByText('Tác phẩm công khai')).toHaveCount(0);
  await expect(page.getByText('Chương mới')).toHaveCount(0);
  await expectNoAxeViolations(page);

  await page.evaluate(() => {
    document.documentElement.style.zoom = '200%';
  });
  await expect(page.getByRole('heading', { name: 'Thông báo' })).toBeVisible();
  await expect(page.getByRole('button', { name: /Đánh dấu đã đọc/ })).toBeVisible();
  const dimensions = await page.evaluate(() => ({
    viewport: window.innerWidth,
    content: document.documentElement.scrollWidth,
  }));
  expect(dimensions.content).toBeLessThanOrEqual(dimensions.viewport);
});

test('loads the next inbox cursor and completes bounded read-all batches', async ({ page }) => {
  await installAcceptedAccountRoutes(page);
  const secondNotificationId = '44444444-4444-4444-8444-444444444444';
  let readAllCalls = 0;
  await page.route('**/api/v1/me/notifications**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'POST' && url.pathname.endsWith('/read-all')) {
      readAllCalls += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
        body: JSON.stringify({ updatedCount: 1, hasMore: readAllCalls === 1 }),
      });
      return;
    }
    const secondPage = url.searchParams.has('cursor');
    const item = secondPage
      ? {
          ...chapterNotification,
          notificationId: secondNotificationId,
          chapterTitle: 'Chương kế tiếp',
        }
      : chapterNotification;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
      body: JSON.stringify({
        items: [item],
        nextCursor: secondPage ? null : 'next-page-cursor',
        unreadCount: 2,
      }),
    });
  });

  await page.goto('/app/notifications');
  await expect(page.getByRole('heading', { name: 'Chương mới' })).toBeVisible();
  const loadMore = page.getByRole('button', { name: 'Tải thêm thông báo' });
  await loadMore.click();
  await expect(page.getByRole('heading', { name: 'Chương kế tiếp' })).toBeVisible();
  await page.getByRole('button', { name: 'Đánh dấu tất cả đã đọc' }).click();

  await expect(page.locator('.notifications-status')).toContainText(
    'Đã đánh dấu 2 thông báo là đã đọc.',
  );
  await expect(page.getByRole('button', { name: 'Đánh dấu tất cả đã đọc' })).toHaveCount(0);
  expect(readAllCalls).toBe(2);
});
