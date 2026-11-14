import { expect, test } from './support';
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
  await expect(
    page.getByText('Candidate đã sẵn sàng, nhưng hiện chưa có màn hình xem hoặc duyệt.'),
  ).toBeVisible({ timeout: 30_000 });
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

  const documentWidths = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(documentWidths.scroll).toBeLessThanOrEqual(documentWidths.client);
  await page.screenshot({ path: screenshotPath!, fullPage: true });
});
