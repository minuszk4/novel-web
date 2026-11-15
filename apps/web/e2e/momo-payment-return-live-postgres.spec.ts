import { expect, test } from './support';

test('MoMo return reads a pending order from the real API and does not grant credit', async ({
  page,
}) => {
  const sessionToken = process.env['MOMO_PAYMENT_RETURN_LIVE_TEST_SESSION_TOKEN'];
  const orderId = process.env['MOMO_PAYMENT_RETURN_LIVE_TEST_ORDER_ID'];
  const screenshotPath = process.env['MOMO_PAYMENT_RETURN_LIVE_TEST_SCREENSHOT'];

  test.skip(
    !sessionToken || !orderId || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 -IncludePaymentReturn to supply an isolated PostgreSQL fixture',
  );
  expect(sessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(orderId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);

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
  const providerOrSettlementRequests: string[] = [];
  page.on('response', (response) => {
    if (new URL(response.url()).pathname.startsWith('/api/v1/')) apiResponses.push(response);
  });
  page.on('request', (request) => {
    const pathname = new URL(request.url()).pathname;
    if (/\/api\/v1\/(momo\/payment-orders|payments\/momo\/ipn)(?:\/|$)/.test(pathname)) {
      providerOrSettlementRequests.push(`${request.method()} ${pathname}`);
    }
  });

  // MoMo may append resultCode=0 after a return. The application must trust its own saved order.
  await page.goto(
    `http://localhost:4300/app/payment/result?orderId=${encodeURIComponent(orderId!)}&resultCode=0&signature=synthetic-untrusted`,
  );
  await expect(page.getByRole('heading', { name: 'Chưa xác nhận thanh toán' })).toBeVisible();
  await expect(page.getByText(/100 credit chưa được cộng/)).toBeVisible();
  await expect(page.getByText(/Đừng thanh toán lại/)).toBeVisible();
  await expect(page.getByText(/MoMo TEST — không dùng tiền thật/)).toBeVisible();
  await expect(page.getByText('Đang chờ xác nhận', { exact: true })).toBeVisible();
  const historyScreenshotPath = screenshotPath!.replace(
    'momo-payment-return-pending-live.png',
    'momo-payment-history-zero-live.png',
  );
  await page.screenshot({ path: screenshotPath!, fullPage: true });

  const orderStatusPath = `/api/v1/payment-orders/${orderId}`;
  const initialStatus = apiResponses.find(
    (response) => new URL(response.url()).pathname === orderStatusPath,
  );
  expect(initialStatus, 'return page must query the real actor-scoped order API').toBeDefined();
  expect(initialStatus!.status()).toBe(200);
  expect((await initialStatus!.json()).status).toBe('PENDING');

  const refreshedStatusResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === orderStatusPath && response.request().method() === 'GET',
  );
  await page.getByRole('button', { name: 'Kiểm tra trạng thái đã lưu' }).click();
  const confirmedRefresh = await refreshedStatusResponse;
  expect(confirmedRefresh.status()).toBe(200);
  await expect(page.getByRole('heading', { name: 'Chưa xác nhận thanh toán' })).toBeVisible();
  expect((await confirmedRefresh.json()).status).toBe('PENDING');

  await page.getByRole('link', { name: 'Xem lịch sử giao dịch' }).click();
  await expect(page).toHaveURL(/\/app\/purchases/);
  await expect(
    page.getByRole('list', { name: 'Các lệnh nạp credit' }).getByText('Đang chờ xác nhận', {
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByText('Chưa ghi nhận credit', { exact: true })).toBeVisible();
  await expect(page.locator('.purchases-balance-values strong')).toHaveText(['0', '0', '0']);

  const balanceResponse = apiResponses.find(
    (response) =>
      new URL(response.url()).pathname === '/api/v1/me/credits/balance' &&
      response.request().method() === 'GET',
  );
  expect(balanceResponse, 'history must read the real account balance API').toBeDefined();
  expect(balanceResponse!.status()).toBe(200);
  expect(await balanceResponse!.json()).toMatchObject({
    paidCredits: 0,
    bonusCredits: 0,
    totalCredits: 0,
  });
  expect(providerOrSettlementRequests).toEqual([]);
  await page.screenshot({ path: historyScreenshotPath, fullPage: true });
});
