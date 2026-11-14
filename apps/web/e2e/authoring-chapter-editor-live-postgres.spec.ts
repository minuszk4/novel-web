import { expect, expectNoAxeViolations, test } from './support';

const baseUrl = 'http://localhost:4300';
const finalLocalText = 'Bản người viết sau xung đột.';
const externalText = 'Phiên bản mới của người viết khác.';

test('private chapter editor saves to PostgreSQL and resolves stale edits by explicit choice', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const workId = process.env['AUTHORING_EDITOR_LIVE_TEST_WORK_ID'];
  const chapterId = process.env['AUTHORING_EDITOR_LIVE_TEST_CHAPTER_ID'];
  const screenshotPath = process.env['AUTHORING_EDITOR_LIVE_TEST_SCREENSHOT'];
  test.skip(
    !sessionToken || !workId || !chapterId || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 -IncludeAuthoringOutline -IncludeAuthoringChapterEditor to supply the disposable authored-chapter fixture',
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

  const draftPath = `/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/draft`;
  const revisionsPath = `/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/revisions`;
  const initialDraftResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === draftPath,
  );
  await page.goto(`${baseUrl}/app/authoring/${workId}/chapters/${chapterId}`);
  const initialDraftResponse = await initialDraftResponsePromise;
  expect(initialDraftResponse.status()).toBe(200);
  expect(initialDraftResponse.headers()['cache-control']).toContain('no-store');
  expect(initialDraftResponse.headers().etag).toBe('"draft-0"');
  expect(await initialDraftResponse.json()).toMatchObject({
    id: chapterId,
    workId,
    status: 'draft',
    revisionNo: 0,
    sourceFormat: null,
    normalizedContent: '',
    segments: [],
  });
  await expect(page.getByRole('heading', { name: 'Live Outline Seed 26' })).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: 'Đã lưu · phiên bản 0' })).toBeVisible();
  await expectNoAxeViolations(page);

  await page.getByLabel('Văn bản bản thảo').fill('Bản đầu tiên được lưu.');
  const csrfResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/csrf',
  );
  const firstSaveResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === revisionsPath,
  );
  await page.getByRole('button', { name: 'Lưu ngay' }).click();
  const [csrfResponse, firstSaveResponse] = await Promise.all([
    csrfResponsePromise,
    firstSaveResponsePromise,
  ]);
  expect(csrfResponse.status()).toBe(200);
  expect(firstSaveResponse.status()).toBe(201);
  expect(firstSaveResponse.headers()['cache-control']).toContain('no-store');
  expect(firstSaveResponse.headers().etag).toBe('"draft-1"');
  const csrfToken = ((await csrfResponse.json()) as { token: string }).token;
  const firstHeaders = firstSaveResponse.request().headers();
  expect(firstHeaders['x-csrf-token']).toBe(csrfToken);
  expect(firstHeaders['if-match']).toBe('"draft-0"');
  expect(firstHeaders['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
  expect(firstSaveResponse.request().postDataJSON()).toEqual({
    sourceFormat: 'plain_text',
    normalizedContent: 'Bản đầu tiên được lưu.',
    segments: [
      {
        ordinal: 1,
        type: 'paragraph',
        sourceText: 'Bản đầu tiên được lưu.',
        stableAnchor: `chapter-${chapterId!.slice(0, 8)}-p1`,
      },
    ],
  });
  await expect(page.getByRole('status').filter({ hasText: 'Đã lưu · phiên bản 1' })).toBeVisible();

  const externalWrite = await page.request.post(`${baseUrl}${revisionsPath}`, {
    headers: {
      'X-CSRF-Token': csrfToken,
      'If-Match': '"draft-1"',
      'Idempotency-Key': 'editor-second-writer-key-0001',
    },
    data: {
      sourceFormat: 'plain_text',
      normalizedContent: externalText,
      segments: [
        {
          ordinal: 1,
          type: 'paragraph',
          sourceText: externalText,
          stableAnchor: 'external-writer-p1',
        },
      ],
    },
  });
  expect(externalWrite.status()).toBe(201);
  expect(externalWrite.headers()['cache-control']).toContain('no-store');
  expect(externalWrite.headers().etag).toBe('"draft-2"');

  await page.getByLabel('Văn bản bản thảo').fill(finalLocalText);
  const staleResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === revisionsPath &&
      response.status() === 412,
  );
  const staleResponse = await staleResponsePromise;
  expect((await staleResponse.json()).currentEtag).toBe('"draft-2"');
  await expect(page.getByText('Bản thảo đã đổi trên máy chủ', { exact: true })).toBeVisible();
  await expect(page.locator('#chapter-remote-content')).toHaveValue(externalText);
  await expect(page.getByLabel('Văn bản bản thảo')).toHaveValue(finalLocalText);
  await expectNoAxeViolations(page);

  const finalSaveResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === revisionsPath &&
      response.status() === 201,
  );
  await page.getByRole('button', { name: 'Ghi bản của tôi thành phiên bản tiếp theo' }).click();
  const finalSaveResponse = await finalSaveResponsePromise;
  expect(finalSaveResponse.headers().etag).toBe('"draft-3"');
  expect(finalSaveResponse.request().headers()['if-match']).toBe('"draft-2"');
  await expect(page.getByRole('status').filter({ hasText: 'Đã lưu · phiên bản 3' })).toBeVisible();
  await expect(page.getByLabel('Văn bản bản thảo')).toHaveValue(finalLocalText);

  const storedContent = await page.evaluate(() =>
    JSON.stringify({ local: Object.values(localStorage), session: Object.values(sessionStorage) }),
  );
  expect(storedContent).not.toContain(finalLocalText);
  const widths = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
  }));
  expect(widths.document).toBeLessThanOrEqual(widths.viewport);
  await expectNoAxeViolations(page);
  await page.screenshot({ path: screenshotPath!, fullPage: true });
});
