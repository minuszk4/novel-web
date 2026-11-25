import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const userId = '11111111-1111-4111-8111-111111111111';
const workId = '22222222-2222-4222-8222-222222222222';
const submissionId = '33333333-3333-4333-8333-333333333333';
const chapterId = '44444444-4444-4444-8444-444444444444';
const versionOneId = '55555555-5555-4555-8555-555555555555';
const versionTwoId = '66666666-6666-4666-8666-666666666666';
const versionThreeId = '77777777-7777-4777-8777-777777777777';
const versionFourId = '88888888-8888-4888-8888-888888888888';
const snapshotDigest = 'a'.repeat(64);
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const versionOneEtag = `"translation-version-${versionOneId}-1"`;
const versionTwoEtag = `"translation-version-${versionTwoId}-2"`;
const versionThreeEtag = `"translation-version-${versionThreeId}-3"`;

async function json(
  route: Route,
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache', ...headers },
    body: JSON.stringify(body),
  });
}

function historyRows(currentVersion: number, versionId: string, versionEtag: string) {
  const at = '2026-11-17T10:00:00Z';
  const common = {
    chapterId,
    chapterTitle: 'Chương mở đầu',
    targetLanguage: 'en',
    currentVersionId: versionId,
    currentVersionNo: currentVersion,
    currentVersionEtag: versionEtag,
  };
  return [
    {
      ...common,
      eventId: '99999999-9999-4999-8999-999999999991',
      versionId: versionOneId,
      versionNo: 1,
      actionCode: 'PUBLISH',
      rollbackOfVersionId: null,
      releasedAt: at,
      current: false,
      rollbackAllowed: true,
    },
    {
      ...common,
      eventId: '99999999-9999-4999-8999-999999999992',
      versionId: versionTwoId,
      versionNo: 2,
      actionCode: 'PUBLISH',
      rollbackOfVersionId: null,
      releasedAt: at,
      current: currentVersion === 2,
      rollbackAllowed: false,
    },
    ...(currentVersion >= 3
      ? [
          {
            ...common,
            eventId: '99999999-9999-4999-8999-999999999993',
            versionId: versionThreeId,
            versionNo: 3,
            actionCode: 'PUBLISH',
            rollbackOfVersionId: null,
            releasedAt: at,
            current: currentVersion === 3,
            rollbackAllowed: false,
          },
        ]
      : []),
    ...(currentVersion >= 4
      ? [
          {
            ...common,
            eventId: '99999999-9999-4999-8999-999999999994',
            versionId: versionFourId,
            versionNo: 4,
            actionCode: 'ROLLBACK',
            rollbackOfVersionId: versionOneId,
            releasedAt: at,
            current: true,
            rollbackAllowed: false,
          },
        ]
      : []),
  ].reverse();
}

async function installOwnerRoutes(page: Page) {
  const releaseRequests: Array<{ csrf?: string; etag?: string; key?: string; body: unknown }> = [];
  const rollbackRequests: Array<{ csrf?: string; etag?: string; key?: string; body: unknown }> = [];
  let released = false;
  let rolledBack = false;

  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '10.2.0',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        vnpayTestEnabled: false,
        vnpayEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: false,
        publicCatalogEnabled: false,
        publicationReleaseEnabled: false,
        translationFakePreviewEnabled: true,
        translationManualSubmissionEnabled: true,
        translationPublicationEnabled: true,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
      user: {
        id: userId,
        displayName: 'Chủ truyện mẫu',
        contactEmail: 'owner@example.test',
        avatarUrl: null,
        status: 'ACTIVE',
      },
      identityProviders: ['google'],
      policy: {
        termsAcceptedVersion: 'terms-1',
        privacyNoticeAcknowledgedVersion: 'privacy-1',
        requiredAcceptanceComplete: true,
      },
      roles: [{ scope: 'personal', permissions: ['authoring.write'] }],
      features: {
        privateLibraryEnabled: true,
        importEnabled: false,
        readerEnabled: true,
        commerceSandboxEnabled: false,
        cultivationEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/current', (route) =>
    json(route, {
      privacyNotice: {
        version: 'privacy-1',
        effectiveAt: '2026-11-17T10:00:00Z',
        documentUrl: '/api/v1/auth/policies/privacy-notice/privacy-1',
      },
      terms: {
        version: 'terms-1',
        effectiveAt: '2026-11-17T10:00:00Z',
        documentUrl: '/api/v1/auth/policies/terms/terms-1',
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/terms/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Điều khoản' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Quyền riêng tư' }),
  );
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route('**/api/v1/authoring/manuscripts/*/translations/accepted**', (route) =>
    json(route, {
      items: released
        ? []
        : [
            {
              submissionId,
              chapterId,
              workTitle: 'Thiên ngoại chi thư',
              chapterTitle: 'Chương mở đầu',
              targetLanguage: 'en',
              snapshotDigest,
              segmentCount: 32,
              submittedAt: '2026-11-17T09:00:00Z',
              rightsCurrent: true,
              currentVersionId: versionTwoId,
              currentVersionNo: 2,
            },
          ],
      nextCursor: null,
      hasMore: false,
    }),
  );
  await page.route('**/api/v1/authoring/manuscripts/*/translations/history**', (route) => {
    const currentVersion = rolledBack ? 4 : released ? 3 : 2;
    const currentVersionId =
      currentVersion === 4 ? versionFourId : currentVersion === 3 ? versionThreeId : versionTwoId;
    const currentEtag =
      currentVersion === 4
        ? `"translation-version-${versionFourId}-4"`
        : currentVersion === 3
          ? versionThreeEtag
          : versionTwoEtag;
    return json(route, {
      items: historyRows(currentVersion, currentVersionId, currentEtag),
      nextCursor: null,
      hasMore: false,
    });
  });
  await page.route(
    '**/api/v1/authoring/translations/review-submissions/*/release',
    async (route) => {
      const request = route.request();
      releaseRequests.push({
        csrf: request.headers()['x-csrf-token'],
        etag: request.headers()['if-match'],
        key: request.headers()['idempotency-key'],
        body: request.postDataJSON(),
      });
      released = true;
      await json(
        route,
        {
          versionId: versionThreeId,
          versionNo: 3,
          snapshotDigest,
          releasedAt: '2026-11-17T10:05:00Z',
          replayed: false,
        },
        201,
        { ETag: `"translation-review-${snapshotDigest}"` },
      );
    },
  );
  await page.route('**/api/v1/authoring/translations/versions/*/rollback', async (route) => {
    const request = route.request();
    rollbackRequests.push({
      csrf: request.headers()['x-csrf-token'],
      etag: request.headers()['if-match'],
      key: request.headers()['idempotency-key'],
      body: request.postDataJSON(),
    });
    rolledBack = true;
    await json(
      route,
      {
        versionId: versionFourId,
        versionNo: 4,
        snapshotDigest,
        releasedAt: '2026-11-17T10:10:00Z',
        replayed: false,
      },
      201,
      { ETag: `"translation-review-${snapshotDigest}"` },
    );
  });
  return { releaseRequests, rollbackRequests };
}

test('owner releases and rolls back an accepted version with safe responsive history', async ({
  page,
}, testInfo) => {
  const { releaseRequests, rollbackRequests } = await installOwnerRoutes(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto(`/app/authoring/${workId}/translations`);
  await expect(page.getByRole('heading', { name: 'Bản dịch đã được duyệt' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Chương mở đầu', exact: true })).toBeVisible();
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Thay bản dịch hiện hành' }).click();
  await expect(page.getByRole('heading', { name: 'Xác nhận phát hành' })).toBeVisible();
  await page.getByRole('button', { name: 'Xác nhận phát hành' }).click();
  await expect(
    page.getByRole('heading', { name: 'Bản dịch phiên bản 3 đã được phát hành' }),
  ).toBeVisible();

  const rollbackButton = page.getByRole('button', { name: 'Khôi phục phiên bản 1' });
  await rollbackButton.click();
  await expect(page.getByRole('heading', { name: 'Xác nhận khôi phục' })).toBeVisible();
  await expect(page.getByText(/lưu phiên bản hiện hành vào lịch sử/)).toBeVisible();
  await page.getByRole('button', { name: 'Xác nhận khôi phục' }).click();
  await expect(
    page.getByRole('heading', { name: 'Đã khôi phục bản dịch phiên bản 4' }),
  ).toBeVisible();

  expect(releaseRequests).toHaveLength(1);
  expect(releaseRequests[0]).toMatchObject({
    csrf: csrfToken,
    etag: `"translation-review-${snapshotDigest}"`,
    body: null,
  });
  expect(releaseRequests[0]?.key).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
  expect(rollbackRequests).toHaveLength(1);
  expect(rollbackRequests[0]).toMatchObject({
    csrf: csrfToken,
    etag: versionThreeEtag,
    body: null,
  });
  expect(rollbackRequests[0]?.key).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);

  for (const viewport of [
    { width: 640, height: 360 },
    { width: 768, height: 1024 },
    { width: 180, height: 400 },
    { width: 1280, height: 900 },
  ]) {
    await page.setViewportSize(viewport);
    const dimensions = await page.evaluate(() => ({
      viewport: document.documentElement.clientWidth,
      document: document.documentElement.scrollWidth,
      body: document.body.scrollWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    expect(dimensions.body).toBeLessThanOrEqual(dimensions.viewport);
  }
  await expectNoAxeViolations(page);
  await page.screenshot({
    path: testInfo.outputPath('translation-publication-history-desktop.png'),
    fullPage: true,
  });
});
