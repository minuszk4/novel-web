import { expect, test } from './support';
import type { Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';
const sourceText = 'Live E2E source text is served from PostgreSQL.';

test('account Reader reads source and persists a bookmark through the real API and PostgreSQL', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const workId = process.env['READER_LIVE_TEST_WORK_ID'];
  const chapterId = process.env['READER_LIVE_TEST_CHAPTER_ID'];
  const screenshotPath = process.env['READER_LIVE_TEST_SCREENSHOT'];

  test.skip(
    !sessionToken || !workId || !chapterId || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 to supply an isolated Reader fixture',
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

  await page.goto(`${baseUrl}/app/reader/${workId}/${chapterId}`);
  await expect(page).toHaveURL(new RegExp(`/app/reader/${workId}/${chapterId}$`));
  await expect(page.getByRole('heading', { name: 'Live Reader Chapter' })).toBeVisible();
  await expect(page.getByText(sourceText, { exact: true })).toBeVisible();
  await expect(page.locator('.reader-account-private')).toContainText('THƯ VIỆN TÀI KHOẢN');
  await expect(page.locator('.demo-user strong')).toHaveText('Live E2E Reader');

  const responseFor = (method: string, path: string) =>
    apiResponses.find(
      (response) =>
        response.request().method() === method && new URL(response.url()).pathname === path,
    );
  const manifestPath = `/api/v1/works/${workId}/reader-manifest`;
  const contentPath = `/api/v1/chapters/${chapterId}/content`;
  await expect.poll(() => responseFor('GET', manifestPath)?.status()).toBe(200);
  await expect.poll(() => responseFor('GET', contentPath)?.status()).toBe(200);

  const manifestResponse = responseFor('GET', manifestPath);
  const contentResponse = responseFor('GET', contentPath);
  expect(manifestResponse).toBeDefined();
  expect(contentResponse).toBeDefined();
  const manifestBody = await manifestResponse!.json();
  const contentBody = await contentResponse!.json();
  expect(JSON.stringify(manifestBody)).not.toContain(sourceText);
  expect(manifestBody.chapters).toContainEqual(
    expect.objectContaining({
      id: chapterId,
      title: 'Live Reader Chapter',
      accessState: 'readable',
    }),
  );
  expect(contentBody).toEqual(
    expect.objectContaining({
      workId,
      chapterId,
      segments: [expect.objectContaining({ anchor: 'live-reader-p1', sourceText })],
    }),
  );

  const bookmarkPath = `/api/v1/works/${workId}/bookmarks`;
  await page.getByRole('button', { name: 'Mở dấu trang' }).click();
  const panel = page.getByRole('dialog', { name: 'Vị trí đã lưu' });
  await expect(panel.getByRole('heading', { name: 'Chưa có dấu trang' })).toBeVisible();
  const createBookmark = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === bookmarkPath,
  );
  await panel.getByRole('button', { name: 'Lưu vị trí đang đọc' }).click();
  const createdBookmark = await createBookmark;
  const bookmarkResponseBody = await createdBookmark.text();
  expect(
    createdBookmark.status(),
    `POST bookmark failed for origin ${createdBookmark.request().headers()['origin'] ?? '(none)'}: ${bookmarkResponseBody}`,
  ).toBe(201);
  const bookmark = JSON.parse(bookmarkResponseBody);
  await expect(
    panel.getByRole('button', {
      name: 'Chương 1 · Live Reader Chapter',
      exact: true,
    }),
  ).toBeVisible();
  await page.screenshot({ path: screenshotPath!, fullPage: true });

  await panel.getByRole('button', { name: /Đóng dấu trang/ }).click();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Live Reader Chapter' })).toBeVisible();
  const listBookmarks = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === bookmarkPath,
  );
  await page.getByRole('button', { name: 'Mở dấu trang' }).click();
  const bookmarksResponse = await listBookmarks;
  expect(bookmarksResponse.status()).toBe(200);
  const bookmarkPage = await bookmarksResponse.json();
  expect(bookmarkPage.items).toContainEqual(expect.objectContaining({ id: bookmark.id, workId }));
  await expect(
    page.getByRole('dialog', { name: 'Vị trí đã lưu' }).getByRole('button', {
      name: 'Chương 1 · Live Reader Chapter',
      exact: true,
    }),
  ).toBeVisible();
});
