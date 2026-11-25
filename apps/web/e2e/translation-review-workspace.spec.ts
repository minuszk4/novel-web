import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const reviewerId = '22222222-2222-4222-8222-222222222222';
const submissionId = '33333333-3333-4333-8333-333333333333';
const staleSubmissionId = '44444444-4444-4444-8444-444444444444';
const decisionId = '55555555-5555-4555-8555-555555555555';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const submittedAt = '2026-11-16T10:00:00Z';
const snapshotHash = 'b'.repeat(64);
const snapshotEtag = `"translation-review-${snapshotHash}"`;

type FixtureOptions = { uncertainFirstDecision?: boolean };

async function json(
  route: Route,
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers,
    body: JSON.stringify(body),
  });
}

function queueItem(id: string, sourceCurrent = true) {
  return {
    submissionId: id,
    workTitle: id === staleSubmissionId ? 'Mùa sương' : 'Thiên ngoại chi thư',
    chapterTitle: id === staleSubmissionId ? 'Chương cũ' : 'Chương mở đầu',
    sourceLanguage: 'vi',
    targetLanguage: 'en',
    segmentCount: 2,
    submitterDisplayName: 'Tác giả mẫu',
    submittedAt,
    sourceCurrent,
  };
}

function reviewDetail() {
  return {
    submissionId,
    status: 'pending_review',
    workTitle: 'Thiên ngoại chi thư',
    chapterTitle: 'Chương mở đầu',
    submitterDisplayName: 'Tác giả mẫu',
    sourceLanguage: 'vi',
    targetLanguage: 'en',
    snapshotDigest: snapshotHash,
    segmentCount: 2,
    submittedAt,
    canDecide: true,
    segments: [
      {
        ordinal: 1,
        editRevision: 3,
        translatedSha256: 'a'.repeat(64),
        sourceText: 'Một buổi sớm, sương phủ kín ngọn núi.',
        submittedText: 'At dawn, mist covered the mountain.',
      },
      {
        ordinal: 2,
        editRevision: 2,
        translatedSha256: 'c'.repeat(64),
        sourceText: 'Người lữ khách lặng lẽ bước đi.',
        submittedText: 'The traveler walked on in silence.',
      },
    ],
  };
}

async function installReviewRoutes(
  page: Page,
  options: FixtureOptions = {},
): Promise<Array<{ body: unknown; csrf?: string; etag?: string; key?: string }>> {
  const decisions: Array<{ body: unknown; csrf?: string; etag?: string; key?: string }> = [];
  let decisionCalls = 0;

  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '9.6.0',
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
        translationFakePreviewEnabled: true,
        translationManualSubmissionEnabled: true,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
      user: {
        id: reviewerId,
        displayName: 'Người duyệt mẫu',
        contactEmail: 'reviewer@example.test',
        avatarUrl: null,
        status: 'ACTIVE',
      },
      identityProviders: ['google'],
      policy: {
        termsAcceptedVersion: 'terms-1',
        privacyNoticeAcknowledgedVersion: 'privacy-1',
        requiredAcceptanceComplete: true,
      },
      roles: [
        { scope: 'personal', permissions: [] },
        { scope: 'publication', permissions: ['publication.review'] },
      ],
      features: {
        privateLibraryEnabled: false,
        importEnabled: false,
        readerEnabled: false,
        commerceSandboxEnabled: false,
        cultivationEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/current', (route) =>
    json(route, {
      terms: {
        version: 'terms-1',
        effectiveAt: submittedAt,
        documentUrl: '/api/v1/auth/policies/terms/terms-1',
      },
      privacyNotice: {
        version: 'privacy-1',
        effectiveAt: submittedAt,
        documentUrl: '/api/v1/auth/policies/privacy-notice/privacy-1',
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
  await page.route('**/api/v1/review/translations**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (request.method() === 'POST') {
      decisionCalls += 1;
      decisions.push({
        body: request.postDataJSON(),
        csrf: request.headers()['x-csrf-token'],
        etag: request.headers()['if-match'],
        key: request.headers()['idempotency-key'],
      });
      if (options.uncertainFirstDecision && decisionCalls === 1) {
        await json(route, { code: 'TRANSLATION_REVIEW_UNAVAILABLE' }, 503, {
          'Cache-Control': 'no-store',
        });
        return;
      }
      const body = request.postDataJSON() as {
        actionCode: 'ACCEPT' | 'REJECT';
        reasonCode: string | null;
      };
      await json(
        route,
        {
          decisionId,
          submissionId,
          actionCode: body.actionCode,
          reasonCode: body.reasonCode,
          snapshotDigest: snapshotHash,
          decidedAt: '2026-11-16T10:05:00Z',
          replayed: false,
        },
        201,
        { 'Cache-Control': 'no-store', Pragma: 'no-cache', ETag: snapshotEtag },
      );
      return;
    }
    if (pathname === '/api/v1/review/translations') {
      await json(
        route,
        {
          items: [queueItem(submissionId), queueItem(staleSubmissionId, false)],
          nextCursor: null,
          hasMore: false,
        },
        200,
        { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
      );
      return;
    }
    await json(route, reviewDetail(), 200, {
      'Cache-Control': 'no-store',
      Pragma: 'no-cache',
      ETag: snapshotEtag,
    });
  });
  return decisions;
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  expect(dimensions.body).toBeLessThanOrEqual(dimensions.viewport);
}

test('review queue and frozen detail remain readable at mobile, narrow zoom width, and desktop', async ({
  page,
}, testInfo) => {
  await installReviewRoutes(page);
  for (const viewport of [
    { name: 'mobile-360x800', width: 360, height: 800 },
    { name: 'landscape-640x360', width: 640, height: 360 },
    { name: 'tablet-768x1024', width: 768, height: 1024 },
    { name: 'effective-200-percent-180x400', width: 180, height: 400 },
    { name: 'desktop-1280x900', width: 1280, height: 900 },
  ]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/app/review/translations');
    const queueHeading = page.getByRole('heading', { name: 'Hàng đợi duyệt bản dịch' });
    await expect(queueHeading).toBeVisible();
    await expect(queueHeading).toBeFocused();
    await expect(
      page.getByRole('link', { name: 'Thiên ngoại chi thư · Chương mở đầu' }),
    ).toBeVisible();
    await expect(page.getByText('Mùa sương · Chương cũ')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Mùa sương · Chương cũ' })).toHaveCount(0);
    await expect(page.getByText(/Đoạn 1|At dawn/)).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    await expectNoAxeViolations(page);
    if (viewport.width <= 360 || viewport.width >= 1200) {
      await page.screenshot({
        path: testInfo.outputPath(`translation-review-queue-${viewport.name}.png`),
        fullPage: true,
      });
    }

    const detailLink = page.getByRole('link', { name: 'Đối chiếu' });
    const detailHref = await detailLink.getAttribute('href');
    expect(detailHref).toBe(`/app/review/translations/${submissionId}`);
    await detailLink.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(detailHref!);
    await expect(page).toHaveURL(`/app/review/translations/${submissionId}`);
    await expect(
      page.getByRole('heading', { name: 'Thiên ngoại chi thư', exact: true }),
    ).toBeVisible();
    await expect(page.getByText('At dawn, mist covered the mountain.')).toBeVisible();
    await expect(page.getByText('Một buổi sớm, sương phủ kín ngọn núi.')).toBeVisible();
    await expect(page.getByText('Chấp thuận bản này')).toBeVisible();
    await expect(page.getByText(/Chấp thuận không xuất bản/i)).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await expectNoAxeViolations(page);
    if (viewport.width === 360) {
      await page.getByRole('link', { name: '← Hàng đợi duyệt bản dịch' }).click();
      await expect(page).toHaveURL('/app/review/translations');
      await page.getByRole('link', { name: 'Đối chiếu' }).click();
      await expect(page).toHaveURL(detailHref!);
      await page.screenshot({
        path: testInfo.outputPath(`translation-review-detail-${viewport.name}.png`),
        fullPage: true,
      });
    }
  }
});

test('accept records only a frozen-snapshot editorial decision and leaves publication off', async ({
  page,
}) => {
  const decisions = await installReviewRoutes(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto(`/app/review/translations/${submissionId}`);
  await expect(page.getByRole('heading', { name: 'Thiên ngoại chi thư' })).toBeVisible();
  await page.getByRole('button', { name: 'Chấp thuận bản này' }).click();
  await expect(page.getByRole('heading', { name: 'Xác nhận chấp thuận' })).toBeVisible();
  await expect(page.getByLabel('Lý do')).toBeFocused();
  await expectNoAxeViolations(page);
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Xác nhận và ghi nhận' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { name: 'Đã ghi nhận duyệt' })).toBeFocused();
  await expect(
    page.getByText('Bản chỉnh sửa được duyệt về mặt biên tập nhưng vẫn chưa công khai.'),
  ).toBeVisible();
  expect(decisions).toHaveLength(1);
  expect(decisions[0]).toMatchObject({
    body: { actionCode: 'ACCEPT', reasonCode: 'ACCURATE' },
    csrf: csrfToken,
    etag: snapshotEtag,
  });
  expect(decisions[0].key).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
  );
  await expect(page.getByRole('button', { name: 'Chấp thuận bản này' })).toHaveCount(0);
  await expectNoAxeViolations(page);
});

test('reject requires a reason and uncertain retry reuses the exact decision request', async ({
  page,
}) => {
  const decisions = await installReviewRoutes(page, { uncertainFirstDecision: true });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto(`/app/review/translations/${submissionId}`);
  await page.getByRole('button', { name: 'Từ chối bản này' }).click();
  await page.getByLabel('Lý do').selectOption('MEANING_ERROR');
  await page.getByRole('button', { name: 'Xác nhận và ghi nhận' }).click();
  await expect(page.getByRole('button', { name: 'Gửi lại đúng quyết định' })).toBeVisible();
  await expect(page.getByLabel('Lý do')).toBeDisabled();
  await page.getByRole('button', { name: 'Gửi lại đúng quyết định' }).click();
  await expect(page.getByRole('heading', { name: 'Đã ghi nhận từ chối' })).toBeFocused();
  expect(decisions).toHaveLength(2);
  expect(decisions[0]).toEqual(decisions[1]);
  expect(decisions[0]).toMatchObject({
    body: { actionCode: 'REJECT', reasonCode: 'MEANING_ERROR' },
    csrf: csrfToken,
    etag: snapshotEtag,
  });
  await expect(page.getByText(/tạo lần gửi mới/)).toBeVisible();
  await expectNoAxeViolations(page);
});
