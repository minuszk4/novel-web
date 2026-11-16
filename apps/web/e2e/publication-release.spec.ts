import { expect, expectNoAxeViolations, test } from './support';

const workId = '11111111-1111-4111-8111-111111111111';
const csrfToken = 'A'.repeat(43);
const readinessEtag = `"publication-snapshot-${'a'.repeat(64)}"`;
const scopeEtag = `"publication-scope-${'b'.repeat(64)}"`;
const publishedAt = '2026-11-17T01:00:00Z';

const readiness = {
  workId,
  canSubmit: true,
  publicationRightsPolicyAvailable: true,
  publicationRightsPolicyVersion: 'rights-v1',
  publicationRightsPolicyEffectiveAt: '2026-11-15T00:00:00Z',
  publicationRightsPolicyAcceptedAt: '2026-11-16T12:00:00Z',
  publicationContentVersion: 7,
  readyChapterCount: 3,
  currentRequestState: 'APPROVED_UNPUBLISHED',
  snapshotEtag: readinessEtag,
  scopes: [
    { scopeCode: 'PUBLIC_METADATA', ready: true, snapshotEtag: scopeEtag },
    { scopeCode: 'CHAPTER_TEXT', ready: true, snapshotEtag: scopeEtag },
    { scopeCode: 'COMMERCIAL_SALE', ready: true, snapshotEtag: scopeEtag },
  ],
  blockerCodes: [],
};

for (const viewport of [
  { name: 'effective 200% width 180×400', width: 180, height: 400 },
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: 'tablet 768×1024', width: 768, height: 1024 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const) {
  test(`owner confirms release with current readiness at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.route('**/api/v1/system/status', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          apiVersion: 'v1',
          environment: 'test',
          integrations: {
            googleOidcEnabled: false,
            momoTestEnabled: false,
            momoEnvironment: 'TEST',
          },
          features: {
            importProcessingEnabled: false,
            cultivationEnabled: false,
            commerceSandboxEnabled: false,
            publicCatalogEnabled: false,
            publicationReleaseEnabled: true,
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
          chapterCount: 3,
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
        body: JSON.stringify({ items: [], nextCursor: null }),
      }),
    );

    let readinessReads = 0;
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/publication/readiness`,
      async (route) => {
        readinessReads += 1;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache', ETag: readinessEtag },
          body: JSON.stringify(readiness),
        });
      },
    );

    let action: {
      body: unknown;
      ifMatch: string | undefined;
      idempotencyKey: string | undefined;
      csrfToken: string | undefined;
    } | null = null;
    await page.route(
      `**/api/v1/authoring/manuscripts/${workId}/publication-actions`,
      async (route) => {
        const request = route.request();
        action = {
          body: request.postDataJSON(),
          ifMatch: request.headers()['if-match'],
          idempotencyKey: request.headers()['idempotency-key'],
          csrfToken: request.headers()['x-csrf-token'],
        };
        await new Promise((resolve) => setTimeout(resolve, 100));
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
          body: JSON.stringify({
            eventId: '22222222-2222-4222-8222-222222222222',
            workId,
            requestId: '33333333-3333-4333-8333-333333333333',
            state: 'PUBLISHED',
            publishedAt,
            publicationContentVersion: 7,
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
    expect(await page.locator('.publication-readiness-publish').count()).toBe(0);

    await page.getByRole('button', { name: 'Kiểm tra điều kiện xuất bản' }).click();
    await expect(page.getByText('Đã duyệt · Chưa công khai')).toBeVisible();
    expect(readinessReads).toBe(1);
    await expectNoAxeViolations(page);

    const publishButton = page.getByRole('button', { name: 'Xuất bản ngay', exact: true });
    await publishButton.click();
    const confirmation = page.getByRole('heading', { name: 'Xác nhận xuất bản ngay' });
    await expect(confirmation).toBeFocused();
    await expect(page.getByText(/trở thành công khai ngay/)).toBeVisible();
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Hủy', exact: true }).click();
    await expect(publishButton).toBeFocused();
    expect(action).toBeNull();

    await publishButton.click();
    await page.getByRole('button', { name: 'Xác nhận xuất bản ngay' }).click();
    await expect(
      page.locator('.publication-readiness-confirm .publication-readiness-status'),
    ).toContainText('Đang xác nhận xuất bản');
    await expect(page.locator('.publication-readiness-success')).toContainText(
      'Máy chủ đã xác nhận tác phẩm được công khai',
    );
    expect(action).toEqual({
      body: { actionCode: 'PUBLISH_NOW' },
      ifMatch: readinessEtag,
      idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/),
      csrfToken,
    });
    expect(readinessReads).toBe(1);

    await page.screenshot({
      path: testInfo.outputPath(`publication-release-${viewport.width}.png`),
      fullPage: true,
    });
    const widths = await page.evaluate(() => ({
      client: document.documentElement.clientWidth,
      scroll: document.documentElement.scrollWidth,
    }));
    expect(widths.scroll).toBeLessThanOrEqual(widths.client);
    await expectNoAxeViolations(page);
  });
}

test('feature-off and unapproved manuscripts never render the publish control', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.route('**/api/v1/system/status', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        apiVersion: 'v1',
        environment: 'test',
        integrations: {
          googleOidcEnabled: false,
          momoTestEnabled: false,
          momoEnvironment: 'TEST',
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
  await page.route(`**/api/v1/authoring/manuscripts/${workId}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store', ETag: '"manuscript-metadata-1"' },
      body: JSON.stringify({
        id: workId,
        title: 'Bản thảo riêng tư',
        author: 'Bút danh',
        language: 'vi',
        visibility: 'private',
        publicationState: 'draft',
        chapterCount: 3,
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
      body: JSON.stringify({ items: [], nextCursor: null }),
    }),
  );
  await page.route(`**/api/v1/authoring/manuscripts/${workId}/publication/readiness`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache', ETag: readinessEtag },
      body: JSON.stringify({ ...readiness, currentRequestState: 'PENDING_REVIEW' }),
    }),
  );

  await page.goto(`/app/authoring/${workId}/chapters`);
  await page.getByRole('button', { name: 'Kiểm tra điều kiện xuất bản' }).click();
  await expect(page.getByText('Đang chờ người duyệt')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Xuất bản ngay' })).toHaveCount(0);
  await expectNoAxeViolations(page);
});
