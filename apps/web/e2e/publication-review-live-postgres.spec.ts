import { randomUUID } from 'node:crypto';
import { expect, expectNoAxeViolations, test } from './support';

const baseUrl = 'http://localhost:4300';

test('assigned reviewer approves a submitted snapshot through the real API and PostgreSQL', async ({
  page,
}) => {
  const ownerSessionToken = process.env['LIBRARY_LIVE_TEST_SESSION_TOKEN'];
  const reviewerSessionToken = process.env['PUBLICATION_REVIEW_LIVE_TEST_REVIEWER_SESSION_TOKEN'];
  const workId = process.env['PUBLICATION_REVIEW_LIVE_TEST_WORK_ID'];
  const screenshotPath = process.env['PUBLICATION_REVIEW_LIVE_TEST_SCREENSHOT'];

  test.skip(
    !ownerSessionToken || !reviewerSessionToken || !workId || !screenshotPath,
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

  const readinessPath = `/api/v1/authoring/manuscripts/${workId}/publication/readiness`;
  const readiness = await page.request.get(`${baseUrl}${readinessPath}`);
  expect(readiness.status()).toBe(200);
  const readinessEtag = readiness.headers()['etag'];
  expect(readinessEtag).toBeTruthy();

  const submission = await page.request.post(
    `${baseUrl}/api/v1/authoring/manuscripts/${workId}/publication/requests`,
    {
      data: {
        policyVersion: policy.version,
        scopeClaims: [
          { scopeCode: 'PUBLIC_METADATA', rightsBasisCode: 'ORIGINAL_WORK' },
          { scopeCode: 'CHAPTER_TEXT', rightsBasisCode: 'ORIGINAL_WORK' },
          { scopeCode: 'COMMERCIAL_SALE', rightsBasisCode: 'ORIGINAL_WORK' },
        ],
      },
      headers: {
        'X-CSRF-Token': csrf.token,
        'If-Match': readinessEtag!,
        'Idempotency-Key': randomUUID(),
      },
    },
  );
  expect(submission.status()).toBe(201);
  const request = (await submission.json()) as { requestId: string };
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
