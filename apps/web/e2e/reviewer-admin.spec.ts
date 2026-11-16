import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const adminId = '11111111-1111-4111-8111-111111111111';
const reviewerId = '22222222-2222-4222-8222-222222222222';
const candidateId = '33333333-3333-4333-8333-333333333333';
const assignmentId = '44444444-4444-4444-8444-444444444444';
const eventId = '55555555-5555-4555-8555-555555555555';
const csrfToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
const now = '2026-11-16T10:00:00Z';

type Assignment = {
  assignmentId: string;
  userId: string;
  displayName: string;
  maskedContactEmail: string;
  userStatus: 'active';
  assignedByUserId: string;
  assignedAt: string;
  reasonCode: 'REVIEW_CAPACITY' | 'TEMPORARY_COVERAGE' | 'ROLE_CORRECTION';
  revokedAt: string | null;
  revokedByUserId: string | null;
  revocationReasonCode: 'ACCESS_ENDED' | 'ROLE_CHANGE' | 'SECURITY_REVIEW' | null;
  lastEventId: string;
  lastEventActionCode: 'GRANTED' | 'REVOKED';
  lastEventActorUserId: string;
  lastEventOccurredAt: string;
  lastEventReasonCode: string;
};

function assignment(overrides: Partial<Assignment> = {}): Assignment {
  return {
    assignmentId,
    userId: reviewerId,
    displayName: 'Lê Minh An',
    maskedContactEmail: 'm***n@example.test',
    userStatus: 'active',
    assignedByUserId: adminId,
    assignedAt: now,
    reasonCode: 'REVIEW_CAPACITY',
    revokedAt: null,
    revokedByUserId: null,
    revocationReasonCode: null,
    lastEventId: eventId,
    lastEventActionCode: 'GRANTED',
    lastEventActorUserId: adminId,
    lastEventOccurredAt: now,
    lastEventReasonCode: 'REVIEW_CAPACITY',
    ...overrides,
  };
}

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installAdminSession(
  page: Page,
  options: { denyList?: boolean; active?: Assignment[] } = {},
): Promise<{
  active: Assignment[];
  searchRequests: Array<{ url: string; query: string }>;
  grantRequests: Array<{ body: unknown; csrf: string | undefined; key: string | undefined }>;
}> {
  const active = options.active ? [...options.active] : [assignment()];
  const revoked = [
    assignment({
      assignmentId: '66666666-6666-4666-8666-666666666666',
      userId: '77777777-7777-4777-8777-777777777777',
      displayName: 'Trần Hải Yến',
      maskedContactEmail: 'y***n@example.test',
      revokedAt: '2026-11-15T10:00:00Z',
      revokedByUserId: adminId,
      revocationReasonCode: 'ACCESS_ENDED',
      lastEventId: '88888888-8888-4888-8888-888888888888',
      lastEventActionCode: 'REVOKED',
      lastEventReasonCode: 'ACCESS_ENDED',
      lastEventOccurredAt: '2026-11-15T10:00:00Z',
    }),
  ];
  const searchRequests: Array<{ url: string; query: string }> = [];
  const grantRequests: Array<{
    body: unknown;
    csrf: string | undefined;
    key: string | undefined;
  }> = [];

  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '8.3.0',
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
        id: adminId,
        displayName: 'Quản trị Novel',
        contactEmail: 'admin@example.test',
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
        { scope: 'security', permissions: ['publication.reviewers.manage'] },
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
        effectiveAt: now,
        documentUrl: '/api/v1/auth/policies/terms/terms-1',
      },
      privacyNotice: {
        version: 'privacy-1',
        effectiveAt: now,
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
  await page.route('**/api/v1/admin/security/role-assignments**', (route) => {
    if (options.denyList) return json(route, { code: 'ADMIN_REQUIRED' }, 403);
    const status = new URL(route.request().url()).searchParams.get('status');
    return json(route, { items: status === 'revoked' ? revoked : active });
  });
  await page.route('**/api/v1/admin/security/users/search', async (route) => {
    const body = route.request().postDataJSON() as { query: string };
    searchRequests.push({ url: route.request().url(), query: body.query });
    await json(route, {
      items: [
        {
          userId: candidateId,
          displayName: 'Phạm Ngọc Lan',
          maskedContactEmail: 'l***n@example.test',
          status: 'active',
        },
      ],
    });
  });
  await page.route('**/api/v1/admin/security/users/*/roles/publication_reviewer', async (route) => {
    grantRequests.push({
      body: route.request().postDataJSON(),
      csrf: route.request().headers()['x-csrf-token'],
      key: route.request().headers()['idempotency-key'],
    });
    const granted = assignment({
      assignmentId: '99999999-9999-4999-8999-999999999999',
      userId: candidateId,
      displayName: 'Phạm Ngọc Lan',
      maskedContactEmail: 'l***n@example.test',
      lastEventId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    });
    active.unshift(granted);
    await json(route, { resultCode: 'GRANTED', assignment: granted, replayed: false }, 201);
  });

  return { active, searchRequests, grantRequests };
}

for (const viewport of [
  { name: '360px mobile', width: 360, height: 800 },
  { name: '200% mobile zoom width', width: 180, height: 400 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 900 },
]) {
  test(`reviewer administration is accessible and reflows at ${viewport.name}`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await installAdminSession(page);
    await page.goto('/app/admin/reviewers');
    await expect(page.getByRole('heading', { name: 'Người duyệt xuất bản' })).toBeVisible();
    await expect(page.getByRole('table')).toContainText('m***n@example.test');
    await expect(page.getByRole('table')).toContainText(eventId);
    await expect(page.getByRole('table')).toContainText(adminId);

    const dimensions = await page.evaluate(() => {
      const viewport = document.documentElement.clientWidth;
      const offenders = Array.from(document.body.querySelectorAll('*'))
        .map((element) => {
          const bounds = element.getBoundingClientRect();
          return {
            tag: element.tagName,
            className: typeof element.className === 'string' ? element.className : '',
            text: element.textContent?.trim().slice(0, 60),
            left: Math.round(bounds.left),
            right: Math.round(bounds.right),
            width: Math.round(bounds.width),
          };
        })
        .filter((element) => element.right > viewport + 1 || element.left < -1)
        .slice(0, 12);
      return {
        document: document.documentElement.scrollWidth,
        viewport,
        body: document.body.scrollWidth,
        offenders,
      };
    });
    expect(dimensions.document, JSON.stringify(dimensions.offenders)).toBeLessThanOrEqual(
      dimensions.viewport,
    );
    expect(dimensions.body, JSON.stringify(dimensions.offenders)).toBeLessThanOrEqual(
      dimensions.viewport,
    );
    await expectNoAxeViolations(page);

    if (viewport.width === 360 || viewport.width === 180) {
      await page.screenshot({
        path: testInfo.outputPath(`reviewer-admin-${viewport.width}px.png`),
        fullPage: true,
      });
    }
  });
}

test('searches in the request body and requires an auditable confirmed grant', async ({ page }) => {
  const requests = await installAdminSession(page, { active: [] });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/admin/reviewers');

  const query = 'new.reader@example.test';
  await page.getByRole('searchbox', { name: 'Email hoặc UUID' }).fill(query);
  await page.getByRole('button', { name: 'Tìm tài khoản' }).click();
  await expect(page.getByText('Phạm Ngọc Lan')).toBeVisible();
  expect(requests.searchRequests).toHaveLength(1);
  expect(requests.searchRequests[0].query).toBe(query);
  expect(requests.searchRequests[0].url).not.toContain(query);
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Chọn Phạm Ngọc Lan làm người duyệt' }).click();
  await page.getByRole('button', { name: 'Tiếp tục cấp quyền' }).click();
  const dialog = page.getByRole('dialog', { name: 'Cấp quyền người duyệt?' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByLabel('Lý do cấp quyền')).toHaveValue('REVIEW_CAPACITY');
  await expectNoAxeViolations(page);

  await dialog.getByRole('button', { name: 'Xác nhận cấp quyền' }).click();
  await expect(page.locator('.reviewer-success')).toContainText('Đã cấp quyền người duyệt');
  await expect(page.getByRole('table')).toContainText('Phạm Ngọc Lan');
  expect(requests.grantRequests).toHaveLength(1);
  expect(requests.grantRequests[0].body).toEqual({ reasonCode: 'REVIEW_CAPACITY' });
  expect(requests.grantRequests[0].csrf).toBe(csrfToken);
  expect(requests.grantRequests[0].key).toMatch(/^[A-Za-z0-9._~-]{16,128}$/);
});

test('revoked history is readable and a denied administrator sees no account details', async ({
  page,
}) => {
  await installAdminSession(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/admin/reviewers');
  await page.getByRole('button', { name: 'Đã thu hồi' }).click();
  await expect(page.getByRole('table')).toContainText('Trần Hải Yến');
  await expect(page.getByRole('table')).toContainText('Kết thúc quyền truy cập');
  await expectNoAxeViolations(page);

  const deniedPage = await page.context().newPage();
  await installAdminSession(deniedPage, { denyList: true });
  await deniedPage.setViewportSize({ width: 360, height: 800 });
  await deniedPage.goto('/app/admin/reviewers');
  await expect(
    deniedPage.getByRole('heading', { name: 'Bạn không có quyền quản lý người duyệt' }),
  ).toBeVisible();
  await expect(deniedPage.getByRole('table')).toHaveCount(0);
  await expectNoAxeViolations(deniedPage);
});
