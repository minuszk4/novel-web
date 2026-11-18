import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const invitationId = '55555555-5555-4555-8555-555555555555';
const workspaceId = '11111111-1111-4111-8111-111111111111';
const invitationToken = 'A'.repeat(43);
const csrfToken = 'B'.repeat(43);
const expiry = '2026-11-25T10:00:00Z';
const role = 'editor';

for (const viewport of [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: '200% reflow proxy 180×400', width: 180, height: 400 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
]) {
  test(`recipient can review and accept a workspace invitation at ${viewport.name}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const requests: string[] = [];
    await installRecipientApi(page, requests);

    await page.goto(`/workspace-invitations/${invitationId}#token=${invitationToken}`);
    await expect(page.getByRole('heading', { name: 'Bạn được mời tham gia' })).toBeVisible();
    await expect(page.getByText('Nhóm biên tập', { exact: true })).toBeVisible();
    await expect(page.getByText('biên tập viên', { exact: false })).toBeVisible();
    await expect(page).toHaveURL(`/workspace-invitations/${invitationId}`);
    expect(await page.evaluate(() => window.location.hash)).toBe('');
    expect(
      await page.evaluate(
        (id) => sessionStorage.getItem(`novel.workspace-invitation-token.v1.${id}`),
        invitationId,
      ),
    ).toBe(invitationToken);
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Chấp nhận lời mời' }).click();
    await expect(
      page.getByText('Bạn đã tham gia workspace. Quyền truy cập đã được cập nhật.'),
    ).toBeVisible();
    await expect(page.getByRole('link', { name: 'Mở không gian cộng tác' })).toHaveAttribute(
      'href',
      '/app/workspaces',
    );
    expect(requests).toEqual(['resolve', 'accept']);
    expect(
      await page.evaluate(
        (id) => sessionStorage.getItem(`novel.workspace-invitation-token.v1.${id}`),
        invitationId,
      ),
    ).toBeNull();
    await expectNoAxeViolations(page);
    await expectNoHorizontalOverflow(page);
  });
}

test('recipient can reject once and the browser token is removed after confirmation', async ({
  page,
}) => {
  const requests: string[] = [];
  await installRecipientApi(page, requests);
  await page.goto(`/workspace-invitations/${invitationId}#token=${invitationToken}`);
  await expect(page.getByRole('button', { name: 'Từ chối' })).toBeVisible();
  await page.getByRole('button', { name: 'Từ chối' }).click();
  await expect(
    page.getByText('Bạn đã từ chối lời mời này. Liên kết không thể dùng lại.'),
  ).toBeVisible();
  expect(requests).toEqual(['resolve', 'reject']);
  expect(
    await page.evaluate(
      (id) => sessionStorage.getItem(`novel.workspace-invitation-token.v1.${id}`),
      invitationId,
    ),
  ).toBeNull();
});

test('anonymous recipient returns to the same invite after Google sign-in without a token URL', async ({
  page,
}) => {
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { code: 'UNAUTHENTICATED' }, 401));
  await page.goto(`/workspace-invitations/${invitationId}#token=${invitationToken}`);
  const signIn = page.getByRole('link', { name: 'Đăng nhập bằng Google' });
  await expect(signIn).toBeVisible();
  await expect(page).toHaveURL(`/workspace-invitations/${invitationId}`);
  expect(await signIn.getAttribute('href')).toContain(
    `/sign-in?returnTo=%2Fworkspace-invitations%2F${invitationId}`,
  );
  expect(await signIn.getAttribute('href')).not.toContain(invitationToken);
  expect(
    await page.evaluate(
      (id) => sessionStorage.getItem(`novel.workspace-invitation-token.v1.${id}`),
      invitationId,
    ),
  ).toBe(invitationToken);
  await expectNoAxeViolations(page);
});

test('recipient is sent to current policy setup with the same invite return path', async ({
  page,
}) => {
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route(`**/api/v1/me/workspace-invitations/${invitationId}/resolve`, (route) =>
    json(route, { code: 'POLICY_ACCEPTANCE_REQUIRED' }, 403),
  );
  await page.goto(`/workspace-invitations/${invitationId}#token=${invitationToken}`);

  const policyLink = page.getByRole('link', { name: 'Xem chính sách và tiếp tục' });
  await expect(policyLink).toBeVisible();
  await expect(page.getByRole('status')).toContainText('xác nhận chính sách hiện hành');
  expect(await policyLink.getAttribute('href')).toContain(
    `/app/setup?returnUrl=%2Fworkspace-invitations%2F${invitationId}`,
  );
  expect(await policyLink.getAttribute('href')).not.toContain(invitationToken);
  expect(
    await page.evaluate(
      (id) => sessionStorage.getItem(`novel.workspace-invitation-token.v1.${id}`),
      invitationId,
    ),
  ).toBe(invitationToken);
  await expectNoAxeViolations(page);
});

test('recipient can retry a temporary invitation-service error without losing the token', async ({
  page,
}) => {
  let resolveAttempts = 0;
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route(`**/api/v1/me/workspace-invitations/${invitationId}/resolve`, async (route) => {
    expect(route.request().postDataJSON()).toEqual({ token: invitationToken });
    resolveAttempts += 1;
    if (resolveAttempts === 1) {
      return json(route, { code: 'WORKSPACE_INVITATIONS_UNAVAILABLE' }, 503);
    }
    return json(route, {
      id: invitationId,
      workspaceId,
      workspaceName: 'Nhóm biên tập',
      role,
      expiresAt: expiry,
    });
  });
  await page.goto(`/workspace-invitations/${invitationId}#token=${invitationToken}`);
  await expect(page.getByRole('status')).toContainText('Hãy thử lại sau');
  await page.getByRole('button', { name: 'Thử lại' }).click();
  await expect(page.getByRole('heading', { name: 'Bạn được mời tham gia' })).toBeVisible();
  expect(resolveAttempts).toBe(2);
  expect(
    await page.evaluate(
      (id) => sessionStorage.getItem(`novel.workspace-invitation-token.v1.${id}`),
      invitationId,
    ),
  ).toBe(invitationToken);
  await expectNoAxeViolations(page);
  await expectNoHorizontalOverflow(page);
});

async function installRecipientApi(page: Page, requests: string[]): Promise<void> {
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: csrfToken }));
  await page.route(`**/api/v1/me/workspace-invitations/${invitationId}/**`, async (route) => {
    const request = route.request();
    const action = new URL(request.url()).pathname.split('/').at(-1)!;
    expect(request.method()).toBe('POST');
    expect(request.headers()['x-csrf-token']).toBe(csrfToken);
    expect(request.postDataJSON()).toEqual({ token: invitationToken });
    expect(request.url()).not.toContain(invitationToken);
    requests.push(action);
    if (action === 'resolve') {
      return json(route, {
        id: invitationId,
        workspaceId,
        workspaceName: 'Nhóm biên tập',
        role,
        expiresAt: expiry,
      });
    }
    return json(route, { id: invitationId, status: action === 'accept' ? 'accepted' : 'rejected' });
  });
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  const report = await page.evaluate(() => {
    const viewport = document.documentElement.clientWidth;
    const offenders = [...document.querySelectorAll<HTMLElement>('body *')]
      .map((element) => {
        const rect = element.getBoundingClientRect();
        return {
          element: `${element.tagName.toLowerCase()}${element.className ? `.${String(element.className).replaceAll(' ', '.')}` : ''}`,
          text: element.innerText?.slice(0, 60),
          left: Math.round(rect.left),
          right: Math.round(rect.right),
          width: Math.round(rect.width),
          scrollWidth: element.scrollWidth,
        };
      })
      .filter((item) => item.left < 0 || item.right > viewport);
    return {
      document: document.documentElement.scrollWidth,
      viewport,
      offenders: offenders.slice(0, 12),
    };
  });
  expect(report.document, JSON.stringify(report.offenders)).toBeLessThanOrEqual(report.viewport);
}

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}
