import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const reviewerId = '22222222-2222-4222-8222-222222222222';
const requestId = '33333333-3333-4333-8333-333333333333';
const chapterId = '44444444-4444-4444-8444-444444444444';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const submittedAt = '2026-11-16T10:00:00Z';
const snapshotHash = 'b'.repeat(64);
const firstEtag = `"publication-review-${'a'.repeat(64)}"`;
const currentEtag = `"publication-review-${'c'.repeat(64)}"`;

type FixtureOptions = {
  unresolvedClaim?: boolean;
  failFirstDecision?: boolean;
  staleFirstDecision?: boolean;
};

function reviewDetail(unresolvedClaim = false) {
  const basis = unresolvedClaim ? 'NEEDS_REVIEW' : 'ORIGINAL_WORK';
  return {
    requestId,
    requestVersion: 1,
    submittedAt,
    targetChapterId: null,
    policyVersion: 'rights-policy-1',
    state: 'PENDING_REVIEW',
    snapshotSha256: snapshotHash,
    scopes: [
      {
        scopeCode: 'PUBLIC_METADATA',
        snapshotSha256: snapshotHash,
        previewPolicyVersion: null,
        previewPercent: null,
        measurement: null,
        work: { title: 'Núi lặng', author: 'Tác giả mẫu', sourceLanguage: 'vi' },
        chapters: [],
      },
      {
        scopeCode: 'CHAPTER_TEXT',
        snapshotSha256: snapshotHash,
        previewPolicyVersion: null,
        previewPercent: null,
        measurement: null,
        work: { title: 'Núi lặng', author: 'Tác giả mẫu', sourceLanguage: 'vi' },
        chapters: [
          {
            chapterId,
            ordinal: 1,
            title: 'Bước đầu',
            sourceLanguage: 'vi',
            contentHash: snapshotHash,
            segmentCount: 6,
          },
        ],
      },
    ],
    claims: [
      { scopeCode: 'PUBLIC_METADATA', rightsBasisCode: basis },
      { scopeCode: 'CHAPTER_TEXT', rightsBasisCode: basis },
    ],
    events: [
      {
        sequence: 1,
        actorRoleCode: 'author',
        actionCode: 'SUBMITTED',
        fromState: null,
        toState: 'PENDING_REVIEW',
        reasonCode: null,
        createdAt: submittedAt,
      },
    ],
  };
}

async function json(route: Route, body: unknown, status = 200, headers = {}): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers,
    body: JSON.stringify(body),
  });
}

async function installReviewerSession(
  page: Page,
  options: FixtureOptions = {},
): Promise<{
  decisions: Array<{ body: unknown; csrf?: string; ifMatch?: string; key?: string }>;
}> {
  let currentDetail: ReturnType<typeof reviewDetail> & { state: string } = reviewDetail(
    options.unresolvedClaim,
  );
  let decisionCalls = 0;
  let detailReads = 0;
  const decisions: Array<{ body: unknown; csrf?: string; ifMatch?: string; key?: string }> = [];

  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '8.9.0',
      environment: 'test',
      integrations: {
        googleOidcEnabled: false,
        momoTestEnabled: false,
        momoEnvironment: 'disabled',
      },
      features: {
        importProcessingEnabled: false,
        cultivationEnabled: false,
        commerceSandboxEnabled: false,
        translationFakePreviewEnabled: false,
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
  await page.route('**/api/v1/review/publications**', async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    if (request.method() === 'POST') {
      decisionCalls += 1;
      decisions.push({
        body: request.postDataJSON(),
        csrf: request.headers()['x-csrf-token'],
        ifMatch: request.headers()['if-match'],
        key: request.headers()['idempotency-key'],
      });
      if (options.failFirstDecision && decisionCalls === 1) {
        return json(route, { code: 'PUBLICATION_REVIEW_DECISION_UNAVAILABLE' }, 503);
      }
      if (options.staleFirstDecision && decisionCalls === 1) {
        return json(route, { code: 'PUBLICATION_REVIEW_ETAG_STALE' }, 412, { ETag: currentEtag });
      }
      const body = request.postDataJSON() as { actionCode: string; reasonCode: string };
      const toState =
        body.actionCode === 'APPROVED'
          ? 'APPROVED_UNPUBLISHED'
          : body.actionCode === 'REJECTED'
            ? 'REJECTED'
            : 'NEEDS_INFORMATION';
      currentDetail = {
        ...currentDetail,
        state: toState,
        events: [
          ...currentDetail.events,
          {
            sequence: 2,
            actorRoleCode: 'publication_reviewer',
            actionCode: body.actionCode,
            fromState: 'PENDING_REVIEW',
            toState,
            reasonCode: body.reasonCode,
            createdAt: '2026-11-16T10:05:00Z',
          },
        ],
      };
      return json(
        route,
        {
          eventSequence: 2,
          actorRoleCode: 'publication_reviewer',
          actionCode: body.actionCode,
          fromState: 'PENDING_REVIEW',
          toState,
          reasonCode: body.reasonCode,
          decidedAt: '2026-11-16T10:05:00Z',
        },
        201,
      );
    }
    if (pathname === '/api/v1/review/publications') {
      const cursor = new URL(request.url()).searchParams.get('cursor');
      return json(route, {
        items:
          cursor === null
            ? [
                {
                  requestId,
                  targetChapterId: null,
                  requestVersion: 1,
                  submittedAt,
                  policyVersion: 'rights-policy-1',
                  snapshotSha256: snapshotHash,
                  title: 'Núi lặng',
                  author: 'Tác giả mẫu',
                  sourceLanguage: 'vi',
                  scopeCodes: ['PUBLIC_METADATA', 'CHAPTER_TEXT'],
                },
              ]
            : [],
        nextCursor: cursor === null ? 'eyJjdXJzb3IifQ.signature' : null,
      });
    }
    detailReads += 1;
    return json(route, currentDetail, 200, {
      ETag: options.staleFirstDecision && detailReads > 1 ? currentEtag : firstEtag,
    });
  });

  return { decisions };
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

for (const viewport of [
  { name: '180 CSS pixel effective width', width: 180, height: 400 },
  { name: '360 pixel mobile', width: 360, height: 800 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 900 },
]) {
  test(`publication review queue and detail reflow at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installReviewerSession(page);
    await page.goto('/app/review');
    await expect(
      page.getByRole('heading', { name: 'Hàng đợi duyệt quyền xuất bản' }),
    ).toBeVisible();
    if (viewport.width > 1024) {
      await expect(page.getByRole('link', { name: 'Hàng đợi duyệt' })).toBeVisible();
    } else {
      const mobileNavigation = page.getByRole('navigation', { name: 'Điều hướng di động' });
      await expect(mobileNavigation).toContainText('Duyệt');
      if (viewport.width <= 200) {
        const reviewerLink = mobileNavigation.getByRole('link', { name: 'Duyệt' });
        await reviewerLink.scrollIntoViewIfNeeded();
        const targetSize = await reviewerLink.evaluate((link) => {
          const bounds = link.getBoundingClientRect();
          return { width: bounds.width, height: bounds.height };
        });
        expect(targetSize.width).toBeGreaterThanOrEqual(44);
        expect(targetSize.height).toBeGreaterThanOrEqual(44);
      }
    }
    await expect(page.getByRole('heading', { name: 'Núi lặng' })).toBeVisible();
    await expect(page.getByText('Văn bản chương', { exact: true })).toBeVisible();
    await expectNoHorizontalOverflow(page);
    await expectNoAxeViolations(page);

    await page.getByRole('link', { name: 'Xem yêu cầu của Núi lặng' }).click();
    await expect(page.getByRole('heading', { name: 'Núi lặng' })).toBeVisible();
    await expect(page.getByText('Chỉ có metadata để xem xét.')).toBeVisible();
    await expect(page.getByText('Tác phẩm do tác giả tự sáng tác').first()).toBeVisible();
    await page.locator('.snapshot-digest summary').first().click();
    await expect(page.getByText(snapshotHash).first()).toBeVisible();
    await page.getByText('1 chương trong snapshot').click();
    await expect(page.getByText('Chương 1 · Bước đầu')).toBeVisible();
    await expect(page.getByText(/văn bản chương đầy đủ/i)).toHaveCount(0);
    await expect(
      page.locator('.publication-review-action-buttons > .review-button-primary'),
    ).toHaveCSS('background-color', 'rgb(64, 86, 181)');
    await expectNoHorizontalOverflow(page);
    await expectNoAxeViolations(page);

    if (viewport.width <= 360) {
      await page.screenshot({
        path: testInfo.outputPath(`publication-review-${viewport.width}px.png`),
        fullPage: true,
      });
    }
  });
}

test('decision requires a reason, sends the current validator and remains unpublished after approval', async ({
  page,
}) => {
  const { decisions } = await installReviewerSession(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto(`/app/review/${requestId}`);
  await expect(page.getByRole('heading', { name: 'Núi lặng' })).toBeVisible();

  const approveAction = page.getByRole('button', { name: 'Chấp thuận quyền' });
  await approveAction.focus();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: 'Chấp thuận quyền cho snapshot này?' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Xác nhận quyết định' })).toBeDisabled();
  const reviewedReason = dialog.getByLabel('Đã xem xét khai báo quyền');
  await reviewedReason.focus();
  await page.keyboard.press('Space');
  await expect(reviewedReason).toBeChecked();
  await expectNoAxeViolations(page);
  const confirmDecision = dialog.getByRole('button', { name: 'Xác nhận quyết định' });
  await confirmDecision.focus();
  await page.keyboard.press('Enter');

  await expect(page.locator('.publication-review-result')).toContainText(
    'Đã ghi nhận: Được duyệt · Chưa công khai.',
  );
  await expect(page.getByText('Được duyệt · Chưa công khai').first()).toBeVisible();
  expect(decisions).toHaveLength(1);
  expect(decisions[0]).toMatchObject({
    body: { actionCode: 'APPROVED', reasonCode: 'RIGHTS_REVIEWED' },
    csrf: csrfToken,
    ifMatch: firstEtag,
  });
  expect(decisions[0].key).toMatch(/^review-decision-[0-9a-f-]{36}$/i);
  await expect(page.getByRole('button', { name: 'Chấp thuận quyền' })).toHaveCount(0);
  await expectNoAxeViolations(page);
});

test('an unresolved rights declaration blocks approval but keeps other reason-coded decisions available', async ({
  page,
}) => {
  await installReviewerSession(page, { unresolvedClaim: true });
  await page.goto(`/app/review/${requestId}`);
  await expect(page.getByRole('button', { name: 'Chấp thuận quyền' })).toBeDisabled();
  await expect(
    page.getByText('Có căn cứ quyền cần xác minh thêm. Không thể chấp thuận yêu cầu hiện tại.'),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Yêu cầu bổ sung' })).toBeEnabled();
  await expectNoAxeViolations(page);
});

test('an uncertain server reply reuses the same action, reason, ETag and idempotency key', async ({
  page,
}) => {
  const { decisions } = await installReviewerSession(page, { failFirstDecision: true });
  await page.goto(`/app/review/${requestId}`);
  await page.getByRole('button', { name: 'Yêu cầu bổ sung' }).click();
  const dialog = page.getByRole('dialog', { name: 'Yêu cầu bổ sung thông tin?' });
  await dialog.getByLabel('Cần thêm thông tin').check();
  await dialog.getByRole('button', { name: 'Xác nhận quyết định' }).click();
  await expect(
    dialog.getByText(
      'Kết quả lần gửi trước chưa xác định. Lựa chọn và mã yêu cầu được giữ nguyên.',
    ),
  ).toBeVisible();
  await dialog.getByRole('button', { name: 'Gửi lại đúng quyết định' }).click();
  await expect(page.locator('.publication-review-result')).toContainText('Đã yêu cầu bổ sung');

  expect(decisions).toHaveLength(2);
  expect(decisions[0]).toEqual(decisions[1]);
  expect(decisions[0]).toMatchObject({
    body: { actionCode: 'NEEDS_INFORMATION', reasonCode: 'MORE_INFORMATION' },
    ifMatch: firstEtag,
    csrf: csrfToken,
  });
  await expect(page.getByRole('button', { name: 'Yêu cầu bổ sung' })).toHaveCount(0);
});

test('a stale ETag requires an explicit detail refresh before a new decision', async ({ page }) => {
  const { decisions } = await installReviewerSession(page, { staleFirstDecision: true });
  await page.goto(`/app/review/${requestId}`);
  await page.getByRole('button', { name: 'Từ chối' }).click();
  const dialog = page.getByRole('dialog', { name: 'Từ chối yêu cầu này?' });
  await dialog.getByLabel('Chưa đủ căn cứ xác minh quyền').check();
  await dialog.getByRole('button', { name: 'Xác nhận quyết định' }).click();
  await expect(page.getByRole('alert')).toContainText('Bản gửi duyệt đã thay đổi');
  await expect(page.getByRole('button', { name: 'Từ chối' })).toBeDisabled();
  await page.getByRole('button', { name: 'Tải lại chi tiết' }).click();
  await expect(page.getByRole('button', { name: 'Từ chối' })).toBeEnabled();
  expect(decisions).toHaveLength(1);
  expect(decisions[0].ifMatch).toBe(firstEtag);
});

test('a reviewer capability link is hidden unless the server returns it', async ({ page }) => {
  await installReviewerSession(page);
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
      user: {
        id: reviewerId,
        displayName: 'Tài khoản thường',
        contactEmail: null,
        avatarUrl: null,
        status: 'ACTIVE',
      },
      identityProviders: [],
      policy: {
        termsAcceptedVersion: 'terms-1',
        privacyNoticeAcknowledgedVersion: 'privacy-1',
        requiredAcceptanceComplete: true,
      },
      roles: [{ scope: 'personal', permissions: [] }],
      features: {
        privateLibraryEnabled: false,
        importEnabled: false,
        readerEnabled: false,
        commerceSandboxEnabled: false,
        cultivationEnabled: false,
      },
    }),
  );
  await page.goto('/app/home');
  await expect(page.getByRole('link', { name: 'Hàng đợi duyệt' })).toHaveCount(0);
});

test('a current server-side reviewer denial clears the queue and shows no request details', async ({
  page,
}) => {
  await installReviewerSession(page);
  await page.route('**/api/v1/review/publications*', (route) =>
    json(route, { code: 'PUBLICATION_REVIEW_FORBIDDEN' }, 403),
  );
  await page.goto('/app/review');
  await expect(page.getByRole('heading', { name: 'Không thể truy cập hàng đợi' })).toBeVisible();
  await expect(
    page.getByText('Thông tin yêu cầu không được tải vì tài khoản hiện không có quyền truy cập.'),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Núi lặng' })).toHaveCount(0);
  await expectNoAxeViolations(page);
});

test('a concealed missing reviewer request does not expose identifying metadata', async ({
  page,
}) => {
  await installReviewerSession(page);
  await page.route(`**/api/v1/review/publications/${requestId}`, (route) =>
    json(route, { code: 'NOT_FOUND' }, 404),
  );
  await page.goto(`/app/review/${requestId}`);
  await expect(page.getByRole('heading', { name: 'Yêu cầu không khả dụng' })).toBeVisible();
  await expect(
    page.getByText(
      'Yêu cầu không tồn tại, không còn truy cập được hoặc không thuộc phạm vi xem xét của bạn.',
    ),
  ).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Núi lặng' })).toHaveCount(0);
  await expectNoAxeViolations(page);
});
