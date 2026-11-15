import { expect, test } from './support';
import type { Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';
const hostileMarkup = [
  '<img src="https://reader-xss.invalid/pixel.png" onerror="window.readerInjected=true">',
  '<svg onload="window.readerInjected=true"><script>window.readerInjected=true</script><image href="https://reader-xss.invalid/vector.svg"></image></svg>',
  '<iframe src="https://reader-xss.invalid/frame"></iframe>',
  '<a href="javascript:window.readerInjected=true">run script</a>',
];
const sourceText = [
  'Synthetic private source created by the live import journey.',
  ...hostileMarkup,
].join('\n');
const workTitle = 'Live Private Import Novel';

test('private import commit refreshes Library, Work Detail and PostgreSQL Reader source', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const screenshotPath = process.env['PRIVATE_IMPORT_LIVE_TEST_SCREENSHOT'];

  test.skip(
    !sessionToken || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 to supply a disposable account and approved test statement',
  );
  expect(sessionToken, 'run with the disposable PostgreSQL fixture harness').toMatch(
    /^[A-Za-z0-9_-]{43}$/,
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
    name: 'live-private-work.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from(sourceText, 'utf8'),
  });
  await page.locator('.account-rights-check').click();
  await page.getByRole('button', { name: 'Tải lên và phân tích' }).click();

  await expect(page).toHaveURL(/\/app\/import\/review$/, { timeout: 90_000 });
  await expect(page.getByRole('heading', { name: 'Kiểm tra metadata và mục lục' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Thứ tự chương sẽ lưu' })).toContainText('Nội dung');
  await expect(page.getByText(sourceText, { exact: true })).toHaveCount(0);
  for (const payload of hostileMarkup) {
    await expect(page.getByText(payload, { exact: true })).toHaveCount(0);
  }
  const createResponse = responseFor('POST', '/api/v1/imports');
  expect(createResponse, 'expected an unmocked import-create response').toBeDefined();
  const createdImport = (await createResponse!.json()) as { job: { id: string } };
  const importId = createdImport.job.id;

  const ownerImportStatus = await page.request.get(`${baseUrl}/api/v1/imports/${importId}`);
  expect(ownerImportStatus.status()).toBe(200);
  expect(ownerImportStatus.headers()['cache-control']).toContain('no-store');
  expect(await ownerImportStatus.json()).toMatchObject({ id: importId });

  const ownerImportPreview = await page.request.get(
    `${baseUrl}/api/v1/imports/${importId}/preview`,
  );
  expect(ownerImportPreview.status()).toBe(200);
  expect(ownerImportPreview.headers()['cache-control']).toContain('no-store');
  expect((await ownerImportPreview.text()).length).toBeGreaterThan(0);

  const otherSessionToken = process.env['MOMO_COMMERCE_LIVE_TEST_OTHER_SESSION_TOKEN'];
  if (otherSessionToken) {
    expect(otherSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    await page.context().clearCookies();
    await page.context().addCookies([
      {
        name: '__Host-novel_session',
        value: otherSessionToken,
        domain: 'localhost',
        path: '/',
        httpOnly: true,
        secure: true,
        sameSite: 'Lax',
      },
    ]);

    const foreignImportStatus = await page.request.get(`${baseUrl}/api/v1/imports/${importId}`);
    expect(foreignImportStatus.status()).toBe(404);
    expect(foreignImportStatus.headers()['cache-control']).toContain('no-store');
    const foreignImportStatusBody = await foreignImportStatus.text();
    expect(foreignImportStatusBody).toContain('IMPORT_NOT_FOUND');
    expect(foreignImportStatusBody).not.toContain(sourceText);

    const foreignImportPreview = await page.request.get(
      `${baseUrl}/api/v1/imports/${importId}/preview`,
    );
    expect(foreignImportPreview.status()).toBe(404);
    expect(foreignImportPreview.headers()['cache-control']).toContain('no-store');
    const foreignImportPreviewBody = await foreignImportPreview.text();
    expect(foreignImportPreviewBody).toContain('IMPORT_NOT_FOUND');
    expect(foreignImportPreviewBody).not.toContain(sourceText);

    await page.context().clearCookies();
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
  }

  await page.getByLabel('Tên tác phẩm').fill(workTitle);
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click();
  await expect(page.getByRole('button', { name: 'Lưu thay đổi' })).toBeDisabled();

  const commitResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname.match(/^\/api\/v1\/imports\/[0-9a-f-]+\/commit$/i) !== null,
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
  await page.screenshot({ path: screenshotPath!, fullPage: true });

  await page.getByRole('link', { name: 'Mở thư viện' }).click();
  await expect(page).toHaveURL(/\/app\/library$/);
  await expect(page.getByRole('heading', { name: workTitle, exact: true })).toBeVisible({
    timeout: 30_000,
  });
  const workListResponse = responseFor('GET', '/api/v1/works');
  expect(workListResponse, 'expected an unmocked account catalog response').toBeDefined();
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
  await expect(page.getByText(sourceText, { exact: true })).toHaveCount(0);
  for (const payload of hostileMarkup) {
    await expect(page.getByText(payload, { exact: true })).toHaveCount(0);
  }
  await page.screenshot({
    path: screenshotPath!.replace(
      'private-import-commit-live.png',
      'private-import-detail-live.png',
    ),
    fullPage: true,
  });

  await page.getByRole('button', { name: /Đọc từ đầu/ }).click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${committedWork.id}/[0-9a-f-]+$`, 'i'));
  for (const payload of hostileMarkup) {
    await expect(
      page.locator('.account-reader-body').getByText(payload, { exact: true }),
    ).toBeVisible();
  }
  await expect(
    page.locator(
      '.account-reader-body img, .account-reader-body svg, .account-reader-body script, .account-reader-body iframe, .account-reader-body style, .account-reader-body link, .account-reader-body object, .account-reader-body embed',
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
  expect(readerContent.segments.map((segment) => segment.sourceText)).toEqual(
    expect.arrayContaining(hostileMarkup),
  );
  await page.screenshot({
    path: screenshotPath!.replace(
      'private-import-commit-live.png',
      'private-import-reader-live.png',
    ),
    fullPage: true,
  });

  await page.reload();
  for (const payload of hostileMarkup) {
    await expect(
      page.locator('.account-reader-body').getByText(payload, { exact: true }),
    ).toBeVisible();
  }
  await expect(
    page.locator(
      '.account-reader-body img, .account-reader-body svg, .account-reader-body script, .account-reader-body iframe, .account-reader-body style, .account-reader-body link, .account-reader-body object, .account-reader-body embed',
    ),
  ).toHaveCount(0);
  expect(unexpectedExternalRequests).toEqual([]);
  expect(
    await page.evaluate(() => (window as Window & { readerInjected?: boolean }).readerInjected),
  ).toBeUndefined();

  const paths = [
    ['GET', '/api/v1/imports/rights-statement/current', 200],
    ['POST', '/api/v1/imports/rights-statement/acceptance', 200],
    ['GET', '/api/v1/libraries', 200],
    ['POST', '/api/v1/imports', 201],
  ] as const;
  for (const [method, path, status] of paths) {
    const response = responseFor(method, path);
    expect(response, `expected an unmocked API response for ${method} ${path}`).toBeDefined();
    expect(response!.status()).toBe(status);
  }

  for (const [method, path, status] of [
    ['PUT', `/api/v1/imports/${importId}/content`, 204],
    ['POST', `/api/v1/imports/${importId}/parse`, 202],
    ['GET', `/api/v1/imports/${importId}/preview`, 200],
    ['PATCH', `/api/v1/imports/${importId}/preview`, 200],
  ] as const) {
    const response = responseFor(method, path);
    expect(response, `expected an unmocked API response for ${method} ${path}`).toBeDefined();
    expect(response!.status()).toBe(status);
  }

  const commitPath = new URL(commitResponse.url()).pathname;
  expect(responseFor('POST', commitPath)?.status()).toBe(201);
  expect(workList.items).toContainEqual(
    expect.objectContaining({
      id: committedWork.id,
      title: workTitle,
      visibility: 'private',
    }),
  );
  for (const path of [
    `/api/v1/works/${committedWork.id}/reader-manifest`,
    `/api/v1/works/${committedWork.id}/progress`,
  ]) {
    const response = responseFor('GET', path);
    expect(response, `expected an unmocked API response for GET ${path}`).toBeDefined();
    expect(response!.status()).toBe(200);
  }
});
