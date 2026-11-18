import { readFile } from 'node:fs/promises';
import { expect, test } from './support';
import type { Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';

test('account export is requested, generated and downloaded through the real API and PostgreSQL', async ({
  page,
}) => {
  const sessionToken = process.env['ACCOUNT_EXPORT_LIVE_TEST_SESSION_TOKEN'];
  const screenshotPath = process.env['ACCOUNT_EXPORT_LIVE_TEST_SCREENSHOT'];
  test.skip(
    !sessionToken || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 -IncludeAccountDataExport to supply the isolated export fixture',
  );
  expect(sessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);

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

  const apiResponses: Response[] = [];
  page.on('response', (response) => {
    if (new URL(response.url()).pathname.startsWith('/api/v1/')) apiResponses.push(response);
  });

  await page.goto(`${baseUrl}/app/settings`);
  const section = page.getByRole('region', { name: 'Tải dữ liệu tài khoản' });
  await expect(section).toBeVisible();
  await expect(section).toContainText('nội dung đầy đủ mà tài khoản này sở hữu');
  await expect(section).toContainText('Nội dung thuộc người khác');

  const acknowledgement = section.getByRole('checkbox', {
    name: 'Tôi hiểu rằng tệp bao gồm toàn bộ nội dung truyện và chương tôi sở hữu.',
  });
  const requestButton = section.getByRole('button', { name: 'Yêu cầu bản export' });
  await expect(acknowledgement).not.toBeChecked();
  await expect(requestButton).toBeDisabled();
  await acknowledgement.check();

  const createResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/me/data-exports',
  );
  await requestButton.click();
  const createResponse = await createResponsePromise;
  expect(createResponse.status()).toBe(202);
  expect(createResponse.request().headers()['x-csrf-token']).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(createResponse.request().headers()['idempotency-key']).toMatch(/^[\w.-]{16,128}$/);
  const created = (await createResponse.json()) as {
    export: { id: string; status: string };
    replayed: boolean;
  };
  expect(created.export.status).toMatch(/^(queued|processing|ready)$/);
  expect(created.replayed).toBe(false);
  await expect(section).toContainText('Yêu cầu đã được tiếp nhận');

  await expect
    .poll(
      async () => {
        const latest = await page.request.get(`${baseUrl}/api/v1/me/data-exports/latest`);
        if (latest.status() !== 200) return 'unavailable';
        return ((await latest.json()) as { status: string }).status;
      },
      { timeout: 30_000 },
    )
    .toBe('ready');

  const refreshResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/me/data-exports/latest' &&
      response.status() === 200,
  );
  await section.getByRole('button', { name: 'Kiểm tra trạng thái' }).click();
  await refreshResponse;
  await expect(section).toContainText('Đã sẵn sàng tải xuống');
  await expect(section.getByText('Hạn tải:')).toBeVisible();

  const downloadUrl = `/api/v1/me/data-exports/${created.export.id}/download`;
  const downloadLink = section.getByRole('link', { name: 'Tải tệp ZIP' });
  await expect(downloadLink).toHaveAttribute('href', downloadUrl);
  const [download] = await Promise.all([page.waitForEvent('download'), downloadLink.click()]);
  expect(download.suggestedFilename()).toBe('novel-web-account-data.zip');
  expect(await download.failure()).toBeNull();
  const downloadResponse = await page.request.get(`${baseUrl}${downloadUrl}`);
  expect(downloadResponse.status()).toBe(200);
  expect(downloadResponse.headers()['content-type']).toContain('application/zip');
  expect(downloadResponse.headers()['cache-control']).toContain('no-store');
  expect(downloadResponse.headers()['referrer-policy']).toBe('no-referrer');
  expect(downloadResponse.headers()['content-disposition']).toContain('novel-web-account-data.zip');
  const downloadedPath = await download.path();
  expect(downloadedPath).toBeTruthy();
  const archive = await readFile(downloadedPath!);
  expect(archive.byteLength).toBeGreaterThan(100);
  expect(archive.subarray(0, 2).toString('ascii')).toBe('PK');
  expect(Number(downloadResponse.headers()['content-length'])).toBe(archive.byteLength);

  for (const path of [
    '/api/v1/auth/me',
    '/api/v1/auth/policies/current',
    '/api/v1/auth/policies/terms/terms-live-e2e-v1',
    '/api/v1/auth/policies/privacy-notice/privacy-live-e2e-v1',
    '/api/v1/me/data-exports/latest',
  ]) {
    expect(
      apiResponses.some(
        (response) => new URL(response.url()).pathname === path && response.status() === 200,
      ),
      `expected the browser to load ${path} from the real API`,
    ).toBe(true);
  }
  expect(
    apiResponses.some(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/me/data-exports' &&
        response.status() === 202,
    ),
  ).toBe(true);
  await page.screenshot({ path: screenshotPath!, fullPage: true });
});
