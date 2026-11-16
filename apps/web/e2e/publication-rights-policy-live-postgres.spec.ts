import { expect, test } from './support';

const baseUrl = 'http://localhost:4300';

interface PolicyResponse {
  version: string;
  effectiveAt: string;
  displayText: string;
  policySha256: string;
  acceptedAt: string | null;
}

interface AcceptanceResponse {
  id: string;
  policyVersion: string;
  policySha256: string;
  acceptedAt: string;
}

test('author explicitly accepts a synthetic rights policy and replays a committed response loss', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const workId = process.env['PUBLICATION_RIGHTS_LIVE_TEST_WORK_ID'];
  const screenshotPath = process.env['PUBLICATION_RIGHTS_LIVE_TEST_SCREENSHOT'];
  const expectedVersion = process.env['PUBLICATION_RIGHTS_LIVE_TEST_VERSION'];
  const expectedText = process.env['PUBLICATION_RIGHTS_LIVE_TEST_TEXT'];
  const expectedSha256 = process.env['PUBLICATION_RIGHTS_LIVE_TEST_SHA256'];

  test.skip(
    !sessionToken ||
      !workId ||
      !screenshotPath ||
      !expectedVersion ||
      !expectedText ||
      !expectedSha256,
    'run tools/e2e/run-library-postgres.ps1 -IncludePublicationRightsAcceptance for the disposable synthetic-policy fixture',
  );
  expect(sessionToken, 'run with the disposable PostgreSQL fixture harness').toMatch(
    /^[A-Za-z0-9_-]{43}$/,
  );
  expect(workId).toMatch(/^[0-9a-f-]{36}$/i);

  await page.setViewportSize({ width: 360, height: 800 });
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

  const policyPath = `/api/v1/authoring/manuscripts/${workId}/publication/rights-policy`;
  const acceptancePath = `${policyPath}/acceptance`;
  let policyReads = 0;
  page.on('request', (request) => {
    if (request.method() === 'GET' && new URL(request.url()).pathname === policyPath) {
      policyReads += 1;
    }
  });

  await page.goto(`${baseUrl}/app/authoring/${workId}/chapters`);
  await expect(page.getByRole('heading', { name: 'Live E2E Novel Two' })).toBeVisible();
  await expect(page.getByText('Bản thảo riêng tư · Chưa xuất bản')).toBeVisible();
  expect(policyReads).toBe(0);

  const policyRead = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === policyPath,
  );
  await page.getByRole('button', { name: 'Kiểm tra chính sách quyền xuất bản' }).click();
  const policyResponse = await policyRead;
  expect(policyResponse.status()).toBe(200);
  expect(policyResponse.headers()['cache-control']).toContain('no-store');
  const currentPolicy = (await policyResponse.json()) as PolicyResponse;
  expect(currentPolicy).toMatchObject({
    version: expectedVersion,
    displayText: expectedText,
    policySha256: expectedSha256,
    acceptedAt: null,
  });
  await expect(page.getByText(expectedText!, { exact: true })).toBeVisible();
  await expect(page.locator('.authoring-rights-document script')).toHaveCount(0);
  const checkbox = page.getByRole('checkbox', {
    name: 'Tôi đã đọc và xác nhận chính sách này cho bản thảo Live E2E Novel Two',
  });
  await expect(checkbox).not.toBeChecked();
  const confirm = page.getByRole('button', { name: 'Xác nhận đã đọc chính sách' });
  await expect(confirm).toBeDisabled();

  const realAttempts: Array<{
    requestBody: unknown;
    idempotencyKey: string | undefined;
    status: number;
    body: AcceptanceResponse;
  }> = [];
  await page.route(`**${acceptancePath}`, async (route) => {
    const request = route.request();
    const serverResponse = await route.fetch();
    const responseBody = await serverResponse.text();
    realAttempts.push({
      requestBody: request.postDataJSON(),
      idempotencyKey: request.headers()['idempotency-key'],
      status: serverResponse.status(),
      body: JSON.parse(responseBody) as AcceptanceResponse,
    });

    if (realAttempts.length === 1) {
      await route.fulfill({
        status: 503,
        contentType: 'application/problem+json',
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
        body: JSON.stringify({ code: 'SYNTHETIC_RESPONSE_LOSS_AFTER_COMMIT' }),
      });
      return;
    }

    await route.fulfill({
      status: serverResponse.status(),
      contentType: 'application/json',
      headers: {
        'Cache-Control': serverResponse.headers()['cache-control'] ?? 'no-store',
        Pragma: serverResponse.headers()['pragma'] ?? 'no-cache',
      },
      body: responseBody,
    });
  });

  await checkbox.check();
  expect(realAttempts).toHaveLength(0);
  const firstPost = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === acceptancePath,
  );
  await confirm.click();
  expect((await firstPost).status()).toBe(503);
  await expect(page.getByRole('button', { name: 'Thử xác nhận lại' })).toBeVisible();
  await expect(page.getByRole('checkbox')).toBeChecked();
  expect(realAttempts).toHaveLength(1);
  expect(realAttempts[0].status).toBe(200);
  expect(realAttempts[0].requestBody).toEqual({
    policyVersion: expectedVersion,
    confirmed: true,
  });
  expect(realAttempts[0].idempotencyKey).toMatch(/^[0-9a-f-]{36}$/i);
  expect(realAttempts[0].body).toMatchObject({
    policyVersion: expectedVersion,
    policySha256: expectedSha256,
  });
  expect(realAttempts[0].body.acceptedAt).toMatch(/^\d{4}-\d\d-\d\dT/);

  const replay = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === acceptancePath,
  );
  await page.getByRole('button', { name: 'Thử xác nhận lại' }).click();
  expect((await replay).status()).toBe(200);
  await expect(
    page.getByText(new RegExp(`Đã ghi nhận xác nhận policy phiên bản ${expectedVersion}`)),
  ).toBeVisible();
  await expect(page.locator('.authoring-rights-success')).toContainText(
    'không xác minh quyền tác giả hoặc giấy phép',
  );
  await expect(page.getByRole('checkbox')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /Gửi yêu cầu duyệt|Xuất bản/ })).toHaveCount(0);

  expect(realAttempts).toHaveLength(2);
  expect(realAttempts[1].status).toBe(200);
  expect(realAttempts[1].requestBody).toEqual(realAttempts[0].requestBody);
  expect(realAttempts[1].idempotencyKey).toBe(realAttempts[0].idempotencyKey);
  expect(realAttempts[1].body).toEqual(realAttempts[0].body);
  await page.screenshot({ path: screenshotPath!, fullPage: true });
});
