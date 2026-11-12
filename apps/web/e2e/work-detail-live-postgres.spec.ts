import { expect, test } from './support';
import type { Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';
const sourceText = 'Live E2E source text is served from PostgreSQL.';

test('account Work Detail hands off to readable source from the real API and PostgreSQL', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const workId = process.env['READER_LIVE_TEST_WORK_ID'];
  const chapterId = process.env['READER_LIVE_TEST_CHAPTER_ID'];
  const screenshotPath = process.env['WORK_DETAIL_LIVE_TEST_SCREENSHOT'];

  test.skip(
    !sessionToken || !workId || !chapterId || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 to supply an isolated Work Detail fixture',
  );
  expect(sessionToken, 'run with the disposable PostgreSQL fixture harness').toMatch(
    /^[A-Za-z0-9_-]{43}$/,
  );
  expect(workId).toMatch(/^[0-9a-f-]{36}$/i);
  expect(chapterId).toMatch(/^[0-9a-f-]{36}$/i);

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

  await page.goto(`${baseUrl}/app/works/${workId}`);
  await expect(page).toHaveURL(new RegExp(`/app/works/${workId}$`));
  await expect(page.getByRole('heading', { name: 'Live E2E Novel One' })).toBeVisible();
  await expect(page.getByText('E2E Author')).toBeVisible();
  await expect(page.locator('.detail-tags').getByText('1 chương', { exact: true })).toBeVisible();
  await expect(
    page.getByRole('link', { name: /Chương 1.*Live Reader Chapter.*Đọc/ }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: /Đọc từ đầu/ })).toBeVisible();
  await expect(page.getByText(sourceText, { exact: true })).toHaveCount(0);

  const responseFor = (method: string, path: string) =>
    apiResponses.find(
      (response) =>
        response.request().method() === method && new URL(response.url()).pathname === path,
    );
  const manifestPath = `/api/v1/works/${workId}/reader-manifest`;
  const progressPath = `/api/v1/works/${workId}/progress`;
  await expect.poll(() => responseFor('GET', manifestPath)?.status()).toBe(200);
  await expect.poll(() => responseFor('GET', progressPath)?.status()).toBe(200);

  const manifestResponse = responseFor('GET', manifestPath);
  expect(manifestResponse).toBeDefined();
  const manifestBody = await manifestResponse!.json();
  expect(JSON.stringify(manifestBody)).not.toContain(sourceText);
  expect(manifestBody.chapters).toContainEqual(
    expect.objectContaining({
      id: chapterId,
      title: 'Live Reader Chapter',
      accessState: 'readable',
    }),
  );

  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  await page.screenshot({ path: screenshotPath!, fullPage: true });

  await page.getByRole('button', { name: /Đọc từ đầu/ }).click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterId}$`));
  await expect(page.getByRole('heading', { name: 'Live Reader Chapter' })).toBeVisible();
  await expect(page.getByText(sourceText, { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Quay lại chi tiết tác phẩm' }).click();
  await expect(page).toHaveURL(new RegExp(`/app/works/${workId}$`));
  await expect(page.getByRole('heading', { name: 'Live E2E Novel One' })).toBeVisible();

  for (const path of [manifestPath, progressPath, `/api/v1/chapters/${chapterId}/content`]) {
    const response = responseFor('GET', path);
    expect(response, `expected a real API response for ${path}`).toBeDefined();
    expect(response!.status(), `${path} should succeed without an API stub`).toBe(200);
  }
});
