import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const orderId = '11111111-1111-4111-8111-111111111111';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const pack = {
  packId: 'vnpay-test-credits-100',
  packVersion: 1,
  environment: 'TEST',
  currency: 'VND',
  amountVnd: 1000,
  paidCredits: 100,
  bonusCredits: 0,
  paidExpiresAt: null,
  bonusExpiresAt: null,
};
const pendingOrder = {
  id: orderId,
  ...pack,
  status: 'PENDING',
  createdAt: '2026-11-15T10:00:00Z',
};
const account = {
  user: {
    id: 'credit-shop-user',
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

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installAccountRoutes(
  page: Page,
  options: { commerceEnabled?: boolean; vnpayTestEnabled?: boolean } = {},
): Promise<void> {
  const commerceEnabled = options.commerceEnabled ?? true;
  const vnpayTestEnabled = options.vnpayTestEnabled ?? true;
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: 'v1',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled,
        vnpayEnvironment: vnpayTestEnabled ? 'TEST' : 'OFF',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: commerceEnabled,
        translationFakePreviewEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
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

for (const scenario of [
  { name: 'commerce sandbox flag', options: { commerceEnabled: false, vnpayTestEnabled: true } },
  { name: 'VNPAY TEST flag', options: { commerceEnabled: true, vnpayTestEnabled: false } },
]) {
  test('keeps the shop closed when the ' + scenario.name + ' is off', async ({ page }) => {
    await installAccountRoutes(page, scenario.options);
    let catalogRequests = 0;
    let createRequests = 0;
    await page.route('**/api/v1/credit-packs', async (route) => {
      catalogRequests += 1;
      await json(route, { environment: 'TEST', offers: [pack] });
    });
    await page.route('**/api/v1/vnpay/payment-orders', async (route) => {
      createRequests += 1;
      await json(route, { code: 'SHOULD_NOT_BE_CALLED' }, 500);
    });

    await page.goto('/app/credits');
    await expect(page.getByRole('heading', { name: 'Cửa hàng TEST đang tắt' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Xem lịch sử nạp' })).toBeVisible();
    await expect.poll(() => catalogRequests).toBe(0);
    await expect.poll(() => createRequests).toBe(0);
    await expect(page.getByText('VNPAY TEST hiện chưa được bật.')).toBeVisible();
    await expectNoAxeViolations(page);
  });
}

test('requires explicit confirmation and waits for server-confirmed settlement', async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installAccountRoutes(page);
  await page.route('**/api/v1/credit-packs', (route) =>
    json(route, { environment: 'TEST', offers: [pack] }),
  );
  const statusRequests: string[] = [];
  await page.route('**/api/v1/payment-orders/' + orderId, async (route) => {
    statusRequests.push(route.request().method());
    await json(
      route,
      statusRequests.length === 1 ? pendingOrder : { ...pendingOrder, status: 'SUCCEEDED' },
    );
  });
  const idempotencyKeys: string[] = [];
  let orderCreates = 0;
  await page.route('**/api/v1/vnpay/payment-orders', async (route) => {
    orderCreates += 1;
    const request = route.request();
    expect(request.method()).toBe('POST');
    expect(request.headers()['x-csrf-token']).toBe(csrfToken);
    expect(request.postDataJSON()).toEqual({ packId: pack.packId, packVersion: pack.packVersion });
    idempotencyKeys.push(request.headers()['idempotency-key'] ?? '');
    await json(
      route,
      {
        order: pendingOrder,
        checkoutOutcome: 'READY',
        checkoutUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_SecureHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      },
      201,
    );
  });
  let balanceRequests = 0;
  await page.unroute('**/api/v1/me/credits/balance');
  await page.route('**/api/v1/me/credits/balance', async (route) => {
    balanceRequests += 1;
    const paidCredits = balanceRequests > 1 ? 110 : 10;
    await json(route, {
      paidCredits,
      bonusCredits: 0,
      totalCredits: paidCredits,
      asOf: '2026-11-15T10:00:00Z',
    });
  });

  await page.goto('/app/credits?returnTo=%2Fapp%2Freader%2F55555555-5555-4555-8555-555555555555');
  await expect(page.getByRole('heading', { name: 'Nạp credit' })).toBeVisible();
  await expect(page.getByText('1.000 ₫')).toBeVisible();
  await expect(page.getByText('100 credit đã mua')).toBeVisible();
  await expect(page.getByText('VNPAY TEST — không dùng tiền thật.')).toBeVisible();
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: testInfo.outputPath('01-credit-shop-360x800.png'),
    fullPage: false,
  });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);

  await page.getByRole('button', { name: /Chọn gói 100 credit/ }).click();
  const dialog = page.getByRole('dialog', { name: 'Tạo lệnh nạp?' });
  await expect(dialog).toBeVisible();
  await expectNoAxeViolations(page);
  await page.getByRole('button', { name: 'Hủy' }).click();
  await expect(dialog).toBeHidden();
  expect(orderCreates).toBe(0);

  await page.getByRole('button', { name: /Chọn gói 100 credit/ }).click();
  await page.getByRole('button', { name: 'Xác nhận tạo lệnh' }).click();
  await expect(page.getByRole('heading', { name: 'Đang chờ VNPAY xác nhận' })).toBeVisible();
  expect(orderCreates).toBe(1);
  expect(idempotencyKeys).toHaveLength(1);
  expect(idempotencyKeys[0]).toMatch(/^[0-9a-f-]{36}$/i);
  await expect(page.getByRole('link', { name: /Mở trang VNPAY TEST/ })).toHaveAttribute(
    'href',
    'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_SecureHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  );
  await expect(page.getByRole('link', { name: 'Trở lại chương đang đọc' })).toHaveAttribute(
    'href',
    '/app/reader/55555555-5555-4555-8555-555555555555',
  );
  expect(balanceRequests).toBe(1);

  await page
    .getByRole('heading', { name: 'Đang chờ VNPAY xác nhận' })
    .evaluate((element) => element.scrollIntoView({ block: 'center', behavior: 'instant' }));
  await page.screenshot({
    path: testInfo.outputPath('02-pending-order-360x800.png'),
    fullPage: false,
  });
  await page.getByRole('button', { name: 'Kiểm tra trạng thái đã lưu' }).click();
  await expect(page.getByRole('heading', { name: 'Đang chờ VNPAY xác nhận' })).toBeVisible();
  expect(balanceRequests).toBe(1);
  expect(statusRequests).toHaveLength(1);
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Kiểm tra trạng thái đã lưu' }).click();
  await expect(page.getByRole('heading', { name: 'Đã xác nhận' })).toBeVisible();
  await expect(page.getByText('110', { exact: true })).toBeVisible();
  expect(balanceRequests).toBe(2);
  expect(statusRequests).toHaveLength(2);
  await expectNoAxeViolations(page);

  await page.setViewportSize({ width: 1280, height: 900 });
  await page.evaluate(() => window.scrollTo(0, 0));
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: testInfo.outputPath('03-credit-shop-1280x900.png'),
    fullPage: false,
  });
  await page.setViewportSize({ width: 720, height: 900 });
  await page.evaluate(() => {
    document.body.style.zoom = '200%';
  });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: testInfo.outputPath('04-credit-shop-200-percent-zoom.png'),
    fullPage: false,
  });
});

test('restores an uncertain create and retries with the same idempotency key', async ({ page }) => {
  await installAccountRoutes(page);
  await page.route('**/api/v1/credit-packs', (route) =>
    json(route, { environment: 'TEST', offers: [pack] }),
  );
  const idempotencyKeys: string[] = [];
  let postCount = 0;
  await page.route('**/api/v1/vnpay/payment-orders', async (route) => {
    postCount += 1;
    idempotencyKeys.push(route.request().headers()['idempotency-key'] ?? '');
    if (postCount === 1) {
      await route.abort('connectionreset');
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Idempotency-Replayed': 'true' },
      body: JSON.stringify({
        order: pendingOrder,
        checkoutOutcome: 'READY',
        checkoutUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_SecureHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      }),
    });
  });

  await page.goto('/app/credits');
  await page.getByRole('button', { name: /Chọn gói 100 credit/ }).click();
  await page.getByRole('button', { name: 'Xác nhận tạo lệnh' }).click();
  await expect(page.getByRole('button', { name: 'Thử lại cùng yêu cầu an toàn' })).toBeVisible();
  const firstKey = idempotencyKeys[0];
  expect(firstKey).toMatch(/^[0-9a-f-]{36}$/i);

  await page.reload();
  await expect(page.getByRole('heading', { name: 'Yêu cầu nạp chưa được xác nhận' })).toBeVisible();
  await page.getByRole('button', { name: 'Thử lại cùng yêu cầu' }).click();
  await expect(page.getByRole('heading', { name: 'Đang chờ VNPAY xác nhận' })).toBeVisible();
  expect(postCount).toBe(2);
  expect(idempotencyKeys).toEqual([firstKey, firstKey]);
  await expect(page.getByRole('link', { name: /Mở trang VNPAY TEST/ })).toHaveAttribute(
    'href',
    'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_SecureHash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  );
});
