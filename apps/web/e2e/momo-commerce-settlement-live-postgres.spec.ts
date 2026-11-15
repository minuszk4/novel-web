import { createHmac } from 'node:crypto';
import { expect, test } from './support';

test('synthetic signed settlement grants credits once and unlocks a public chapter', async ({
  page,
}) => {
  const sessionToken = process.env['MOMO_COMMERCE_LIVE_TEST_SESSION_TOKEN'];
  const otherSessionToken = process.env['MOMO_COMMERCE_LIVE_TEST_OTHER_SESSION_TOKEN'];
  const orderId = process.env['MOMO_COMMERCE_LIVE_TEST_ORDER_ID'];
  const requestId = process.env['MOMO_COMMERCE_LIVE_TEST_REQUEST_ID'];
  const workId = process.env['MOMO_COMMERCE_LIVE_TEST_WORK_ID'];
  const chapterId = process.env['MOMO_COMMERCE_LIVE_TEST_CHAPTER_ID'];
  const successScreenshot = process.env['MOMO_COMMERCE_LIVE_TEST_SUCCESS_SCREENSHOT'];
  const unlockScreenshot = process.env['MOMO_COMMERCE_LIVE_TEST_UNLOCK_SCREENSHOT'];
  const accessKey = process.env['MOMO_TEST_ACCESS_KEY'];
  const secretKey = process.env['MOMO_TEST_SECRET_KEY'];

  test.skip(
    !sessionToken ||
      !otherSessionToken ||
      !orderId ||
      !requestId ||
      !workId ||
      !chapterId ||
      !successScreenshot ||
      !unlockScreenshot ||
      !accessKey ||
      !secretKey,
    'run tools/e2e/run-library-postgres.ps1 -IncludeCommerceSettlement for the disposable fixture',
  );
  expect(sessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(otherSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(orderId).toMatch(/^[0-9a-f-]{36}$/i);
  expect(requestId).toMatch(/^[0-9a-f-]{36}$/i);
  expect(workId).toMatch(/^[0-9a-f-]{36}$/i);
  expect(chapterId).toMatch(/^[0-9a-f-]{36}$/i);

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

  const chapterContentRequests: string[] = [];
  const apiResponses: import('@playwright/test').Response[] = [];
  page.on('request', (request) => {
    const pathname = new URL(request.url()).pathname;
    if (pathname === `/api/v1/chapters/${chapterId}/content`) {
      chapterContentRequests.push(`${request.method()} ${pathname}`);
    }
  });
  page.on('response', (response) => {
    if (new URL(response.url()).pathname.startsWith('/api/v1/')) apiResponses.push(response);
  });

  await page.goto(
    `http://localhost:4300/app/payment/result?orderId=${encodeURIComponent(orderId!)}&resultCode=0`,
  );
  await expect(page.getByRole('heading', { name: 'Chưa xác nhận thanh toán' })).toBeVisible();
  await expect(page.getByText(/100 credit chưa được cộng/)).toBeVisible();
  const initialOrderResponse = apiResponses.find(
    (response) => new URL(response.url()).pathname === `/api/v1/payment-orders/${orderId}`,
  );
  expect(initialOrderResponse?.status()).toBe(200);
  expect((await initialOrderResponse!.json()).status).toBe('PENDING');
  await expect(page.getByText('Số dư hiện tại:')).toHaveCount(0);

  const notification = {
    amount: 1000,
    extraData: '',
    message: 'Successful.',
    orderId: orderId!,
    orderInfo: 'Novel Web credit top-up',
    orderType: 'momo_wallet',
    partnerCode: process.env['MOMO_TEST_PARTNER_CODE'] ?? 'SYNTHETIC-TEST-PARTNER',
    payType: 'qr',
    requestId: requestId!,
    responseTime: Date.now(),
    resultCode: 0,
    transId: Date.now() * 1000,
  };
  const canonicalNotification =
    `accessKey=${accessKey}` +
    `&amount=${notification.amount}` +
    `&extraData=${notification.extraData}` +
    `&message=${notification.message}` +
    `&orderId=${notification.orderId}` +
    `&orderInfo=${notification.orderInfo}` +
    `&orderType=${notification.orderType}` +
    `&partnerCode=${notification.partnerCode}` +
    `&payType=${notification.payType}` +
    `&requestId=${notification.requestId}` +
    `&responseTime=${notification.responseTime}` +
    `&resultCode=${notification.resultCode}` +
    `&transId=${notification.transId}`;
  const signedNotification = {
    ...notification,
    signature: createHmac('sha256', secretKey!).update(canonicalNotification).digest('hex'),
  };

  const firstIpn = await page.request.post('http://localhost:4300/api/v1/payments/momo/ipn', {
    data: signedNotification,
  });
  expect(firstIpn.status()).toBe(204);
  const duplicateIpn = await page.request.post('http://localhost:4300/api/v1/payments/momo/ipn', {
    data: signedNotification,
  });
  expect(duplicateIpn.status()).toBe(204);

  const confirmedOrderResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/v1/payment-orders/${orderId}` &&
      response.request().method() === 'GET',
  );
  await page.getByRole('button', { name: 'Kiểm tra trạng thái đã lưu' }).click();
  const confirmedOrder = await confirmedOrderResponse;
  expect(confirmedOrder.status()).toBe(200);
  expect((await confirmedOrder.json()).status).toBe('SUCCEEDED');
  await expect(page.getByRole('heading', { name: 'Thanh toán đã được xác nhận' })).toBeVisible();
  await expect(page.getByText(/Số dư hiện tại: 100 credit/)).toBeVisible();
  await page.screenshot({ path: successScreenshot!, fullPage: true });

  await page.goto(`http://localhost:4300/app/reader/${workId}/${chapterId}`);
  await expect(page.getByRole('heading', { name: 'Live Paid Chapter' })).toBeVisible();
  await expect(page.getByText('10 paid credit', { exact: true })).toBeVisible();
  await expect(page.getByText('100', { exact: true })).toBeVisible();
  expect(chapterContentRequests).toEqual([]);

  await page.getByRole('button', { name: 'Xem lại điều kiện' }).click();
  await expect(page.getByRole('button', { name: 'Xác nhận · 10 credit' })).toBeVisible();
  await page.getByRole('button', { name: 'Xác nhận · 10 credit' }).click();
  await expect(page.locator('.account-reader-content')).toContainText(
    'This paid chapter is released only after a server-confirmed credit unlock.',
  );
  expect(chapterContentRequests).toEqual([`GET /api/v1/chapters/${chapterId}/content`]);
  const unlockResponse = apiResponses.find(
    (response) =>
      new URL(response.url()).pathname === `/api/v1/chapters/${chapterId}/unlock` &&
      response.request().method() === 'POST',
  );
  expect(unlockResponse?.status()).toBe(201);
  expect(await unlockResponse!.json()).toMatchObject({
    outcome: 'CREATED',
    chapterId,
    creditsSpent: 10,
  });
  await page.screenshot({ path: unlockScreenshot!, fullPage: true });

  const balanceResponse = await page.request.get('http://localhost:4300/api/v1/me/credits/balance');
  expect(balanceResponse.status()).toBe(200);
  expect(await balanceResponse.json()).toMatchObject({
    paidCredits: 90,
    bonusCredits: 0,
    totalCredits: 90,
  });

  await page.goto('http://localhost:4300/app/purchases?view=orders');
  await expect(page.getByRole('heading', { name: 'Lệnh nạp' })).toBeVisible();
  await expect(
    page
      .getByRole('list', { name: 'Các lệnh nạp credit' })
      .getByText('100 credit đã được ghi nhận'),
  ).toBeVisible();
  await page.goto('http://localhost:4300/app/purchases?view=unlocks');
  await expect(page.getByRole('heading', { name: 'Chương đã mở' })).toBeVisible();
  await expect(page.getByText('Live Paid Chapter')).toBeVisible();

  await page.context().clearCookies();
  await page.context().addCookies([
    {
      name: '__Host-novel_session',
      value: otherSessionToken!,
      domain: 'localhost',
      path: '/',
      httpOnly: true,
      secure: true,
      sameSite: 'Lax',
    },
  ]);

  const foreignOrderResponse = await page.request.get(
    `http://localhost:4300/api/v1/payment-orders/${orderId}`,
  );
  expect(foreignOrderResponse.status()).toBe(404);
  expect(await foreignOrderResponse.text()).toContain('PAYMENT_ORDER_NOT_FOUND');

  const foreignPaymentOrders = await page.request.get(
    'http://localhost:4300/api/v1/me/payment-orders',
  );
  expect(foreignPaymentOrders.status()).toBe(200);
  expect(foreignPaymentOrders.headers()['cache-control']).toContain('no-store');
  expect(await foreignPaymentOrders.json()).toMatchObject({ items: [], nextCursor: null });

  const foreignCreditTransactions = await page.request.get(
    'http://localhost:4300/api/v1/me/credits/transactions',
  );
  expect(foreignCreditTransactions.status()).toBe(200);
  expect(await foreignCreditTransactions.json()).toMatchObject({ items: [], nextCursor: null });

  const foreignPurchases = await page.request.get('http://localhost:4300/api/v1/me/purchases');
  expect(foreignPurchases.status()).toBe(200);
  expect(await foreignPurchases.json()).toMatchObject({ items: [], nextCursor: null });

  const foreignBalance = await page.request.get('http://localhost:4300/api/v1/me/credits/balance');
  expect(foreignBalance.status()).toBe(200);
  expect(await foreignBalance.json()).toMatchObject({
    paidCredits: 0,
    bonusCredits: 0,
    totalCredits: 0,
  });

  await page.goto(
    `http://localhost:4300/app/payment/result?orderId=${encodeURIComponent(orderId!)}`,
  );
  await expect(page.getByRole('heading', { name: 'Chưa tải được trạng thái' })).toBeVisible();
  await expect(page.getByText(/Chưa tải được trạng thái do máy chủ lưu/)).toBeVisible();
  await expect(page.getByText(/100 paid credit|1\.000 ₫/)).toHaveCount(0);

  await page.goto('http://localhost:4300/app/purchases?view=orders');
  await expect(page.getByRole('heading', { name: 'Chưa có lệnh nạp' })).toBeVisible();
  await expect(page.locator('.purchases-balance-values strong')).toHaveText(['0', '0', '0']);
  await expect(page.getByRole('list', { name: 'Các lệnh nạp credit' })).toHaveCount(0);

  await page.goto('http://localhost:4300/app/purchases?view=credits');
  await expect(page.getByRole('heading', { name: 'Chưa có biến động credit' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Các biến động credit' })).toHaveCount(0);

  await page.goto('http://localhost:4300/app/purchases?view=unlocks');
  await expect(page.getByRole('heading', { name: 'Bạn chưa mở chương nào' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Các chương đã mở' })).toHaveCount(0);
  await expect(page.getByText('Live Paid Chapter')).toHaveCount(0);
  await expect(page.getByText('Live Public Commerce Story')).toHaveCount(0);

  chapterContentRequests.length = 0;
  await page.goto(`http://localhost:4300/app/reader/${workId}/${chapterId}`);
  await expect(page.getByRole('heading', { name: 'Live Paid Chapter' })).toBeVisible();
  await expect(page.getByText(/Bạn còn 0 paid credit, chưa đủ 10 credit/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Xem lại điều kiện' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Nạp credit MoMo TEST' })).toBeVisible();
  expect(chapterContentRequests).toEqual([]);
});
