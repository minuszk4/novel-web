import { randomUUID } from 'node:crypto';
import { expect, expectNoAxeViolations, test } from './support';

const baseUrl = 'http://localhost:4300';

test('reviewer approval and owner-confirmed staged chapter release use the real API and PostgreSQL', async ({
  page,
}) => {
  const ownerSessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const reviewerSessionToken = process.env['PUBLICATION_REVIEW_LIVE_TEST_REVIEWER_SESSION_TOKEN'];
  const workId = process.env['PUBLICATION_REVIEW_LIVE_TEST_WORK_ID'];
  const screenshotPath = process.env['PUBLICATION_REVIEW_LIVE_TEST_SCREENSHOT'];
  const chapterScreenshotPath = process.env['PUBLICATION_REVIEW_LIVE_TEST_CHAPTER_SCREENSHOT'];

  test.skip(
    !ownerSessionToken ||
      !reviewerSessionToken ||
      !workId ||
      !screenshotPath ||
      !chapterScreenshotPath,
    'run tools/e2e/run-library-postgres.ps1 -IncludePublicationReviewWorkspace for disposable reviewer and manuscript fixtures',
  );
  expect(ownerSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(reviewerSessionToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(workId).toMatch(/^[0-9a-f-]{36}$/i);

  await page.setViewportSize({ width: 360, height: 800 });
  const addSession = async (value: string) => {
    await page.context().clearCookies({ name: '__Host-novel_session' });
    await page.context().addCookies([
      {
        name: '__Host-novel_session',
        value,
        domain: 'localhost',
        path: '/',
        httpOnly: true,
        secure: true,
        sameSite: 'Lax',
      },
    ]);
  };

  // Create a real pending request with the owner session before switching to the reviewer.
  await addSession(ownerSessionToken!);
  const csrfResponse = await page.request.get(`${baseUrl}/api/v1/auth/csrf`);
  expect(csrfResponse.status()).toBe(200);
  const csrf = (await csrfResponse.json()) as { token: string };
  expect(csrf.token).toMatch(/^[A-Za-z0-9_-]{43}$/);

  const policyPath = `/api/v1/authoring/manuscripts/${workId}/publication/rights-policy`;
  const policyResponse = await page.request.get(`${baseUrl}${policyPath}`);
  expect(policyResponse.status()).toBe(200);
  const policy = (await policyResponse.json()) as {
    version: string;
    policySha256: string;
  };
  expect(policy.version).toMatch(/^rights-live-e2e-/);
  expect(policy.policySha256).toMatch(/^[0-9a-f]{64}$/);

  const accepted = await page.request.post(`${baseUrl}${policyPath}/acceptance`, {
    data: { policyVersion: policy.version, confirmed: true },
    headers: {
      'X-CSRF-Token': csrf.token,
      'Idempotency-Key': randomUUID(),
    },
  });
  expect(accepted.status()).toBe(200);

  await page.goto(`${baseUrl}/app/authoring/${workId}/chapters`);
  await expect(page.getByRole('heading', { name: 'Live E2E Novel Two' })).toBeVisible();
  await page.getByRole('button', { name: 'Kiểm tra điều kiện xuất bản' }).click();
  await expect(page.getByRole('heading', { name: 'Yêu cầu người duyệt xem xét' })).toBeVisible();
  await page.getByRole('button', { name: 'Chuẩn bị yêu cầu duyệt' }).click();
  await expect(
    page.getByRole('heading', { name: 'Rà soát bản đã lưu và khai báo phạm vi' }),
  ).toBeFocused();
  await page.locator('#publication-request-metadata-basis').selectOption('ORIGINAL_WORK');
  await page.locator('#publication-request-chapter-basis').selectOption('ORIGINAL_WORK');
  await page.getByLabel(/Tôi cũng yêu cầu xem xét quyền thương mại/).check();
  await page.locator('#publication-request-commercial-basis').selectOption('ORIGINAL_WORK');
  await page.getByLabel(/Tôi cũng yêu cầu xem xét quyền chuyển ngữ/).check();
  await page
    .locator('#publication-request-translation-adaptation-basis')
    .selectOption('ORIGINAL_WORK');
  await page.getByLabel(/Tôi đã rà soát các phạm vi/).check();

  const submissionResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname.endsWith('/publication/requests'),
  );
  await page.getByRole('button', { name: 'Gửi yêu cầu duyệt' }).click();
  const submission = await submissionResponse;
  expect(submission.status()).toBe(201);
  const submissionRequest = submission.request();
  const request = (await submission.json()) as { requestId: string };
  expect(submissionRequest.postDataJSON()).toEqual({
    policyVersion: policy.version,
    scopeClaims: [
      { scopeCode: 'PUBLIC_METADATA', rightsBasisCode: 'ORIGINAL_WORK' },
      { scopeCode: 'CHAPTER_TEXT', rightsBasisCode: 'ORIGINAL_WORK' },
      { scopeCode: 'COMMERCIAL_SALE', rightsBasisCode: 'ORIGINAL_WORK' },
      { scopeCode: 'TRANSLATION_ADAPTATION', rightsBasisCode: 'ORIGINAL_WORK' },
    ],
  });
  expect(submissionRequest.headers()['if-match']).toMatch(/^"publication-snapshot-[a-f0-9]{64}"$/);
  expect(submissionRequest.headers()['x-csrf-token']).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(submissionRequest.headers()['idempotency-key']).toMatch(/^[A-Za-z0-9_-]{16,128}$/);
  expect(submission.headers()['cache-control']).toBe('no-store');
  expect(submission.headers()['pragma']).toBe('no-cache');
  await expect(page.locator('.publication-readiness-summary dd').nth(2)).toHaveText(
    'Đang chờ người duyệt',
  );
  expect(request.requestId).toMatch(/^[0-9a-f-]{36}$/i);

  await addSession(reviewerSessionToken!);
  await page.goto(`${baseUrl}/app/review`);
  await expect(page.getByRole('heading', { name: 'Hàng đợi duyệt quyền xuất bản' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Điều hướng di động' })).toContainText('Duyệt');
  await expect(
    page.getByRole('link', { name: 'Xem yêu cầu của Live E2E Novel Two' }),
  ).toBeVisible();
  await expectNoAxeViolations(page);

  await page.getByRole('link', { name: 'Xem yêu cầu của Live E2E Novel Two' }).click();
  await expect(page).toHaveURL(new RegExp(`/app/review/${request.requestId}$`));
  await expect(page.getByText('Chỉ có metadata để xem xét.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Live E2E Novel Two' })).toBeVisible();
  await expect(page.getByText('Private chapter prose for reviewer queue redaction.')).toHaveCount(
    0,
  );
  const snapshotDigest = page.locator('.snapshot-digest').first();
  await snapshotDigest.locator('summary').click();
  await expect(snapshotDigest.locator('code')).toBeVisible();
  const chapterPreview = page.locator('.chapter-preview').first();
  await chapterPreview.locator('summary').click();
  await expect(page.getByText('Live Translation Chapter').first()).toBeVisible();
  await expectNoAxeViolations(page);
  await expectNoHorizontalOverflow(page);

  await page.getByRole('button', { name: 'Chấp thuận quyền' }).click();
  const decisionDialog = page.getByRole('dialog', { name: 'Chấp thuận quyền cho snapshot này?' });
  await expect(decisionDialog).toBeVisible();
  await decisionDialog.getByLabel('Đã xem xét khai báo quyền').check();
  await expectNoAxeViolations(page);
  const decisionResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname ===
        `/api/v1/review/publications/${request.requestId}/decisions`,
  );
  await decisionDialog.getByRole('button', { name: 'Xác nhận quyết định' }).click();
  expect((await decisionResponse).status()).toBe(201);
  await expect(page.locator('.publication-review-result')).toContainText(
    'Đã ghi nhận: Được duyệt · Chưa công khai.',
  );
  await expect(page.getByText('Được duyệt · Chưa công khai').first()).toBeVisible();
  await expect(page.getByRole('button', { name: 'Chấp thuận quyền' })).toHaveCount(0);
  await expectNoAxeViolations(page);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: screenshotPath!, fullPage: true });

  // Release the reviewed work through the real API so the following browser steps exercise a
  // newly staged chapter on an already-public work. The test runner enables this gate only in
  // its disposable API process.
  await addSession(ownerSessionToken!);
  const ownerCsrfResponse = await page.request.get(`${baseUrl}/api/v1/auth/csrf`);
  expect(ownerCsrfResponse.status()).toBe(200);
  const ownerCsrf = (await ownerCsrfResponse.json()) as { token: string };
  const liveSystemStatus = await page.request.get(`${baseUrl}/api/v1/system/status`);
  expect(liveSystemStatus.status()).toBe(200);
  expect((await liveSystemStatus.json()).features.publicationReleaseEnabled).toBe(true);
  const wholeWorkReadiness = await page.request.get(
    `${baseUrl}/api/v1/authoring/manuscripts/${workId}/publication/readiness`,
  );
  expect(wholeWorkReadiness.status()).toBe(200);
  const wholeWorkEtag = wholeWorkReadiness.headers()['etag'];
  expect(wholeWorkEtag).toMatch(/^"publication-snapshot-[a-f0-9]{64}"$/);
  const wholeWorkRelease = await page.request.post(
    `${baseUrl}/api/v1/authoring/manuscripts/${workId}/publication-actions`,
    {
      data: { actionCode: 'PUBLISH_NOW' },
      headers: {
        'X-CSRF-Token': ownerCsrf.token,
        'If-Match': wholeWorkEtag,
        'Idempotency-Key': `live-whole-release-${randomUUID()}`,
      },
    },
  );
  expect(wholeWorkRelease.status(), await wholeWorkRelease.text()).toBe(201);

  await page.goto(`${baseUrl}/app/authoring/${workId}/chapters`);
  await expect(page.getByRole('heading', { name: 'Live E2E Novel Two' })).toBeVisible();
  await expect(page.getByText(/TÁC PHẨM ĐANG PHÁT HÀNH/)).toBeVisible();
  await page.getByRole('button', { name: 'Thêm chương' }).click();
  await page.locator('#authoring-chapter-title').fill('Live E2E Staged Chapter');
  const chapterCreateResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/authoring/manuscripts/${workId}/chapters`,
  );
  await page.getByRole('button', { name: 'Thêm chương trống' }).click();
  const createdChapter = await chapterCreateResponse;
  expect(createdChapter.status()).toBe(201);
  const createdChapterBody = (await createdChapter.json()) as { id: string };
  expect(createdChapterBody.id).toMatch(/^[0-9a-f-]{36}$/i);
  await page.getByRole('link', { name: 'Viết chương' }).click();
  await expect(page.getByRole('heading', { name: 'Live E2E Staged Chapter' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Duyệt chương mới' })).toHaveCount(0);
  await page
    .locator('#chapter-draft-content')
    .fill('A privately staged chapter, released only after review.');
  await page.getByRole('button', { name: 'Lưu ngay' }).click();
  await expect(page.locator('.chapter-editor-save-state')).toContainText('Đã lưu · phiên bản 1');
  await expect(page.getByRole('heading', { name: 'Duyệt chương mới' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Chuẩn bị yêu cầu duyệt' })).toBeVisible();
  await page.getByRole('button', { name: 'Chuẩn bị yêu cầu duyệt' }).click();
  await expect(
    page.getByRole('heading', { name: 'Rà soát bản đã lưu và khai báo phạm vi' }),
  ).toBeFocused();
  const targetChapterRow = page
    .locator('.publication-request-chapters li')
    .filter({ hasText: 'Live E2E Staged Chapter' });
  await expect(targetChapterRow).toContainText('Bản lưu mục tiêu · chưa công khai');
  await targetChapterRow.getByRole('button', { name: 'Xem bản đã lưu' }).click();
  await expect(page.getByLabel('Nội dung văn bản của bản chương đã lưu')).toContainText(
    'A privately staged chapter, released only after review.',
  );
  await page.getByRole('button', { name: 'Đóng xem trước' }).click();
  await page.locator('#publication-request-metadata-basis').selectOption('ORIGINAL_WORK');
  await page.locator('#publication-request-chapter-basis').selectOption('ORIGINAL_WORK');
  await page.getByLabel(/Tôi cũng yêu cầu xem xét quyền thương mại/).check();
  await page.locator('#publication-request-commercial-basis').selectOption('ORIGINAL_WORK');
  await page.getByLabel(/Tôi cũng yêu cầu xem xét quyền chuyển ngữ/).check();
  await page
    .locator('#publication-request-translation-adaptation-basis')
    .selectOption('ORIGINAL_WORK');
  await page.getByLabel(/Tôi đã rà soát các phạm vi/).check();
  const chapterSubmissionResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname ===
        `/api/v1/authoring/manuscripts/${workId}/publication/requests/chapters/${createdChapterBody.id}`,
  );
  await expect(page.getByRole('button', { name: 'Gửi yêu cầu duyệt' })).toBeEnabled();
  await page.getByRole('button', { name: 'Gửi yêu cầu duyệt' }).click();
  const chapterSubmission = await chapterSubmissionResponse;
  expect(chapterSubmission.status()).toBe(201);
  const chapterRequest = (await chapterSubmission.json()) as { requestId: string };
  expect(chapterRequest.requestId).toMatch(/^[0-9a-f-]{36}$/i);
  await expect(page.locator('.chapter-release-status')).toContainText(
    'Đã gửi yêu cầu duyệt chương',
  );
  await expect(page.locator('.chapter-editor-privacy')).toContainText('chưa được xuất bản');

  await addSession(reviewerSessionToken!);
  await page.goto(`${baseUrl}/app/review`);
  await expect(
    page.getByRole('link', { name: 'Xem yêu cầu của Live E2E Novel Two' }),
  ).toBeVisible();
  await page.getByRole('link', { name: 'Xem yêu cầu của Live E2E Novel Two' }).click();
  await expect(page).toHaveURL(new RegExp(`/app/review/${chapterRequest.requestId}$`));
  const reviewedChapterSnapshot = page.locator('.chapter-preview').first();
  await reviewedChapterSnapshot.locator('summary').click();
  await expect(reviewedChapterSnapshot.getByText('CHƯƠNG ĐANG XIN DUYỆT')).toBeVisible();
  await expect(
    reviewedChapterSnapshot.getByText('Chương 2 · Live E2E Staged Chapter'),
  ).toBeVisible();
  await expect(
    page.getByText('A privately staged chapter, released only after review.'),
  ).toHaveCount(0);
  await expectNoAxeViolations(page);
  await page.getByRole('button', { name: 'Chấp thuận quyền' }).click();
  const chapterDecisionDialog = page.getByRole('dialog', {
    name: 'Chấp thuận quyền cho snapshot này?',
  });
  await expect(chapterDecisionDialog).toBeVisible();
  await chapterDecisionDialog.getByLabel('Đã xem xét khai báo quyền').check();
  const chapterDecisionResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname ===
        `/api/v1/review/publications/${chapterRequest.requestId}/decisions`,
  );
  await chapterDecisionDialog.getByRole('button', { name: 'Xác nhận quyết định' }).click();
  expect((await chapterDecisionResponse).status()).toBe(201);
  await expect(page.locator('.publication-review-result')).toContainText(
    'Đã ghi nhận: Được duyệt · Chưa công khai.',
  );
  await expectNoAxeViolations(page);

  await addSession(ownerSessionToken!);
  await page.goto(`${baseUrl}/app/authoring/${workId}/chapters/${createdChapterBody.id}`);
  await expect(page.getByRole('heading', { name: 'Reviewer đã duyệt chương' })).toBeVisible();
  await page.getByLabel('Tôi xác nhận công khai chương này ngay bây giờ.').check();
  const chapterReleaseResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname ===
        `/api/v1/authoring/manuscripts/${workId}/chapters/${createdChapterBody.id}/publication-release`,
  );
  await page.getByRole('button', { name: 'Xác nhận xuất bản chương' }).click();
  expect((await chapterReleaseResponse).status()).toBe(201);
  await expect(page.locator('.chapter-release-status')).toContainText('Đã công khai chương');
  await expectNoAxeViolations(page);
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: chapterScreenshotPath!, fullPage: true });
});

async function expectNoHorizontalOverflow(page: import('@playwright/test').Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  expect(dimensions.body).toBeLessThanOrEqual(dimensions.viewport);
}
