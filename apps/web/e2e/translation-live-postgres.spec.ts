import { expect, expectNoAxeViolations, test } from './support';
import type { Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';
const sourceText = 'Live fake-local source segment from PostgreSQL.';

test('fake-local Reader preview reaches a private candidate through real API and PostgreSQL', async ({
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const workId = process.env['TRANSLATION_LIVE_TEST_WORK_ID'];
  const chapterId = process.env['TRANSLATION_LIVE_TEST_CHAPTER_ID'];
  const screenshotPath = process.env['TRANSLATION_LIVE_TEST_SCREENSHOT'];

  test.skip(
    !sessionToken || !workId || !chapterId || !screenshotPath,
    'run tools/e2e/run-library-postgres.ps1 -IncludeTranslationPreview to supply the disposable fake-local translation fixture',
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

  const systemStatusPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/system/status',
  );
  await page.goto(`${baseUrl}/app/reader/${workId}/${chapterId}`);
  const systemStatus = await systemStatusPromise;
  expect(systemStatus.status()).toBe(200);
  expect((await systemStatus.json()).features.translationFakePreviewEnabled).toBe(true);
  await expect(page.getByRole('heading', { name: 'Live Translation Chapter' })).toBeVisible();
  await expect(page.getByText(sourceText, { exact: true })).toBeVisible();
  await expect(page.getByText('BẢN THỬ · KẾT QUẢ GIẢ')).toBeVisible();
  await expect(
    page.getByText('Không gọi dịch vụ ngoài, không tính phí; nội dung trả về chỉ là placeholder.'),
  ).toBeVisible();

  const csrfPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/auth/csrf',
  );
  const enqueuePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/translation/jobs',
  );
  await page.getByRole('button', { name: 'Tạo bản xem thử' }).click();
  const [csrf, enqueue] = await Promise.all([csrfPromise, enqueuePromise]);
  expect(csrf.status()).toBe(200);
  expect(enqueue.status()).toBe(202);

  const csrfToken = ((await csrf.json()) as { token: string }).token;
  const enqueueRequestHeaders = enqueue.request().headers();
  expect(enqueueRequestHeaders['x-csrf-token']).toBe(csrfToken);
  expect(enqueueRequestHeaders['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
  expect(enqueue.request().postDataJSON()).toEqual({
    chapterId,
    sourceRevisionId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
    targetLanguage: 'vi',
  });
  const enqueueBody = (await enqueue.json()) as {
    jobId: string;
    providerId: string;
    status: string;
    replayed: boolean;
  };
  expect(enqueueBody).toMatchObject({ providerId: 'fake-local', replayed: false });
  expect(enqueueBody.status).toBe('queued');
  expect(enqueueBody.jobId).toMatch(/^[0-9a-f-]{36}$/i);

  await expect(page).toHaveURL(new RegExp(`/app/translation/jobs/${enqueueBody.jobId}$`));
  await expect(page.getByRole('heading', { name: 'Live Translation Chapter' })).toBeVisible();
  await expect(page.getByText('Candidate giả đã sẵn sàng để đối chiếu.')).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText('KẾT QUẢ GIẢ · BẢN XEM THỬ CỤC BỘ')).toBeVisible();
  await expect(
    page.getByText(/không gửi chương ra dịch vụ ngoài và không tính phí/i),
  ).toBeVisible();
  await expect(page.getByRole('link', { name: 'Quay lại chương' })).toHaveAttribute(
    'href',
    `/app/reader/${workId}/${chapterId}`,
  );
  await expect(page.getByText(sourceText, { exact: true })).toHaveCount(0);

  const statusPath = `/api/v1/translation/jobs/${enqueueBody.jobId}`;
  await expect
    .poll(
      async () => {
        const latestStatus = [...apiResponses]
          .reverse()
          .find(
            (response) =>
              response.request().method() === 'GET' &&
              new URL(response.url()).pathname === statusPath,
          );
        if (!latestStatus) return null;
        return ((await latestStatus.json()) as { job?: { status?: string } }).job?.status ?? null;
      },
      { timeout: 30_000 },
    )
    .toBe('review_required');
  const statusResponse = [...apiResponses]
    .reverse()
    .find(
      (response) =>
        response.request().method() === 'GET' && new URL(response.url()).pathname === statusPath,
    )!;
  const statusText = await statusResponse.text();
  const statusBody = JSON.parse(statusText) as {
    job: {
      providerId: string;
      status: string;
      sourceRevisionCurrent: boolean;
      totalUnits: number;
      completedUnits: number;
      failedUnits: number;
      candidateId: string | null;
      candidateStatus: string | null;
    };
    terminalUnits: number;
    progressPercent: number;
  };
  expect(statusResponse.headers()['cache-control']).toContain('no-store');
  expect(statusResponse.headers().pragma).toBe('no-cache');
  expect(statusBody).toMatchObject({
    job: {
      providerId: 'fake-local',
      status: 'review_required',
      sourceRevisionCurrent: true,
      totalUnits: 1,
      completedUnits: 1,
      failedUnits: 0,
      candidateStatus: 'ready_for_review',
    },
    terminalUnits: 1,
    progressPercent: 100,
  });
  expect(statusBody.job.candidateId).toMatch(/^[0-9a-f-]{36}$/i);
  expect(statusText).not.toContain(sourceText);
  expect(statusText).not.toMatch(/"(?:sourceText|translatedText|segments|content)"\s*:/i);

  const candidatePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `${statusPath}/candidate`,
  );
  await page.getByRole('link', { name: 'Mở đối chiếu bản giả' }).click();
  const candidateResponse = await candidatePromise;
  expect(candidateResponse.status()).toBe(200);
  expect(candidateResponse.headers()['cache-control']).toContain('no-store');
  const candidateText = await candidateResponse.text();
  const candidateBody = JSON.parse(candidateText) as {
    jobId: string;
    candidateId: string;
    providerId: string;
    candidateStatus: string;
    sourceRevisionCurrent: boolean;
    pageOffset: number;
    pageLimit: number;
    totalSegments: number;
    segments: {
      sourceSegmentId: string;
      sourceText: string;
      fakeResultText: string;
      editedText: string | null;
      editRevision: number;
      editEtag: string;
    }[];
  };
  expect(candidateBody).toMatchObject({
    jobId: enqueueBody.jobId,
    providerId: 'fake-local',
    candidateStatus: 'ready_for_review',
    sourceRevisionCurrent: true,
    pageOffset: 0,
    pageLimit: 10,
    totalSegments: 1,
  });
  expect(candidateBody.segments).toEqual([
    {
      sourceSegmentId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
      ordinal: 1,
      sourceType: 'paragraph',
      sourceText,
      fakeResultText: `[fake en->vi] ${sourceText}`,
      editedText: null,
      editRevision: 0,
      editEtag: '"edit-0"',
    },
  ]);
  expect(candidateText).not.toMatch(/"(?:ownerId|sourcePath|translatedHtml|html)"\s*:/i);
  await expect(page.getByRole('heading', { name: 'Đối chiếu candidate' })).toBeVisible();
  await expect(page.getByText('Đây không phải bản dịch.')).toBeVisible();
  await expect(page.getByText(sourceText, { exact: true })).toBeVisible();
  await expect(page.getByText(`[fake en->vi] ${sourceText}`, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Phần trước' })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Phần tiếp' })).toBeDisabled();
  await expectNoAxeViolations(page);

  const editText = 'Writer correction persisted through the private edit API.';
  const editResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname ===
        `${statusPath}/candidate/segments/${candidateBody.segments[0].sourceSegmentId}/edit`,
  );
  const refreshedCandidatePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `${statusPath}/candidate`,
  );
  await page.getByRole('button', { name: 'Sửa đoạn này' }).click();
  const candidateEditor = page.getByRole('textbox', { name: 'Bản nháp chỉnh sửa cho phần 1' });
  await expect(candidateEditor).toBeFocused();
  await candidateEditor.fill(editText);
  await page.getByRole('button', { name: 'Lưu chỉnh sửa' }).click();
  const editResponse = await editResponsePromise;
  expect(editResponse.status()).toBe(201);
  expect(editResponse.headers()['cache-control']).toContain('no-store');
  expect(editResponse.headers().etag).toBe('"edit-1"');
  expect(editResponse.request().headers()['x-csrf-token']).toBe(csrfToken);
  expect(editResponse.request().headers()['if-match']).toBe('"edit-0"');
  expect(editResponse.request().headers()['idempotency-key']).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
  expect(editResponse.request().postDataJSON()).toEqual({ translatedText: editText });
  expect(await editResponse.json()).toMatchObject({
    jobId: enqueueBody.jobId,
    candidateId: candidateBody.candidateId,
    sourceSegmentId: candidateBody.segments[0].sourceSegmentId,
    editRevision: 1,
    translatedText: editText,
    replayed: false,
  });
  const refreshedCandidate = await refreshedCandidatePromise;
  expect(refreshedCandidate.status()).toBe(200);
  expect(refreshedCandidate.headers()['cache-control']).toContain('no-store');
  const refreshedBody = (await refreshedCandidate.json()) as typeof candidateBody;
  expect(refreshedBody.segments[0]).toMatchObject({
    sourceText,
    fakeResultText: `[fake en->vi] ${sourceText}`,
    editedText: editText,
    editRevision: 1,
    editEtag: '"edit-1"',
  });
  await expect(page.getByText(editText, { exact: true })).toBeVisible();
  await expect(page.getByText('Bản 1 đã lưu · chưa xuất bản')).toBeVisible();
  await expect(page.getByText(sourceText, { exact: true })).toBeVisible();
  await expect(page.getByText(`[fake en->vi] ${sourceText}`, { exact: true })).toBeVisible();
  await expectNoAxeViolations(page);

  const deskListPromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === '/api/v1/translation/jobs' &&
      new URL(response.url()).searchParams.get('limit') === '25',
  );
  await page.getByRole('link', { name: 'Về trạng thái job' }).click();
  await expect(page.getByRole('heading', { name: /Live Translation Chapter/ })).toBeVisible();
  await expect(page.locator('.mobile-nav').getByRole('link', { name: 'Bàn dịch' })).toBeVisible();
  await page.getByRole('link', { name: 'Về Bàn dịch' }).click();
  const deskListResponse = await deskListPromise;
  expect(deskListResponse.status()).toBe(200);
  expect(deskListResponse.headers()['cache-control']).toContain('no-store');
  expect(deskListResponse.headers().pragma).toBe('no-cache');
  const deskListText = await deskListResponse.text();
  const deskListBody = JSON.parse(deskListText) as {
    items: { job: { id: string; status: string; chapterTitle: string } }[];
    nextCursor: string | null;
  };
  expect(deskListBody.items.some((item) => item.job.id === enqueueBody.jobId)).toBe(true);
  expect(deskListText).not.toContain(sourceText);
  expect(deskListText).not.toMatch(
    /"(?:sourceText|fakeResultText|translatedText|segments|content)"\s*:/i,
  );
  await expect(page.getByRole('heading', { name: 'Bàn dịch' })).toBeVisible();
  await expect(page.getByRole('heading', { name: /Live Translation Chapter/ })).toBeVisible();
  await expect(page.getByText('Bản thử cục bộ · kết quả giả.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Đối chiếu bản giả' })).toHaveAttribute(
    'href',
    `/app/translation/jobs/${enqueueBody.jobId}/candidate`,
  );
  await expect(page.getByText(sourceText, { exact: true })).toHaveCount(0);
  await expectNoAxeViolations(page);

  const documentWidths = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(documentWidths.scroll).toBeLessThanOrEqual(documentWidths.client);
  await page.screenshot({
    path: test.info().outputPath('translation-desk-live.png'),
    fullPage: true,
  });
  await page.screenshot({ path: screenshotPath!, fullPage: true });
});
