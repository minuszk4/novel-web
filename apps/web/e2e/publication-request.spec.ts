import { expect, expectNoAxeViolations, test } from './support';

const workId = '11111111-1111-4111-8111-111111111111';
const csrfToken = 'A'.repeat(43);
const readinessEtag = `"publication-snapshot-${'a'.repeat(64)}"`;
const scopeEtag = `"publication-scope-${'b'.repeat(64)}"`;
const chapterId = '22222222-2222-4222-8222-222222222222';
const requestId = '33333333-3333-4333-8333-333333333333';

const readyReadiness = {
  workId,
  canSubmit: true,
  publicationRightsPolicyAvailable: true,
  publicationRightsPolicyVersion: 'rights-v1',
  publicationRightsPolicyEffectiveAt: '2026-11-15T00:00:00Z',
  publicationRightsPolicyAcceptedAt: '2026-11-16T12:00:00Z',
  publicationContentVersion: 7,
  readyChapterCount: 1,
  currentRequestState: null,
  snapshotEtag: readinessEtag,
  scopes: [
    { scopeCode: 'PUBLIC_METADATA', ready: true, snapshotEtag: scopeEtag },
    { scopeCode: 'CHAPTER_TEXT', ready: true, snapshotEtag: scopeEtag },
    { scopeCode: 'COMMERCIAL_SALE', ready: true, snapshotEtag: scopeEtag },
    { scopeCode: 'FREE_PREVIEW', ready: true, snapshotEtag: scopeEtag },
    { scopeCode: 'TRANSLATION_ADAPTATION', ready: true, snapshotEtag: scopeEtag },
  ],
  blockerCodes: [],
};

const outline = {
  items: [
    {
      id: chapterId,
      ordinal: 1,
      title: 'Khởi hành',
      language: 'vi',
      status: 'ready',
      contentAvailable: true,
      hasSavedDraftContent: false,
      createdAt: '2026-11-15T12:00:00Z',
    },
  ],
  nextCursor: null,
};

const savedDraft = {
  id: chapterId,
  workId,
  title: 'Chương 1 · Khởi hành',
  status: 'draft',
  revisionNo: 1,
  sourceFormat: 'plain_text',
  normalizedContent: '<script>alert("đây phải là văn bản thuần")</script>\nChân núi phủ sương.',
  segments: [
    {
      ordinal: 1,
      type: 'paragraph',
      sourceText: 'Chân núi phủ sương.',
      stableAnchor: 'chapter-1-paragraph-1',
    },
  ],
  updatedAt: '2026-11-16T12:00:00Z',
};

for (const viewport of [
  { name: 'effective 200% width 180×400', width: 180, height: 400 },
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'tablet 768×1024', width: 768, height: 1024 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const) {
  test(`author submits scoped rights declarations against the saved snapshot at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccount(page);

    await page.route(`**/api/v1/authoring/manuscripts/${workId}`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', ETag: '"manuscript-metadata-1"' },
        body: JSON.stringify({
          id: workId,
          title: 'Hành trình tu luyện',
          author: 'Bút danh',
          language: 'vi',
          visibility: 'private',
          publicationState: 'draft',
          chapterCount: 1,
          createdAt: '2026-11-15T12:00:00Z',
          metadataVersion: 1,
        }),
      }),
    );
    await page.route(`**/api/v1/authoring/manuscripts/${workId}/chapters?**`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
        body: JSON.stringify(outline),
      }),
    );
    let draftReads = 0;
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/chapters/${chapterId}/draft`,
      async (route) => {
        draftReads += 1;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', ETag: '"draft-1"' },
          body: JSON.stringify(savedDraft),
        });
      },
    );

    let readinessReads = 0;
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/publication/readiness`,
      async (route) => {
        readinessReads += 1;
        const isPending = readinessReads > 1;
        const current = isPending
          ? {
              ...readyReadiness,
              canSubmit: false,
              currentRequestState: 'PENDING_REVIEW',
              blockerCodes: ['PUBLICATION_REQUEST_ALREADY_PENDING'],
            }
          : readyReadiness;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache', ETag: readinessEtag },
          body: JSON.stringify(current),
        });
      },
    );

    let submittedRequest: {
      body: unknown;
      ifMatch: string | undefined;
      idempotencyKey: string | undefined;
      csrfToken: string | undefined;
    } | null = null;
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/publication/requests`,
      async (route) => {
        const request = route.request();
        submittedRequest = {
          body: request.postDataJSON(),
          ifMatch: request.headers()['if-match'],
          idempotencyKey: request.headers()['idempotency-key'],
          csrfToken: request.headers()['x-csrf-token'],
        };
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
          body: JSON.stringify({
            requestId,
            requestVersion: 1,
            policyVersion: 'rights-v1',
            snapshotSha256: 'c'.repeat(64),
            scopeCodes: ['PUBLIC_METADATA', 'CHAPTER_TEXT', 'COMMERCIAL_SALE'],
            submittedAt: '2026-11-17T01:00:00Z',
            state: 'PENDING_REVIEW',
          }),
        });
      },
    );
    await page.route('**/api/v1/auth/csrf', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ token: csrfToken }),
      }),
    );

    await page.goto(`/app/authoring/${workId}/chapters`);
    await expect(page.getByRole('heading', { name: 'Hành trình tu luyện' })).toBeVisible();
    expect(readinessReads).toBe(0);
    expect(draftReads).toBe(0);
    await page.getByRole('button', { name: 'Kiểm tra điều kiện xuất bản' }).click();
    await expect(page.getByRole('heading', { name: 'Yêu cầu người duyệt xem xét' })).toBeVisible();
    expect(readinessReads).toBe(1);
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Chuẩn bị yêu cầu duyệt' }).click();
    await expect(
      page.getByRole('heading', { name: 'Rà soát bản đã lưu và khai báo phạm vi' }),
    ).toBeFocused();
    await expect(page.getByText('Chương 1 · Khởi hành')).toBeVisible();
    expect(draftReads).toBe(0);
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Xem bản đã lưu' }).click();
    await expect(page.getByRole('heading', { name: /bản lưu 1/ })).toBeFocused();
    await expect(page.locator('.publication-request-preview pre')).toContainText(
      '<script>alert("đây phải là văn bản thuần")</script>',
    );
    await expect(page.locator('.publication-request-preview script')).toHaveCount(0);
    expect(draftReads).toBe(1);
    await expectNoAxeViolations(page);

    await page.locator('#publication-request-metadata-basis').selectOption('ORIGINAL_WORK');
    await page.locator('#publication-request-chapter-basis').selectOption('LICENSE_OR_PERMISSION');
    await page.getByLabel(/Tôi cũng yêu cầu xem xét quyền thương mại/).check();
    await page.locator('#publication-request-commercial-basis').selectOption('NEEDS_REVIEW');
    const declaration = page.getByLabel(/Tôi đã rà soát các phạm vi/);
    await expect(page.getByRole('button', { name: 'Gửi yêu cầu duyệt' })).toBeDisabled();
    await declaration.check();
    await expect(page.getByRole('button', { name: 'Gửi yêu cầu duyệt' })).toBeEnabled();
    await expectNoAxeViolations(page);
    await page.screenshot({
      path: testInfo.outputPath(`publication-request-form-${viewport.width}.png`),
      fullPage: true,
    });

    await page.getByRole('button', { name: 'Gửi yêu cầu duyệt' }).click();
    await expect(page.locator('.publication-readiness-summary dd').nth(2)).toHaveText(
      'Đang chờ người duyệt',
    );
    expect(readinessReads).toBe(2);
    expect(submittedRequest).toEqual({
      body: {
        policyVersion: 'rights-v1',
        scopeClaims: [
          { scopeCode: 'PUBLIC_METADATA', rightsBasisCode: 'ORIGINAL_WORK' },
          { scopeCode: 'CHAPTER_TEXT', rightsBasisCode: 'LICENSE_OR_PERMISSION' },
          { scopeCode: 'COMMERCIAL_SALE', rightsBasisCode: 'NEEDS_REVIEW' },
        ],
      },
      ifMatch: readinessEtag,
      idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
      csrfToken,
    });
    await expect(page.getByRole('heading', { name: 'Yêu cầu người duyệt xem xét' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Xuất bản ngay' })).toHaveCount(0);
    await expectNoAxeViolations(page);

    const widths = await page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
    }));
    expect(widths.scroll).toBeLessThanOrEqual(widths.client);
    await page.screenshot({
      path: testInfo.outputPath(`publication-request-${viewport.width}.png`),
      fullPage: true,
    });
  });
}

for (const viewport of [
  { name: 'effective 200% width 180×400', width: 180, height: 400 },
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'tablet 768×1024', width: 768, height: 1024 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const) {
  test(`author explicitly withdraws a pending request and retries the exact attempt at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccount(page);
    await page.route(`**/api/v1/authoring/manuscripts/${workId}`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', ETag: '"manuscript-metadata-1"' },
        body: JSON.stringify({
          id: workId,
          title: 'Hành trình tu luyện',
          author: 'Bút danh',
          language: 'vi',
          visibility: 'private',
          publicationState: 'draft',
          chapterCount: 1,
          createdAt: '2026-11-15T12:00:00Z',
          metadataVersion: 1,
        }),
      }),
    );
    await page.route(`**/api/v1/authoring/manuscripts/${workId}/chapters?**`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
        body: JSON.stringify(outline),
      }),
    );

    let readinessReads = 0;
    const withdrawnReadiness = {
      ...readyReadiness,
      currentRequestState: 'WITHDRAWN',
      canSubmit: true,
      blockerCodes: [],
    };
    const pendingReadiness = {
      ...readyReadiness,
      currentRequestState: 'PENDING_REVIEW',
      canSubmit: false,
      blockerCodes: ['PUBLICATION_REQUEST_ALREADY_PENDING'],
    };
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/publication/readiness`,
      async (route) => {
        readinessReads += 1;
        const body = readinessReads === 1 ? pendingReadiness : withdrawnReadiness;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache', ETag: readinessEtag },
          body: JSON.stringify(body),
        });
      },
    );

    const attempts: Array<{
      body: string | null;
      ifMatch: string | undefined;
      idempotencyKey: string | undefined;
      csrfToken: string | undefined;
    }> = [];
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/publication/requests/withdrawal`,
      async (route) => {
        const request = route.request();
        attempts.push({
          body: request.postData(),
          ifMatch: request.headers()['if-match'],
          idempotencyKey: request.headers()['idempotency-key'],
          csrfToken: request.headers()['x-csrf-token'],
        });
        if (attempts.length === 1) {
          await route.fulfill({
            status: 503,
            contentType: 'application/problem+json',
            body: JSON.stringify({ code: 'PUBLICATION_REQUEST_WITHDRAWAL_UNAVAILABLE' }),
          });
          return;
        }
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
          body: JSON.stringify({
            eventId: '44444444-4444-4444-8444-444444444444',
            requestId,
            requestVersion: 1,
            state: 'WITHDRAWN',
            withdrawnAt: '2026-11-17T01:00:00Z',
          }),
        });
      },
    );
    await page.route('**/api/v1/auth/csrf', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ token: csrfToken }),
      }),
    );

    await page.goto(`/app/authoring/${workId}/chapters`);
    await page.getByRole('button', { name: 'Kiểm tra điều kiện xuất bản' }).click();
    await expect(page.getByRole('button', { name: 'Rút yêu cầu duyệt' })).toBeVisible();
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Rút yêu cầu duyệt' }).click();
    const confirmation = page.getByRole('heading', { name: 'Xác nhận rút yêu cầu duyệt' });
    await expect(confirmation).toBeFocused();
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Xác nhận rút yêu cầu' }).click();
    await expect(page.getByRole('button', { name: 'Thử lại cùng thao tác rút' })).toBeVisible();
    await expectNoAxeViolations(page);
    expect(attempts).toHaveLength(1);

    await page.getByRole('button', { name: 'Thử lại cùng thao tác rút' }).click();
    await expect(page.locator('.publication-readiness-summary dd').nth(2)).toHaveText(
      'Yêu cầu đã được rút',
    );
    await expect(page.getByRole('button', { name: 'Rút yêu cầu duyệt' })).toHaveCount(0);
    await expectNoAxeViolations(page);
    expect(readinessReads).toBe(2);
    expect(attempts).toHaveLength(2);
    expect(attempts[0]).toEqual(attempts[1]);
    expect(attempts[0]).toEqual({
      body: null,
      ifMatch: readinessEtag,
      idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
      csrfToken,
    });

    const widths = await page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
    }));
    expect(widths.scroll).toBeLessThanOrEqual(widths.client);
    await page.screenshot({
      path: testInfo.outputPath(`publication-withdrawal-${viewport.width}.png`),
      fullPage: true,
    });
  });
}

for (const viewport of [
  { name: 'effective 200% width 180×400', width: 180, height: 400 },
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'tablet 768×1024', width: 768, height: 1024 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const) {
  test(`author explicitly loads a redacted request timeline at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAccount(page);
    await page.route(`**/api/v1/authoring/manuscripts/${workId}`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', ETag: '"manuscript-metadata-1"' },
        body: JSON.stringify({
          id: workId,
          title: 'Hành trình tu luyện',
          author: 'Bút danh',
          language: 'vi',
          visibility: 'private',
          publicationState: 'draft',
          chapterCount: 1,
          createdAt: '2026-11-15T12:00:00Z',
          metadataVersion: 1,
        }),
      }),
    );
    await page.route(`**/api/v1/authoring/manuscripts/${workId}/chapters?**`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
        body: JSON.stringify(outline),
      }),
    );
    await page.route(`**/api/v1/authoring/manuscripts/${workId}/publication/readiness`, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache', ETag: readinessEtag },
        body: JSON.stringify({
          ...readyReadiness,
          currentRequestState: 'NEEDS_INFORMATION',
        }),
      }),
    );
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/publication/requests/history**`,
      async (route) => {
        expect(route.request().method()).toBe('GET');
        expect(new URL(route.request().url()).searchParams.get('limit')).toBe('20');
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
          body: JSON.stringify({
            items: [
              {
                requestVersion: 1,
                state: 'NEEDS_INFORMATION',
                submittedAt: '2026-11-17T04:00:00Z',
                events: [
                  {
                    sequence: 1,
                    actorType: 'OWNER',
                    actionCode: 'SUBMITTED',
                    fromState: null,
                    toState: 'PENDING_REVIEW',
                    reasonCode: null,
                    createdAt: '2026-11-17T04:00:00Z',
                  },
                  {
                    sequence: 2,
                    actorType: 'REVIEWER',
                    actionCode: 'NEEDS_INFORMATION',
                    fromState: 'PENDING_REVIEW',
                    toState: 'NEEDS_INFORMATION',
                    reasonCode: 'MORE_INFORMATION',
                    createdAt: '2026-11-17T04:10:00Z',
                  },
                ],
              },
            ],
            nextCursor: null,
          }),
        });
      },
    );

    await page.goto(`/app/authoring/${workId}/chapters`);
    await page.getByRole('button', { name: 'Kiểm tra điều kiện xuất bản' }).click();
    await expect(page.getByRole('button', { name: 'Xem lịch sử xử lý' })).toBeVisible();
    await expect(page.getByText('Cần bổ sung thông tin')).toHaveCount(0);
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Xem lịch sử xử lý' }).click();
    await expect(
      page.getByText('Yêu cầu lần 1 · Người duyệt yêu cầu bổ sung thông tin'),
    ).toBeVisible();
    await expect(page.getByText('Cần bổ sung thông tin')).toBeVisible();
    await expect(page.getByText('Nhóm duyệt')).toBeVisible();
    await expect(page.getByText('reviewer-user-id', { exact: false })).toHaveCount(0);
    await expectNoAxeViolations(page);
    const widths = await page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
    }));
    expect(widths.scroll).toBeLessThanOrEqual(widths.client);
    await page.screenshot({
      path: testInfo.outputPath(`publication-history-${viewport.width}.png`),
      fullPage: true,
    });
  });
}

async function installAccount(page: import('@playwright/test').Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        apiVersion: 'v1',
        environment: 'test',
        integrations: {
          googleOidcEnabled: false,
          vnpayTestEnabled: false,
          vnpayEnvironment: 'TEST',
        },
        features: {
          importProcessingEnabled: false,
          cultivationEnabled: false,
          commerceSandboxEnabled: false,
          publicCatalogEnabled: false,
          publicationReleaseEnabled: false,
          translationFakePreviewEnabled: false,
        },
      }),
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        user: {
          id: '11111111-aaaa-4aaa-8aaa-111111111111',
          displayName: 'Tác giả',
          contactEmail: 'author@example.test',
          avatarUrl: null,
          status: 'ACTIVE',
        },
        policy: {
          termsAcceptedVersion: 'terms-1',
          privacyNoticeAcknowledgedVersion: 'privacy-1',
          requiredAcceptanceComplete: true,
        },
      }),
    }),
  );
  await page.route('**/api/v1/auth/policies/current', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        terms: {
          version: 'terms-1',
          effectiveAt: '2026-11-01T00:00:00Z',
          documentUrl: '/api/v1/auth/policies/terms/terms-1',
        },
        privacyNotice: {
          version: 'privacy-1',
          effectiveAt: '2026-11-01T00:00:00Z',
          documentUrl: '/api/v1/auth/policies/privacy-notice/privacy-1',
        },
      }),
    }),
  );
  await page.route('**/api/v1/auth/policies/terms/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Điều khoản' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Quyền riêng tư' }),
  );
}
