import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const orderId = '11111111-1111-4111-8111-111111111111';
const account = {
  user: {
    id: 'commerce-history-user',
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

const order = {
  id: orderId,
  packId: 'momo-test-credits-100',
  packVersion: 1,
  provider: 'MOMO',
  environment: 'TEST',
  currency: 'VND',
  amountVnd: 1000,
  paidCreditsPurchased: 100,
  bonusCreditsPurchased: 0,
  paidExpiresAt: null,
  bonusExpiresAt: null,
  paidCreditsGranted: 0,
  bonusCreditsGranted: 0,
  status: 'PENDING',
  createdAt: '2026-11-15T10:00:00Z',
  updatedAt: '2026-11-15T10:00:00Z',
};

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installAccountRoutes(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: 'v1',
      environment: 'test',
      integrations: { googleOidcEnabled: false, momoTestEnabled: true, momoEnvironment: 'TEST' },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: true,
        translationFakePreviewEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, { token: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }),
  );
  await page.route('**/api/v1/auth/me', (route) => json(route, account));
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
  await page.route('**/api/v1/me/credits/balance', (route) =>
    json(route, {
      paidCredits: 10,
      bonusCredits: 0,
      totalCredits: 10,
      asOf: '2026-11-15T10:00:00Z',
    }),
  );
}

const viewports = [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`commerce history keeps the three lists distinct at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccountRoutes(page);
    const orderStateRequests: Array<string | null> = [];
    await page.route('**/api/v1/me/payment-orders**', (route) => {
      orderStateRequests.push(new URL(route.request().url()).searchParams.get('state'));
      return json(route, { items: [order], nextCursor: null });
    });
    await page.route('**/api/v1/me/credits/transactions**', (route) =>
      json(route, {
        items: [
          {
            id: '22222222-2222-4222-8222-222222222222',
            entryType: 'UNLOCK_DEBIT',
            bucket: 'PAID',
            amountDelta: -10,
            expiresAt: null,
            sourceType: 'CHAPTER_UNLOCK',
            occurredAt: '2026-11-15T10:05:00Z',
          },
        ],
        nextCursor: null,
      }),
    );
    await page.route('**/api/v1/me/purchases**', (route) =>
      json(route, {
        items: [
          {
            entitlementId: '33333333-3333-4333-8333-333333333333',
            chapterId: '44444444-4444-4444-8444-444444444444',
            workId: '55555555-5555-4555-8555-555555555555',
            workTitle: 'Gió qua núi',
            chapterTitle: 'Qua khỏi dốc',
            entitlementStatus: 'GRANTED',
            priceVersion: 1,
            creditsSpent: 10,
            grantedAt: '2026-11-15T10:05:00Z',
            currentReadAccess: 'READABLE',
          },
          {
            entitlementId: '66666666-6666-4666-8666-666666666666',
            chapterId: '77777777-7777-4777-8777-777777777777',
            workId: '88888888-8888-4888-8888-888888888888',
            workTitle: 'Thành cũ',
            chapterTitle: 'Cổng thành đóng lại',
            entitlementStatus: 'GRANTED',
            priceVersion: 1,
            creditsSpent: 10,
            grantedAt: '2026-11-14T10:05:00Z',
            currentReadAccess: 'UNAVAILABLE',
          },
        ],
        nextCursor: null,
      }),
    );

    await page.goto('/app/purchases');
    await expect(page.getByRole('heading', { name: 'Lịch sử nạp và mở chương' })).toBeVisible();
    await expect(
      page
        .getByRole('list', { name: 'Các lệnh nạp credit' })
        .getByText('Đang chờ xác nhận', { exact: true }),
    ).toBeVisible();
    await expect(page.getByText('100 paid credit · 1.000 ₫')).toBeVisible();
    await expect(page.getByText('Gói TEST 100 paid credit · phiên bản 1')).toBeVisible();
    await expect(page.getByText('Chưa ghi nhận credit')).toBeVisible();
    await expect(page.getByText('Đừng thanh toán lại lệnh này.')).toBeVisible();
    await expect(page.getByText('MÔI TRƯỜNG TEST')).toBeVisible();
    await expectNoAxeViolations(page);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
      .toBe(true);
    await page.screenshot({
      path: testInfo.outputPath(`01-orders-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    });
    if (viewport.width === 360) {
      await page
        .getByRole('list', { name: 'Các lệnh nạp credit' })
        .locator('li')
        .first()
        .evaluate((element) => element.scrollIntoView({ block: 'center', behavior: 'instant' }));
      await page.screenshot({ path: testInfo.outputPath('02-mobile-pending-order.png') });
      await page.evaluate(() => window.scrollTo(0, 0));
    }

    if (viewport.width === 360) {
      await expect(page.getByRole('link', { name: 'Giao dịch' })).toBeVisible();
    } else {
      await expect(page.getByRole('link', { name: 'Lịch sử nạp & mở chương' })).toBeVisible();
    }
    await page.getByLabel('Trạng thái').selectOption('PENDING');
    await expect(page).toHaveURL(/state=PENDING/);
    await expect.poll(() => orderStateRequests).toHaveLength(2);

    const creditViewLink = page.getByRole('link', { name: 'Biến động credit' });
    await creditViewLink.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/view=credits/);
    await expect(page.getByRole('heading', { name: 'Mở khóa chương' })).toBeVisible();
    await expect(page.getByText('-10 credit')).toBeVisible();
    await expect(page.getByText('Chưa ghi nhận credit')).toHaveCount(0);

    await page.getByRole('link', { name: 'Chương đã mở' }).click();
    await expect(page).toHaveURL(/view=unlocks/);
    await expect(page.getByRole('link', { name: 'Đọc chương' })).toHaveAttribute(
      'href',
      '/app/reader/55555555-5555-4555-8555-555555555555/44444444-4444-4444-8444-444444444444',
    );
    await expect(
      page.getByText('Chương hiện không khả dụng; lịch sử mở chương vẫn được lưu.'),
    ).toBeVisible();
    await expectNoAxeViolations(page);
  });
}

test('commerce history retries a failed first page and reflows at 200% zoom', async ({ page }) => {
  await page.setViewportSize({ width: 720, height: 900 });
  await installAccountRoutes(page);
  let attempts = 0;
  await page.route('**/api/v1/me/payment-orders**', async (route) => {
    attempts += 1;
    if (attempts === 1) {
      await json(route, { code: 'COMMERCE_HISTORY_UNAVAILABLE' }, 503);
      return;
    }
    await json(route, { items: [], nextCursor: null });
  });
  await page.route('**/api/v1/me/credits/transactions**', (route) =>
    json(route, { items: [], nextCursor: null }),
  );
  await page.route('**/api/v1/me/purchases**', (route) =>
    json(route, { items: [], nextCursor: null }),
  );
  await page.goto('/app/purchases');
  await expect(page.getByRole('heading', { name: 'Chưa thể mở lịch sử' })).toBeVisible();
  await page.getByRole('button', { name: 'Thử tải lại' }).click();
  await expect(page.getByRole('heading', { name: 'Chưa có lệnh nạp' })).toBeVisible();
  await page.evaluate(() => {
    document.body.style.zoom = '200%';
  });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
});
