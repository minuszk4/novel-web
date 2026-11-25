import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const ownerId = '11111111-aaaa-4aaa-8aaa-111111111111';
const editorId = '22222222-bbbb-4bbb-8bbb-222222222222';
const readerId = '33333333-cccc-4ccc-8ccc-333333333333';
const csrfToken = 'A'.repeat(43);
const timestamp = '2026-11-18T10:00:00Z';
const viewports = [
  { name: 'mobile 360×800', width: 360, height: 800 },
  { name: '200% reflow proxy 180×400', width: 180, height: 400 },
  { name: 'desktop 1280×900', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`workspace owner can manage access at ${viewport.name}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const api = await installSignedInOwner(page);
    await page.goto('/app/workspaces');

    await expect(page.getByRole('heading', { name: 'Không gian cộng tác' })).toBeVisible();
    await expect(page.getByText('Bạn chưa tham gia không gian nào')).toBeVisible();
    await page.getByLabel('Tên không gian').fill('Nhóm đọc thử');
    await page.getByRole('button', { name: 'Tạo không gian' }).click();
    await expect(page.getByLabel('Không gian của bạn')).toContainText('Nhóm đọc thử');
    await expect(page.getByRole('heading', { name: 'Thành viên', exact: true })).toBeVisible();
    await expect(api.createdId).toMatch(/^[0-9a-f-]{36}$/i);
    await page.getByLabel('Địa chỉ email').fill('editor@example.test');
    await page.getByLabel('Vai trò được mời').selectOption('editor');
    await page.getByLabel(/Tôi có quyền liên hệ/).check();
    await page.getByRole('button', { name: 'Gửi lời mời' }).click();
    await expect(page.getByText(/Đã xếp lời mời vào hàng gửi/)).toBeVisible();
    await expect(page.locator('.workspace-invitation-list')).toContainText('Biên tập viên');
    await expect(page.locator('.workspace-invitation-list')).not.toContainText(
      'editor@example.test',
    );
    await expect(api.mutations).toContain('invitation:create:editor');
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Thu hồi lời mời' }).click();
    const invitationRevoke = page.getByRole('group', { name: 'Xác nhận thu hồi lời mời' });
    await expect(invitationRevoke).toBeVisible();
    await invitationRevoke.getByRole('button', { name: 'Xác nhận thu hồi' }).click();
    await expect(page.locator('.workspace-invitation-list')).toContainText(
      'Không có lời mời đang chờ xử lý',
    );
    await expect(api.mutations).toContain('invitation:revoke');
    await expectNoAxeViolations(page);

    await page.getByLabel('Tạo thư viện').fill('Truyện đang dịch');
    await page.getByRole('button', { name: 'Tạo thư viện' }).click();
    await expect.poll(() => api.libraryId).not.toBe('');
    const createdLibraryId = api.libraryId;
    const sharedLibraryLink = page.getByRole('link', { name: 'Mở thư viện Truyện đang dịch' });
    await expect(sharedLibraryLink).toHaveAttribute(
      'href',
      `/app/library?libraryId=${createdLibraryId}`,
    );
    await sharedLibraryLink.click();
    await expect(page).toHaveURL(new RegExp(`/app/library\\?libraryId=${createdLibraryId}`));
    await expect.poll(() => api.libraryQueries).toContain(createdLibraryId);
    const workReadsBeforeUnavailableLink = api.libraryQueries.length;
    await page.goto('/app/library?libraryId=99999999-9999-4999-8999-999999999999');
    await expect(
      page.getByText('Thư viện này không tồn tại hoặc bạn không còn quyền truy cập.'),
    ).toBeVisible();
    expect(api.libraryQueries).toHaveLength(workReadsBeforeUnavailableLink);
    await page.goto('/app/workspaces');
    await expect(page.getByRole('heading', { name: 'Thành viên', exact: true })).toBeVisible();

    await page.getByLabel('UUID tài khoản thành viên').fill(readerId);
    await page.getByLabel('Vai trò ban đầu').selectOption('reader');
    await page.getByRole('button', { name: 'Cấp quyền thành viên' }).click();
    await expect(page.locator('.workspace-member-list')).toContainText(readerId);
    await expect(page.locator('.workspace-member-list')).toContainText('Người đọc');
    await expect(api.mutations).toContain('add:reader');

    const readerCard = page.locator('.workspace-member-card').filter({ hasText: readerId });
    await readerCard.getByLabel('Vai trò').selectOption('editor');
    await readerCard.getByRole('button', { name: 'Lưu vai trò' }).click();
    await expect(readerCard.locator('.workspace-role-label')).toHaveText('Biên tập viên');
    await expect.poll(() => api.mutations).toContain('role:editor');
    await expectNoAxeViolations(page);

    await readerCard.getByRole('button', { name: 'Thu hồi quyền' }).click();
    await expect(
      readerCard.getByRole('group', { name: `Xác nhận thu hồi ${readerId}` }),
    ).toBeVisible();
    await expect(readerCard).toContainText('mất quyền mở thư viện');
    await readerCard.getByRole('button', { name: 'Xác nhận thu hồi' }).click();
    await expect(readerCard).toContainText('Đã thu hồi');
    await expect(api.mutations).toContain('revoke');

    await page.getByLabel('UUID tài khoản thành viên').fill(editorId);
    await page.getByLabel('Vai trò ban đầu').selectOption('editor');
    await page.getByRole('button', { name: 'Cấp quyền thành viên' }).click();
    await expect(page.locator('.workspace-member-list')).toContainText(editorId);
    await page.getByLabel('Chủ sở hữu mới').selectOption(editorId);
    await page.getByRole('button', { name: 'Tiếp tục chuyển quyền' }).click();
    const transfer = page.getByRole('group', { name: 'Xác nhận chuyển quyền sở hữu' });
    await expect(transfer).toBeVisible();
    await transfer.getByRole('button', { name: 'Xác nhận chuyển quyền' }).click();
    await expect(page.locator('.workspace-heading')).toContainText('Không gian cộng tác');
    await expect(page.locator('.workspace-detail-card > .workspace-members-heading')).toContainText(
      'QUYỀN CỦA BẠN: Quản trị viên',
    );
    await expect(page.locator('.workspace-audit')).toBeVisible();
    await expect(page.locator('.workspace-footnote')).toContainText(
      'Nhật ký thành viên chỉ dành cho chủ sở hữu và quản trị viên',
    );
    await expect(api.mutations).toContain('transfer');
    await expectNoAxeViolations(page);

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
      containers: [
        '.app-frame',
        '.app-pane',
        '.app-main',
        'app-workspace-management-page',
        '.workspace-page',
      ].map((selector) => {
        const element = document.querySelector<HTMLElement>(selector);
        return {
          selector,
          width: element?.getBoundingClientRect().width,
          minWidth: element ? getComputedStyle(element).minWidth : null,
          display: element ? getComputedStyle(element).display : null,
        };
      }),
      overflowers: Array.from(document.querySelectorAll<HTMLElement>('*'))
        .map((element) => ({
          selector: `${element.tagName.toLowerCase()}${element.className ? `.${String(element.className).replaceAll(' ', '.')}` : ''}`,
          text: element.innerText?.slice(0, 50),
          right: element.getBoundingClientRect().right,
          width: element.getBoundingClientRect().width,
        }))
        .filter((element) => element.right > document.documentElement.clientWidth + 1)
        .slice(0, 8),
    }));
    expect(
      dimensions.document,
      JSON.stringify({ containers: dimensions.containers, overflowers: dimensions.overflowers }),
    ).toBeLessThanOrEqual(dimensions.viewport);
    await page.screenshot({
      path: testInfo.outputPath(`workspace-management-${viewport.width}x${viewport.height}.png`),
      fullPage: true,
    });
  });
}

async function installSignedInOwner(page: Page) {
  let workspace: Record<string, unknown> | null = null;
  let scopeVersion = 1;
  let members: Array<Record<string, unknown>> = [
    {
      userId: ownerId,
      role: 'owner',
      status: 'active',
      joinedAt: timestamp,
      revokedAt: null,
    },
  ];
  const mutations: string[] = [];
  let createdId = '';
  let createdLibraryId = '';
  const sharedLibraries: Array<Record<string, unknown>> = [];
  const invitations: Array<Record<string, unknown>> = [];
  const auditEvents: Array<Record<string, unknown>> = [];
  const libraryQueries: string[] = [];

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();

    if (path === '/api/v1/system/status' && method === 'GET') {
      return json(route, {
        apiVersion: '10.14.0',
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
          translationFakePreviewEnabled: false,
          publicCatalogEnabled: false,
        },
      });
    }
    if (path === '/api/v1/auth/me' && method === 'GET') {
      return json(route, {
        user: {
          id: ownerId,
          displayName: 'Chủ nhóm',
          contactEmail: 'owner@example.test',
          avatarUrl: null,
          status: 'ACTIVE',
        },
        policy: {
          termsAcceptedVersion: 'terms-1',
          privacyNoticeAcknowledgedVersion: 'privacy-1',
          requiredAcceptanceComplete: true,
        },
      });
    }
    if (path === '/api/v1/auth/policies/current' && method === 'GET') {
      return json(route, {
        terms: {
          version: 'terms-1',
          effectiveAt: timestamp,
          documentUrl: '/api/v1/auth/policies/terms/terms-1',
        },
        privacyNotice: {
          version: 'privacy-1',
          effectiveAt: timestamp,
          documentUrl: '/api/v1/auth/policies/privacy-notice/privacy-1',
        },
      });
    }
    if (
      path.startsWith('/api/v1/auth/policies/terms/') ||
      path.startsWith('/api/v1/auth/policies/privacy-notice/')
    ) {
      return route.fulfill({
        status: 200,
        contentType: 'text/markdown',
        body: '# Chính sách kiểm thử',
      });
    }
    if (path === '/api/v1/auth/csrf' && method === 'GET') return json(route, { token: csrfToken });
    if (path === '/api/v1/me/notifications' && method === 'GET') {
      return route.fulfill({
        status: 200,
        headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
        body: JSON.stringify({ items: [], nextCursor: null, unreadCount: 0 }),
      });
    }
    if (path === '/api/v1/me/workspaces' && method === 'GET') {
      return json(route, { items: workspace ? [workspaceSummary()] : [] });
    }
    if (createdId && path === `/api/v1/workspaces/${createdId}/libraries` && method === 'GET') {
      return json(route, { items: sharedLibraries, nextCursor: null });
    }
    if (createdId && path === `/api/v1/workspaces/${createdId}/invitations` && method === 'GET') {
      return json(route, { items: invitations });
    }
    if (
      createdId &&
      path.startsWith(`/api/v1/workspaces/${createdId}/invitations/`) &&
      method === 'PUT'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(csrfToken);
      const requestedId = path.split('/').at(-1)!;
      const body = request.postDataJSON() as {
        email: string;
        role: string;
        confirmedPermissionToEmail: boolean;
      };
      expect(body.confirmedPermissionToEmail).toBe(true);
      expect(body.email).toBe('editor@example.test');
      expect(body.role).toBe('editor');
      const created = {
        id: requestedId,
        status: 'pending',
        expiresAt: '2026-11-25T10:00:00Z',
        message: 'Queued.',
      };
      invitations.push({
        id: requestedId,
        role: body.role,
        status: 'pending',
        createdAt: timestamp,
        expiresAt: created.expiresAt,
      });
      mutations.push('invitation:create:editor');
      return json(route, created, 202);
    }
    if (
      createdId &&
      path.startsWith(`/api/v1/workspaces/${createdId}/invitations/`) &&
      method === 'DELETE'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(csrfToken);
      invitations.splice(0, invitations.length);
      mutations.push('invitation:revoke');
      return route.fulfill({ status: 204 });
    }
    if (
      createdId &&
      path.startsWith(`/api/v1/workspaces/${createdId}/libraries/`) &&
      method === 'PUT'
    ) {
      expect(request.headers()['x-csrf-token']).toBe(csrfToken);
      const requestedId = path.split('/').at(-1)!;
      const body = request.postDataJSON() as { name: string };
      let library = sharedLibraries.find((candidate) => candidate.id === requestedId);
      if (!library) {
        if (sharedLibraries.some((candidate) => candidate.name === body.name)) {
          return json(route, { code: 'LIBRARY_NAME_CONFLICT' }, 409);
        }
        library = {
          id: requestedId,
          name: body.name,
          visibility: 'private',
          isDefault: false,
          createdAt: timestamp,
        };
        sharedLibraries.push(library);
        createdLibraryId = requestedId;
        scopeVersion += 1;
        mutations.push('library:create');
      } else if (library.name !== body.name) {
        return json(route, { code: 'LIBRARY_NAME_CONFLICT' }, 409);
      }
      return json(route, library);
    }
    if (path === '/api/v1/me/library/collections' && method === 'GET') return json(route, []);
    if (path === '/api/v1/libraries' && method === 'GET') {
      return json(route, { items: sharedLibraries, nextCursor: null });
    }
    if (path === '/api/v1/works' && method === 'GET') {
      libraryQueries.push(url.searchParams.get('libraryId') ?? '');
      return json(route, { items: [], nextCursor: null });
    }
    if (path.startsWith('/api/v1/me/workspaces/') && method === 'PUT') {
      expect(request.headers()['x-csrf-token']).toBe(csrfToken);
      const requestedId = path.split('/').at(-1)!;
      expect(requestedId).toMatch(/^[0-9a-f-]{36}$/i);
      const body = request.postDataJSON() as { name?: string };
      if (!workspace) {
        createdId = requestedId;
        workspace = { id: createdId, name: body.name, createdAt: timestamp };
        auditEvents.push({
          eventId: '1',
          workspaceId: createdId,
          actorUserId: ownerId,
          subjectUserId: ownerId,
          action: 'WORKSPACE_CREATED',
          reason: 'INITIAL_OWNER',
          previousRole: null,
          newRole: 'owner',
          previousStatus: null,
          newStatus: 'active',
          occurredAt: timestamp,
        });
      } else if (createdId !== requestedId || workspace.name !== body.name) {
        return json(route, { code: 'WORKSPACE_MEMBER_CONFLICT' }, 409);
      }
      return json(route, { ...workspace, scopeVersion });
    }
    if (createdId && path === `/api/v1/workspaces/${createdId}/members` && method === 'GET') {
      return json(route, { items: members });
    }
    if (
      createdId &&
      path === `/api/v1/workspaces/${createdId}/membership-audit` &&
      method === 'GET'
    ) {
      const before = Number(url.searchParams.get('cursor') ?? Number.MAX_SAFE_INTEGER);
      const limit = Number(url.searchParams.get('limit') ?? 25);
      const page = auditEvents
        .filter((event) => Number(event.eventId) < before)
        .sort((left, right) => Number(right.eventId) - Number(left.eventId))
        .slice(0, limit);
      return json(route, { items: page, nextCursor: null });
    }
    if (createdId && path.startsWith(`/api/v1/workspaces/${createdId}/members/`)) {
      const targetId = path.split('/').at(-1)!;
      const current = members.find((member) => member.userId === targetId);
      if (method === 'PUT') {
        expect(request.headers()['x-csrf-token']).toBe(csrfToken);
        const body = request.postDataJSON() as { role: string };
        if (current) {
          current.role = body.role;
          current.status = 'active';
          current.revokedAt = null;
          mutations.push(`role:${body.role}`);
        } else {
          members.push({
            userId: targetId,
            role: body.role,
            status: 'active',
            joinedAt: timestamp,
            revokedAt: null,
          });
          mutations.push(`add:${body.role}`);
        }
        scopeVersion += 1;
        return json(
          route,
          members.find((member) => member.userId === targetId),
        );
      }
      if (method === 'DELETE' && current) {
        expect(request.headers()['x-csrf-token']).toBe(csrfToken);
        current.status = 'revoked';
        current.revokedAt = timestamp;
        mutations.push('revoke');
        scopeVersion += 1;
        return json(route, current);
      }
    }
    if (createdId && path === `/api/v1/workspaces/${createdId}/owner` && method === 'PUT') {
      expect(request.headers()['x-csrf-token']).toBe(csrfToken);
      const body = request.postDataJSON() as { newOwnerUserId: string };
      const oldOwner = members.find((member) => member.userId === ownerId);
      const newOwner = members.find((member) => member.userId === body.newOwnerUserId);
      if (oldOwner && newOwner && workspace) {
        oldOwner.role = 'admin';
        newOwner.role = 'owner';
        scopeVersion += 1;
        mutations.push('transfer');
        return json(route, { ...workspace, scopeVersion });
      }
    }

    return json(route, { code: 'NOT_FOUND' }, 404);
  });

  function workspaceSummary() {
    return {
      ...workspace,
      role: members.find((member) => member.userId === ownerId)?.role ?? 'reader',
      scopeVersion,
      joinedAt: timestamp,
    };
  }

  return {
    get createdId() {
      return createdId;
    },
    get libraryId() {
      return createdLibraryId;
    },
    mutations,
    libraryQueries,
  };
}

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}
