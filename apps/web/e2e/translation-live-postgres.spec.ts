import { expect, expectNoAxeViolations, test } from './support';
import type { Response } from '@playwright/test';

const baseUrl = 'http://localhost:4300';
const sourceText = 'Live fake-local source segment from PostgreSQL.';

test('fake-local Reader preview reaches a private candidate through real API and PostgreSQL', async ({
  browser,
  page,
}) => {
  const sessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const workId = process.env['TRANSLATION_LIVE_TEST_WORK_ID'];
  const chapterId = process.env['TRANSLATION_LIVE_TEST_CHAPTER_ID'];
  const screenshotPath = process.env['TRANSLATION_LIVE_TEST_SCREENSHOT'];
  const reviewerSessionToken = process.env['TRANSLATION_REVIEW_LIVE_TEST_REVIEWER_SESSION_TOKEN'];
  const translationStage = process.env['TRANSLATION_REVIEW_LIVE_TEST_STAGE'] ?? 'all';
  const auditScreenshot = (state: string): string =>
    screenshotPath!.replace(/\.png$/i, `-${state}.png`);

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
  const systemStatusBody = (await systemStatus.json()) as {
    features: {
      translationFakePreviewEnabled: boolean;
      translationManualSubmissionEnabled: boolean;
      translationPublicationEnabled: boolean;
    };
  };
  expect(systemStatusBody.features.translationFakePreviewEnabled).toBe(true);
  if (reviewerSessionToken) {
    expect(systemStatusBody.features).toMatchObject({
      translationManualSubmissionEnabled: true,
      translationPublicationEnabled: true,
    });
  }

  if (translationStage === 'release') {
    const submissionId = process.env['TRANSLATION_REVIEW_LIVE_TEST_SUBMISSION_ID'];
    const snapshotDigest = process.env['TRANSLATION_REVIEW_LIVE_TEST_SNAPSHOT_DIGEST'];
    expect(reviewerSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(submissionId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(snapshotDigest).toMatch(/^[0-9a-f]{64}$/);
    const acceptedPath = `/api/v1/authoring/manuscripts/${workId}/translations/accepted`;
    const acceptedResponse = await page.request.get(`${baseUrl}${acceptedPath}?limit=20`);
    expect(acceptedResponse.status()).toBe(200);
    const accepted = (await acceptedResponse.json()) as {
      items: { submissionId: string; snapshotDigest: string; rightsCurrent: boolean }[];
    };
    expect(accepted.items).toContainEqual(
      expect.objectContaining({
        submissionId,
        snapshotDigest,
        rightsCurrent: true,
      }),
    );

    await page.goto(`${baseUrl}/app/authoring/${workId}/translations`);
    await expect(page.getByRole('heading', { name: 'Bản dịch đã được duyệt' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Phát hành bản dịch' })).toBeVisible();
    await page.getByRole('button', { name: 'Phát hành bản dịch' }).click();
    await expect(page.getByRole('heading', { name: 'Xác nhận phát hành' })).toBeVisible();
    const releaseResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname ===
          `/api/v1/authoring/translations/review-submissions/${submissionId}/release`,
    );
    await page.getByRole('button', { name: 'Xác nhận phát hành' }).click();
    const releaseResponse = await releaseResponsePromise;
    expect(releaseResponse.status()).toBe(201);
    expect(releaseResponse.headers()['cache-control']).toContain('no-store');
    expect(releaseResponse.request().postData()).toBeNull();
    expect(releaseResponse.request().headers()['x-csrf-token']).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(releaseResponse.request().headers()['if-match']).toBe(
      `"translation-review-${snapshotDigest}"`,
    );
    expect(releaseResponse.request().headers()['idempotency-key']).toMatch(
      /^[A-Za-z0-9._~-]{16,128}$/,
    );
    const releaseBody = (await releaseResponse.json()) as {
      versionId: string;
      versionNo: number;
      snapshotDigest: string;
      replayed: boolean;
    };
    expect(releaseBody).toMatchObject({ versionNo: 1, snapshotDigest, replayed: false });
    expect(releaseBody.versionId).toMatch(/^[0-9a-f-]{36}$/i);
    await expect(
      page.getByRole('heading', { name: 'Bản dịch phiên bản 1 đã được phát hành' }),
    ).toBeVisible();

    const languagesResponse = await page.request.get(
      `${baseUrl}/api/v1/chapters/${chapterId}/translations`,
    );
    expect(languagesResponse.status()).toBe(200);
    expect(languagesResponse.headers()['cache-control']).toContain('no-store');
    expect(await languagesResponse.json()).toEqual({ languages: ['vi'] });
    const translatedContentResponse = await page.request.get(
      `${baseUrl}/api/v1/chapters/${chapterId}/translations/vi/content?limit=10`,
    );
    expect(translatedContentResponse.status()).toBe(200);
    expect(translatedContentResponse.headers()['cache-control']).toContain('no-store');
    expect(await translatedContentResponse.json()).toMatchObject({
      chapterId,
      workId,
      translationVersionId: releaseBody.versionId,
      translationVersionNo: 1,
      targetLanguage: 'vi',
      segments: [{ translatedText: 'Writer correction persisted through the private edit API.' }],
      nextSegmentCursor: null,
    });
    return;
  }
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
  await page.screenshot({ path: auditScreenshot('candidate-360x800') });

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
  await page.screenshot({ path: auditScreenshot('candidate-editing-360x800') });
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

    const outsiderCsrfResponse = await page.request.get(`${baseUrl}/api/v1/auth/csrf`);
    expect(outsiderCsrfResponse.status()).toBe(200);
    const outsiderCsrf = (await outsiderCsrfResponse.json()) as { token: string };
    expect(outsiderCsrf.token).toMatch(/^[A-Za-z0-9_-]{43}$/);

    const foreignStatus = await page.request.get(`${baseUrl}${statusPath}`);
    expect(foreignStatus.status()).toBe(404);
    expect(foreignStatus.headers()['cache-control']).toContain('no-store');
    const foreignStatusBody = await foreignStatus.text();
    expect(foreignStatusBody).toContain('TRANSLATION_JOB_NOT_FOUND');
    expect(foreignStatusBody).not.toContain(sourceText);
    expect(foreignStatusBody).not.toContain('Live Translation Chapter');

    const foreignCandidate = await page.request.get(`${baseUrl}${statusPath}/candidate`);
    expect(foreignCandidate.status()).toBe(404);
    expect(foreignCandidate.headers()['cache-control']).toContain('no-store');
    const foreignCandidateBody = await foreignCandidate.text();
    expect(foreignCandidateBody).toContain('TRANSLATION_CANDIDATE_NOT_FOUND');
    expect(foreignCandidateBody).not.toContain(sourceText);
    expect(foreignCandidateBody).not.toContain(`[fake en->vi] ${sourceText}`);
    expect(foreignCandidateBody).not.toContain(editText);

    const foreignEdit = await page.request.put(
      `${baseUrl}${statusPath}/candidate/segments/${candidateBody.segments[0].sourceSegmentId}/edit`,
      {
        headers: {
          'X-CSRF-Token': outsiderCsrf.token,
          'If-Match': '"edit-1"',
          'Idempotency-Key': 'outsider-translation-edit-0001',
        },
        data: { translatedText: 'Outsider must not replace this candidate.' },
      },
    );
    expect(foreignEdit.status()).toBe(404);
    expect(foreignEdit.headers()['cache-control']).toContain('no-store');
    const foreignEditBody = await foreignEdit.text();
    expect(foreignEditBody).toContain('TRANSLATION_CANDIDATE_NOT_FOUND');
    expect(foreignEditBody).not.toContain('Outsider must not replace this candidate.');

    for (const action of ['retry', 'cancel'] as const) {
      const foreignAction = await page.request.post(`${baseUrl}${statusPath}/${action}`, {
        headers: {
          'X-CSRF-Token': outsiderCsrf.token,
          'Idempotency-Key': `outsider-translation-${action}-0001`,
        },
      });
      expect(foreignAction.status()).toBe(404);
      expect(foreignAction.headers()['cache-control']).toContain('no-store');
      const foreignActionBody = await foreignAction.text();
      expect(foreignActionBody).toContain('TRANSLATION_JOB_NOT_FOUND');
      expect(foreignActionBody).not.toContain(sourceText);
    }

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
    const unchangedOwnerStatus = await page.request.get(`${baseUrl}${statusPath}`);
    expect(unchangedOwnerStatus.status()).toBe(200);
    expect(unchangedOwnerStatus.headers()['cache-control']).toContain('no-store');
    expect(await unchangedOwnerStatus.json()).toMatchObject({
      job: {
        id: enqueueBody.jobId,
        status: 'review_required',
        completedUnits: 1,
        failedUnits: 0,
      },
    });
    const unchangedOwnerCandidate = await page.request.get(`${baseUrl}${statusPath}/candidate`);
    expect(unchangedOwnerCandidate.status()).toBe(200);
    expect(unchangedOwnerCandidate.headers()['cache-control']).toContain('no-store');
    const unchangedOwnerCandidateBody =
      (await unchangedOwnerCandidate.json()) as typeof candidateBody;
    expect(unchangedOwnerCandidateBody.segments[0]).toMatchObject({
      editedText: editText,
      editRevision: 1,
      editEtag: '"edit-1"',
    });
  }

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
  await page.screenshot({ path: auditScreenshot('translation-desk-360x800') });

  const narrowContext = await browser.newContext({
    viewport: { width: 180, height: 400 },
    deviceScaleFactor: 2,
  });
  try {
    const narrowPage = await narrowContext.newPage();
    await narrowPage.context().addCookies([
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
    await narrowPage.goto(`${baseUrl}/app/translation/jobs/${enqueueBody.jobId}/candidate`);
    await expect(narrowPage.getByRole('heading', { name: 'Đối chiếu candidate' })).toBeVisible();
    await expectNoAxeViolations(narrowPage);
    const narrowDimensions = await narrowPage.evaluate(() => ({
      cssWidth: window.innerWidth,
      devicePixelRatio: window.devicePixelRatio,
      documentWidth: document.documentElement.scrollWidth,
      bodyWidth: document.body.scrollWidth,
    }));
    expect(narrowDimensions).toEqual({
      cssWidth: 180,
      devicePixelRatio: 2,
      documentWidth: 180,
      bodyWidth: 180,
    });
    await narrowPage.screenshot({ path: auditScreenshot('candidate-effective-200-180x400-dpr2') });
  } finally {
    await narrowContext.close();
  }

  if (reviewerSessionToken) {
    expect(reviewerSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    const setSession = async (token: string) => {
      await page.context().clearCookies({ name: '__Host-novel_session' });
      await page.context().addCookies([
        {
          name: '__Host-novel_session',
          value: token,
          domain: 'localhost',
          path: '/',
          httpOnly: true,
          secure: true,
          sameSite: 'Lax',
        },
      ]);
    };
    const getCsrf = async (): Promise<string> => {
      const response = await page.request.get(`${baseUrl}/api/v1/auth/csrf`);
      expect(response.status()).toBe(200);
      const body = (await response.json()) as { token: string };
      expect(body.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
      return body.token;
    };

    const readinessPath = `/api/v1/translation/jobs/${enqueueBody.jobId}/candidate/review-readiness`;
    const readinessResponse = await page.request.get(`${baseUrl}${readinessPath}`);
    expect(readinessResponse.status()).toBe(200);
    expect(readinessResponse.headers()['cache-control']).toContain('no-store');
    const readiness = (await readinessResponse.json()) as {
      readyForSubmission: boolean;
      editedSegments: number;
      totalSegments: number;
      pendingReview: boolean;
      missingSegmentOrdinals: number[];
    };
    expect(readiness).toMatchObject({
      readyForSubmission: true,
      editedSegments: 1,
      totalSegments: 1,
      pendingReview: false,
      missingSegmentOrdinals: [],
    });
    const ownerCsrf = await getCsrf();
    const submissionResponse = await page.request.post(
      `${baseUrl}${readinessPath.replace('/review-readiness', '/review-submissions')}`,
      {
        headers: {
          'X-CSRF-Token': ownerCsrf,
          'If-Match': readinessResponse.headers().etag!,
          'Idempotency-Key': `translation-review-${enqueueBody.jobId}`,
        },
      },
    );
    expect(submissionResponse.status()).toBe(201);
    expect(submissionResponse.headers()['cache-control']).toContain('no-store');
    const submissionBody = (await submissionResponse.json()) as {
      submissionId: string;
      status: string;
      segmentCount: number;
      snapshotDigest: string;
    };
    expect(submissionBody).toMatchObject({ status: 'pending_review', segmentCount: 1 });
    expect(submissionBody.submissionId).toMatch(/^[0-9a-f-]{36}$/i);
    expect(submissionBody.snapshotDigest).toMatch(/^[0-9a-f]{64}$/);

    await setSession(reviewerSessionToken);
    const queueResponse = await page.request.get(`${baseUrl}/api/v1/review/translations`);
    expect(queueResponse.status()).toBe(200);
    expect(queueResponse.headers()['cache-control']).toContain('no-store');
    const queue = (await queueResponse.json()) as {
      items: { submissionId: string; sourceCurrent: boolean; segmentCount: number }[];
      nextCursor: string | null;
    };
    expect(queue.items).toContainEqual({
      submissionId: submissionBody.submissionId,
      sourceCurrent: true,
      segmentCount: 1,
      workTitle: expect.any(String),
      chapterTitle: 'Live Translation Chapter',
      sourceLanguage: 'en',
      targetLanguage: 'vi',
      submitterDisplayName: expect.any(String),
      submittedAt: expect.any(String),
    });
    expect(queue.nextCursor).toBeNull();
    await page.goto(`${baseUrl}/app/review/translations`);
    await expect(page.getByRole('heading', { name: 'Hàng đợi duyệt bản dịch' })).toBeVisible();
    await expect(
      page.getByRole('link', { name: /Live E2E Novel Two · Live Translation Chapter/ }),
    ).toBeVisible();
    await page.getByRole('link', { name: 'Đối chiếu' }).click();
    await expect(page.getByRole('heading', { name: 'Live E2E Novel Two' })).toBeVisible();
    await expect(page.getByText(editText, { exact: true })).toBeVisible();
    await expect(page.getByText('Chấp thuận không xuất bản')).toBeVisible();
    await expectNoAxeViolations(page);

    const detailPath = `/api/v1/review/translations/${submissionBody.submissionId}`;
    const detailResponse = await page.request.get(`${baseUrl}${detailPath}`);
    expect(detailResponse.status()).toBe(200);
    expect(detailResponse.headers()['cache-control']).toContain('no-store');
    expect(detailResponse.headers().etag).toBe(
      `"translation-review-${submissionBody.snapshotDigest}"`,
    );
    const detail = (await detailResponse.json()) as {
      status: string;
      canDecide: boolean;
      segments: { sourceText: string; submittedText: string }[];
    };
    expect(detail).toMatchObject({
      status: 'pending_review',
      canDecide: true,
      segments: [{ sourceText, submittedText: editText }],
    });
    const reviewerCsrf = await getCsrf();
    const decisionResponse = await page.request.post(`${baseUrl}${detailPath}/decisions`, {
      headers: {
        'X-CSRF-Token': reviewerCsrf,
        'If-Match': detailResponse.headers().etag!,
        'Idempotency-Key': `translation-decision-${submissionBody.submissionId}`,
        'Content-Type': 'application/json',
      },
      data: { actionCode: 'ACCEPT', reasonCode: 'ACCURATE' },
    });
    expect(decisionResponse.status()).toBe(201);
    expect(decisionResponse.headers()['cache-control']).toContain('no-store');
    expect(await decisionResponse.json()).toMatchObject({
      submissionId: submissionBody.submissionId,
      actionCode: 'ACCEPT',
      reasonCode: 'ACCURATE',
      snapshotDigest: submissionBody.snapshotDigest,
      replayed: false,
    });
    await page.reload();
    await expect(page.getByText('Đã duyệt · chưa xuất bản')).toBeVisible();

    if (translationStage === 'accept') return;

    await setSession(sessionToken!);
    const acceptedPath = `/api/v1/authoring/manuscripts/${workId}/translations/accepted`;
    const acceptedResponse = await page.request.get(`${baseUrl}${acceptedPath}?limit=20`);
    expect(acceptedResponse.status()).toBe(200);
    expect(acceptedResponse.headers()['cache-control']).toContain('no-store');
    const accepted = (await acceptedResponse.json()) as {
      items: {
        submissionId: string;
        chapterId: string;
        snapshotDigest: string;
        rightsCurrent: boolean;
      }[];
    };
    expect(accepted.items).toContainEqual({
      submissionId: submissionBody.submissionId,
      chapterId,
      snapshotDigest: submissionBody.snapshotDigest,
      rightsCurrent: true,
      segmentCount: 1,
      workTitle: expect.any(String),
      chapterTitle: 'Live Translation Chapter',
      targetLanguage: 'vi',
      submittedAt: expect.any(String),
      currentVersionId: null,
      currentVersionNo: null,
    });
    await page.goto(`${baseUrl}/app/authoring/${workId}/translations`);
    await expect(page.getByRole('heading', { name: 'Bản dịch đã được duyệt' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Phát hành bản dịch' })).toBeVisible();
    await page.getByRole('button', { name: 'Phát hành bản dịch' }).click();
    await expect(page.getByRole('heading', { name: 'Xác nhận phát hành' })).toBeVisible();

    const releaseResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname ===
          `/api/v1/authoring/translations/review-submissions/${submissionBody.submissionId}/release`,
    );
    await page.getByRole('button', { name: 'Xác nhận phát hành' }).click();
    const releaseResponse = await releaseResponsePromise;
    expect(releaseResponse.status()).toBe(201);
    expect(releaseResponse.headers()['cache-control']).toContain('no-store');
    expect(releaseResponse.request().postData()).toBeNull();
    expect(releaseResponse.request().headers()['x-csrf-token']).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(releaseResponse.request().headers()['if-match']).toBe(
      `"translation-review-${submissionBody.snapshotDigest}"`,
    );
    expect(releaseResponse.request().headers()['idempotency-key']).toMatch(
      /^[A-Za-z0-9._~-]{16,128}$/,
    );
    const releaseBody = (await releaseResponse.json()) as {
      versionId: string;
      versionNo: number;
      snapshotDigest: string;
      replayed: boolean;
    };
    expect(releaseBody).toMatchObject({
      versionNo: 1,
      snapshotDigest: submissionBody.snapshotDigest,
      replayed: false,
    });
    expect(releaseBody.versionId).toMatch(/^[0-9a-f-]{36}$/i);
    await expect(
      page.getByRole('heading', { name: 'Bản dịch phiên bản 1 đã được phát hành' }),
    ).toBeVisible();

    const languagesResponse = await page.request.get(
      `${baseUrl}/api/v1/chapters/${chapterId}/translations`,
    );
    expect(languagesResponse.status()).toBe(200);
    expect(languagesResponse.headers()['cache-control']).toContain('no-store');
    expect(await languagesResponse.json()).toEqual({ languages: ['vi'] });
    const translatedContentResponse = await page.request.get(
      `${baseUrl}/api/v1/chapters/${chapterId}/translations/vi/content?limit=10`,
    );
    expect(translatedContentResponse.status()).toBe(200);
    expect(translatedContentResponse.headers()['cache-control']).toContain('no-store');
    expect(await translatedContentResponse.json()).toMatchObject({
      chapterId,
      workId,
      translationVersionId: releaseBody.versionId,
      translationVersionNo: 1,
      targetLanguage: 'vi',
      segments: [{ translatedText: editText }],
      nextSegmentCursor: null,
    });
  }
});
