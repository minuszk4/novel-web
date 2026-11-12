import { expect, test } from './support';
import type { Page, Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';
const fixtures = [
  {
    id: 'html',
    name: 'HTML',
    filename: 'live-private-hostile.html',
    mimeType: 'text/html',
    sourceText: `<!doctype html>
<html lang="vi"><head><title>Safe HTML Novel</title></head><body><article>
<h1>Imported HTML chapter</h1>
<p>HTML safe sentence survives.</p>
<p><img src="https://reader-xss.invalid/image.png" onerror="window.readerInjected=true" alt="remote image alt"></p>
<p><a href="javascript:window.readerInjected=true">HTML safe link label</a></p>
<svg onload="window.readerInjected=true"><script>window.readerInjected=true</script><image href="https://reader-xss.invalid/vector.svg"></image></svg>
<iframe src="https://reader-xss.invalid/frame">iframe hidden text</iframe>
<script>window.readerInjected=true</script>
</article></body></html>`,
    visibleReaderText: ['HTML safe sentence survives.', 'HTML safe link label'],
    forbiddenText: [
      'reader-xss.invalid',
      'onerror',
      'javascript:',
      '<script',
      '<svg',
      '<iframe',
      'remote image alt',
      'iframe hidden text',
      'window.readerInjected',
    ],
  },
  {
    id: 'markdown',
    name: 'Markdown',
    filename: 'live-private-hostile.md',
    mimeType: 'text/markdown',
    sourceText: `# Imported Markdown chapter

Markdown safe sentence survives.

[Markdown safe link label](javascript:window.readerInjected=true)

![remote image alt](https://reader-xss.invalid/image.png)

<img src="https://reader-xss.invalid/markdown.png" onerror="window.readerInjected=true">

<svg onload="window.readerInjected=true"><script>window.readerInjected=true</script></svg>

<iframe src="https://reader-xss.invalid/frame">iframe hidden text</iframe>`,
    visibleReaderText: [
      'Markdown safe sentence survives.',
      'Markdown safe link label',
      'remote image alt',
    ],
    forbiddenText: [
      'reader-xss.invalid',
      'onerror',
      'javascript:',
      '<img',
      '<script',
      '<svg',
      '<iframe',
      'window.readerInjected',
    ],
  },
] as const;

interface ImportFixture {
  id: string;
  name: string;
  filename: string;
  mimeType: string;
  sourceText: string;
  visibleReaderText: readonly string[];
  forbiddenText: readonly string[];
}

async function assertBodyOmits(page: Page, values: readonly string[]): Promise<void> {
  for (const value of values) {
    await expect(page.locator('body')).not.toContainText(value);
  }
}

async function runFormatJourney(page: Page, fixture: ImportFixture): Promise<void> {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const evidenceBase = process.env['PRIVATE_IMPORT_LIVE_TEST_SCREENSHOT'];
  test.skip(
    !sessionToken || !evidenceBase,
    'run tools/e2e/run-library-postgres.ps1 to supply a disposable account and approved test statement',
  );
  expect(sessionToken, 'run with the disposable PostgreSQL fixture harness').toMatch(
    /^[A-Za-z0-9_-]{43}$/,
  );

  const workTitle = `Live Private ${fixture.name} Novel`;
  const screenshotPath = (state: string) =>
    evidenceBase!.replace(
      'private-import-commit-live.png',
      `private-import-${fixture.id}-${state}-live.png`,
    );

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

  const unexpectedExternalRequests: string[] = [];
  await page.route('https://reader-xss.invalid/**', async (route) => {
    unexpectedExternalRequests.push(route.request().url());
    await route.abort();
  });
  const apiResponses: Response[] = [];
  page.on('response', (response) => {
    if (new URL(response.url()).pathname.startsWith('/api/v1/')) apiResponses.push(response);
  });
  const responseFor = (method: string, path: string) =>
    apiResponses.find(
      (response) =>
        response.request().method() === method && new URL(response.url()).pathname === path,
    );

  await page.goto(`${baseUrl}/app/import`);
  await expect(page.getByRole('heading', { name: 'Mang câu chuyện của bạn về đây' })).toBeVisible();
  await expect(page.getByText(/Disposable test statement/)).toBeVisible();
  await page.locator('#work-file').setInputFiles({
    name: fixture.filename,
    mimeType: fixture.mimeType,
    buffer: Buffer.from(fixture.sourceText, 'utf8'),
  });
  await page.locator('.account-rights-check').click();
  const createResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/imports',
  );
  await page.getByRole('button', { name: 'Tải lên và phân tích' }).click();
  const createResponse = await createResponsePromise;
  expect(createResponse.status()).toBe(201);
  const createdImport = (await createResponse.json()) as { job: { id: string } };
  const importId = createdImport.job.id;

  await expect(page).toHaveURL(/\/app\/import\/review$/, { timeout: 90_000 });
  await expect(page.getByRole('heading', { name: 'Kiểm tra metadata và mục lục' })).toBeVisible();
  for (const value of fixture.forbiddenText) {
    await expect(page.locator('body')).not.toContainText(value);
  }
  await page.getByLabel('Tên tác phẩm').fill(workTitle);
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click();
  await expect(page.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();

  const commitResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/imports/${importId}/commit`,
    { timeout: 90_000 },
  );
  await page.getByRole('button', { name: 'Thêm vào thư viện riêng' }).click();
  const commitResponse = await commitResponsePromise;
  expect(commitResponse.status()).toBe(201);
  const committedWork = (await commitResponse.json()) as {
    id: string;
    title: string;
    visibility: string;
    chapterCount: number;
  };
  expect(committedWork).toMatchObject({
    title: workTitle,
    visibility: 'private',
    chapterCount: 1,
  });
  await expect(page.locator('.import-success')).toContainText('Đã thêm riêng tư vào thư viện');
  await page.screenshot({ path: screenshotPath('commit'), fullPage: true });

  await page.getByRole('link', { name: 'Mở thư viện' }).click();
  await expect(page).toHaveURL(/\/app\/library$/);
  await expect(page.getByRole('heading', { name: workTitle, exact: true })).toBeVisible({
    timeout: 30_000,
  });
  const workListResponse = responseFor('GET', '/api/v1/works');
  expect(workListResponse).toBeDefined();
  const workList = (await workListResponse!.json()) as {
    items: Array<{ id: string; title: string; visibility: string }>;
  };
  const workRow = page
    .locator('.library-work-row')
    .filter({ has: page.getByRole('heading', { name: workTitle, exact: true }) });
  await expect(workRow.getByText('RIÊNG TƯ · TÀI KHOẢN')).toBeVisible();
  await workRow.getByRole('link', { name: 'Chi tiết tác phẩm' }).click();

  await expect(page).toHaveURL(new RegExp(`/app/works/${committedWork.id}$`));
  await expect(page.getByRole('heading', { name: workTitle, exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /Đọc từ đầu/ })).toBeVisible();
  await assertBodyOmits(page, fixture.forbiddenText);
  await page.screenshot({ path: screenshotPath('detail'), fullPage: true });

  await page.getByRole('button', { name: /Đọc từ đầu/ }).click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${committedWork.id}/[0-9a-f-]+$`, 'i'));
  for (const value of fixture.visibleReaderText) {
    await expect(
      page.locator('.account-reader-body').getByText(value, { exact: true }),
    ).toBeVisible();
  }
  await assertBodyOmits(page, fixture.forbiddenText);
  await expect(
    page.locator(
      '.account-reader-body img, .account-reader-body svg, .account-reader-body script, .account-reader-body iframe',
    ),
  ).toHaveCount(0);
  expect(unexpectedExternalRequests).toEqual([]);
  expect(
    await page.evaluate(() => (window as Window & { readerInjected?: boolean }).readerInjected),
  ).toBeUndefined();

  const chapterId = new URL(page.url()).pathname.split('/').at(-1)!;
  const contentResponse = responseFor('GET', `/api/v1/chapters/${chapterId}/content`);
  expect(contentResponse, 'expected a PostgreSQL Reader content response').toBeDefined();
  expect(contentResponse!.status()).toBe(200);
  const readerContent = (await contentResponse!.json()) as {
    segments: Array<{ sourceText: string }>;
  };
  for (const value of fixture.visibleReaderText) {
    expect(readerContent.segments.map((segment) => segment.sourceText)).toContain(value);
  }
  for (const value of fixture.forbiddenText) {
    expect(readerContent.segments.some((segment) => segment.sourceText.includes(value))).toBe(
      false,
    );
  }

  await page.screenshot({ path: screenshotPath('reader'), fullPage: true });
  await page.reload();
  for (const value of fixture.visibleReaderText) {
    await expect(
      page.locator('.account-reader-body').getByText(value, { exact: true }),
    ).toBeVisible();
  }
  await assertBodyOmits(page, fixture.forbiddenText);
  await expect(
    page.locator(
      '.account-reader-body img, .account-reader-body svg, .account-reader-body script, .account-reader-body iframe',
    ),
  ).toHaveCount(0);
  expect(unexpectedExternalRequests).toEqual([]);
  expect(
    await page.evaluate(() => (window as Window & { readerInjected?: boolean }).readerInjected),
  ).toBeUndefined();

  for (const [method, path, status] of [
    ['GET', '/api/v1/imports/rights-statement/current', 200],
    ['POST', '/api/v1/imports/rights-statement/acceptance', 200],
    ['GET', '/api/v1/libraries', 200],
    ['POST', '/api/v1/imports', 201],
    ['PUT', `/api/v1/imports/${importId}/content`, 204],
    ['POST', `/api/v1/imports/${importId}/parse`, 202],
    ['GET', `/api/v1/imports/${importId}/preview`, 200],
    ['PATCH', `/api/v1/imports/${importId}/preview`, 200],
  ] as const) {
    const response = responseFor(method, path);
    expect(response, `expected an unmocked API response for ${method} ${path}`).toBeDefined();
    expect(response!.status()).toBe(status);
  }
  expect(workList.items).toContainEqual(
    expect.objectContaining({
      id: committedWork.id,
      title: workTitle,
      visibility: 'private',
    }),
  );
  expect(responseFor('POST', `/api/v1/imports/${importId}/commit`)?.status()).toBe(201);
  for (const path of [
    `/api/v1/works/${committedWork.id}/reader-manifest`,
    `/api/v1/works/${committedWork.id}/progress`,
  ]) {
    expect(responseFor('GET', path)?.status()).toBe(200);
  }
}

for (const fixture of fixtures) {
  test(`private ${fixture.name} import keeps hostile markup inert through PostgreSQL Reader`, async ({
    page,
  }) => runFormatJourney(page, fixture));
}
