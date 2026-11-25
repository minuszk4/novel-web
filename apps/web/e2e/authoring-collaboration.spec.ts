import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const workId = '11111111-1111-4111-8111-111111111111';
const invitationId = '55555555-5555-4555-8555-555555555555';
const ownerId = '11111111-aaaa-4aaa-8aaa-111111111111';
const invitationToken = 'A'.repeat(43);
const csrfToken = 'B'.repeat(43);
const expiry = '2026-12-02T10:00:00Z';

const viewports = [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: '200% reflow proxy 180×400', width: 180, height: 400 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`recipient can inspect and accept a manuscript invitation at ${viewport.name}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const decisions: string[] = [];
    await installSignedInAccount(page);
    await installRecipientApi(page, decisions);

    await page.goto(`/manuscript-invitations/${invitationId}#token=${invitationToken}`);
    await expect(page.getByRole('heading', { name: 'Lời mời xem bản thảo' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Thành phố mưa' })).toBeVisible();
    await expect(page.getByText('Chỉnh sửa thông tin và chương nháp')).toBeVisible();
    await expect(
      page.getByText('Chỉ owner có thể gửi bản thảo đi duyệt hoặc xuất bản.'),
    ).toBeVisible();
    await expect(page).toHaveURL(`/manuscript-invitations/${invitationId}`);
    expect(await page.evaluate(() => window.location.hash)).toBe('');
    expect(
      await page.evaluate(
        (id) => sessionStorage.getItem(`novel.manuscript-invitation-token.v1.${id}`),
        invitationId,
      ),
    ).toBe(invitationToken);
    await expectNoAxeViolations(page);
    await expectNoHorizontalOverflow(page);

    await page.getByRole('button', { name: 'Chấp nhận lời mời' }).click();
    await expect(
      page.getByText(
        'Bạn đã chấp nhận lời mời. Bản thảo sẽ xuất hiện trong mục Được chia sẻ với tôi.',
      ),
    ).toBeVisible();
    await expect(page.getByRole('link', { name: 'Mở các bản thảo được chia sẻ' })).toHaveAttribute(
      'href',
      '/app/authoring/shared',
    );
    expect(decisions).toEqual(['resolve', 'accept']);
    expect(
      await page.evaluate(
        (id) => sessionStorage.getItem(`novel.manuscript-invitation-token.v1.${id}`),
        invitationId,
      ),
    ).toBeNull();
    await expectNoAxeViolations(page);
    await expectNoHorizontalOverflow(page);
  });
}

test('owner can queue a least-privilege invitation without exposing recipient email', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await installSignedInAccount(page);
  const mutations: string[] = [];
  let pending = false;
  let createdInvitationId = '';

  await page.route(`**/api/v1/authoring/manuscripts/${workId}/contributors`, async (route) => {
    if (route.request().method() !== 'GET') return route.continue();
    await json(route, {
      collaborators: [],
      invitations: pending
        ? [
            {
              id: createdInvitationId,
              permissions: ['VIEW'],
              status: 'pending',
              createdAt: '2026-11-25T09:00:00Z',
              expiresAt: expiry,
            },
          ]
        : [],
    });
  });
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route(`**/api/v1/authoring/manuscripts/${workId}/invitations/*`, async (route) => {
    expect(route.request().method()).toBe('PUT');
    expect(route.request().headers()['x-csrf-token']).toBe(csrfToken);
    expect(route.request().postDataJSON()).toEqual({
      email: 'reader@example.test',
      permissions: ['VIEW'],
      confirmedPermissionToEmail: true,
    });
    createdInvitationId = new URL(route.request().url()).pathname.split('/').at(-1)!;
    mutations.push('invite');
    pending = true;
    await json(
      route,
      {
        id: createdInvitationId,
        permissions: ['VIEW'],
        status: 'pending',
        expiresAt: expiry,
        message: 'queued',
      },
      202,
    );
  });

  await page.goto(`/app/authoring/${workId}/collaborators`);
  await expect(page.getByRole('heading', { name: 'Cộng tác bản thảo' })).toBeVisible();
  await page.getByLabel('Email người nhận').fill('Reader@Example.test');
  await page
    .getByLabel('Tôi có quyền liên hệ địa chỉ này và yêu cầu Novel gửi lời mời cộng tác.')
    .check();
  await page.getByRole('button', { name: 'Gửi lời mời' }).click();
  await expect(page.locator('.collaboration-status')).toContainText('Đã xếp hàng lời mời');
  await expect(page.getByRole('heading', { name: 'Lời mời đang chờ' })).toBeVisible();
  await expect(page.getByText('Chỉ xem', { exact: true })).toBeVisible();
  await expect(page.locator('#main-content')).not.toContainText('reader@example.test');
  expect(mutations).toEqual(['invite']);
  await expectNoAxeViolations(page);
  await expectNoHorizontalOverflow(page);
});

async function installSignedInAccount(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '10.17.0',
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
        publicCatalogEnabled: false,
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) =>
    json(route, {
      user: {
        id: ownerId,
        displayName: 'Chủ bản thảo',
        contactEmail: 'owner@example.test',
        avatarUrl: null,
        status: 'ACTIVE',
      },
      policy: {
        termsAcceptedVersion: 'terms-1',
        privacyNoticeAcknowledgedVersion: 'privacy-1',
        requiredAcceptanceComplete: true,
      },
    }),
  );
  await page.route('**/api/v1/auth/policies/current', (route) =>
    json(route, {
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
  );
  await page.route('**/api/v1/auth/policies/terms/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Điều khoản' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Quyền riêng tư' }),
  );
}

async function installRecipientApi(page: Page, decisions: string[]): Promise<void> {
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route(`**/api/v1/me/authoring-invitations/${invitationId}/**`, async (route) => {
    const request = route.request();
    const action = new URL(request.url()).pathname.split('/').at(-1)!;
    expect(request.method()).toBe('POST');
    expect(request.headers()['x-csrf-token']).toBe(csrfToken);
    expect(request.postDataJSON()).toEqual({ token: invitationToken });
    expect(request.url()).not.toContain(invitationToken);
    decisions.push(action);
    if (action === 'resolve') {
      return json(route, {
        id: invitationId,
        title: 'Thành phố mưa',
        author: 'Bút danh',
        permissions: ['EDIT', 'VIEW'],
        expiresAt: expiry,
      });
    }
    return json(route, { id: invitationId, status: 'accepted' });
  });
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const report = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(report.document, JSON.stringify(report)).toBeLessThanOrEqual(report.viewport);
}

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
    body: JSON.stringify(body),
  });
}
