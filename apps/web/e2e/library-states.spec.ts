import { expect, expectNoAxeViolations, test } from './support';
import type { Page, Route } from '@playwright/test';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`private library empty and populated states work at ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.addInitScript(() => {
      if (!sessionStorage.getItem('novel.test.library.initialized')) {
        localStorage.removeItem('novel.demo.library.v1');
        sessionStorage.setItem('novel.test.library.initialized', 'true');
      }
    });
    await page.goto('/app/setup');
    await page.locator('.consent-row').nth(0).click();
    await page.locator('.consent-row').nth(1).click();
    await page.getByRole('button', { name: 'Vào không gian đọc' }).click();

    await page.getByRole('link', { name: 'Thư viện', exact: true }).click();
    await expect(page).toHaveURL(/\/app\/library$/);
    await expect(page.getByRole('heading', { name: 'Chưa có gì trên kệ' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Thêm tác phẩm', exact: true })).toBeVisible();
    await expectNoAxeViolations(page);

    await page.getByRole('link', { name: 'Thêm tác phẩm', exact: true }).click();
    await page.locator('#work-file').setInputFiles({
      name: 'quyen-truyen-rieng.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('local demo fixture; content is not uploaded'),
    });
    await page.locator('.rights-check').click();
    await page.getByRole('button', { name: /Xem lại thông tin/ }).click();
    await page.locator('#work-title').fill('Quán Trọ Ánh Trăng');
    await page.locator('#work-author').fill('Nguyệt Kiều');
    await page.getByRole('button', { name: /Thêm vào thư viện demo/ }).click();

    await page.getByRole('link', { name: 'Xem trong thư viện' }).click();
    await expect(page).toHaveURL(/\/app\/library$/);
    const allFilter = page.getByRole('button', { name: /Tất cả/ });
    const readingFilter = page.getByRole('button', { name: /Đang đọc/ });
    const wantToReadFilter = page.getByRole('button', { name: /Đọc sau/ });
    await expect(allFilter).toHaveAttribute('aria-pressed', 'true');
    await expect(readingFilter).toBeEnabled();
    await expect(wantToReadFilter).toBeEnabled();

    const work = page.locator('.library-work-row');
    await expect(work.getByRole('heading', { name: 'Quán Trọ Ánh Trăng' })).toBeVisible();
    await expect(work).toContainText('Nguyệt Kiều');
    await expect(work).toContainText('quyen-truyen-rieng.txt');
    await expect(work.locator('.private-tag')).toHaveText('RIÊNG TƯ · DEMO');
    await expect(work).toContainText('chưa có chương để đọc');
    const readingStatus = work.getByRole('combobox', { name: 'Trạng thái đọc: Quán Trọ Ánh Trăng' });
    await expect(readingStatus).toHaveValue('want-to-read');
    await readingStatus.selectOption('reading');
    await readingFilter.click();
    await expect(work).toBeVisible();
    await expect(readingFilter).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('textbox', { name: 'Tên bộ sưu tập mới' }).fill('Đọc cuối tuần');
    await page.getByRole('button', { name: 'Tạo bộ sưu tập' }).click();
    const collection = page.locator('.library-collection-chip');
    await expect(collection).toHaveAttribute('aria-pressed', 'false');
    await collection.click();
    await expect(collection).toHaveAttribute('aria-pressed', 'true');
    await collection.click();
    await expect(collection).toHaveAttribute('aria-pressed', 'false');
    await collection.click();

    await page.reload();
    await expect(page.getByRole('heading', { name: 'Quán Trọ Ánh Trăng' })).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'Trạng thái đọc: Quán Trọ Ánh Trăng' }))
      .toHaveValue('reading');
    await expect(page.locator('.library-collection-chip')).toHaveAttribute('aria-pressed', 'true');
    await expectNoAxeViolations(page);

    await page.context().setOffline(true);
    await expect(page.locator('.library-offline-message')).toContainText('Bạn đang ngoại tuyến');
    await expect(page.getByRole('heading', { name: 'Quán Trọ Ánh Trăng' })).toBeVisible();
    await page.context().setOffline(false);

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  });
}

test('account library uses server collections, CSRF and ETag conflict recovery', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  const workId = '22222222-2222-4222-8222-222222222222';
  const collectionId = '11111111-1111-4111-8111-111111111111';
  const storageKey = 'novel.demo.library.v1';
  const libraryWork = {
    id: workId,
    title: 'Tác phẩm tài khoản',
    author: 'Độc giả',
    sourceName: 'metadata.txt',
    importedAt: '2026-11-10T00:00:00Z',
    visibility: 'private' as const,
  };
  await page.addInitScript(({ key, state }) => localStorage.setItem(key, JSON.stringify(state)), {
    key: storageKey,
    state: {
      version: 1,
      importedWork: libraryWork,
      readingStatusByWork: { [workId]: 'want-to-read' },
      collections: [],
      collectionIdsByWork: { [workId]: [] },
    },
  });

  await installAcceptedAccountRoutes(page);

  const collection = {
    id: collectionId,
    name: 'Đọc cuối tuần',
    workCount: 0,
    createdAt: '2026-11-10T10:00:00Z',
    updatedAt: '2026-11-10T10:00:00Z',
  };
  let accountCollections: typeof collection[] = [];
  let serverState = {
    workId,
    readingStatus: 'want-to-read',
    collectionIds: [] as string[],
    version: 0,
    updatedAt: null as string | null,
  };
  let staleWriteNext = false;
  const writes: { ifMatch: string | undefined; csrf: string | undefined; body: { readingStatus: string; collectionIds: string[] } }[] = [];

  await page.route('**/api/v1/me/library/collections', async (route) => {
    if (route.request().method() === 'GET') {
      await json(route, accountCollections);
      return;
    }
    const headers = route.request().headers();
    expect(headers['x-csrf-token']).toBe('session-library-csrf');
    const created = { ...collection, ...route.request().postDataJSON() as { name: string } };
    accountCollections = [created];
    await json(route, created, 201);
  });
  await page.route(`**/api/v1/me/library/collections/${collectionId}`, async (route) => {
    expect(route.request().method()).toBe('DELETE');
    expect(route.request().headers()['x-csrf-token']).toBe('session-library-csrf');
    accountCollections = [];
    serverState = { ...serverState, collectionIds: [], version: serverState.version + 1, updatedAt: '2026-11-10T10:01:00Z' };
    await route.fulfill({ status: 204 });
  });
  await page.route(`**/api/v1/me/works/${workId}/library-state`, async (route) => {
    if (route.request().method() === 'GET') {
      await json(route, serverState, 200, { etag: `"v${serverState.version}"` });
      return;
    }

    const headers = route.request().headers();
    const body = route.request().postDataJSON() as { readingStatus: string; collectionIds: string[] };
    writes.push({ ifMatch: headers['if-match'], csrf: headers['x-csrf-token'], body });
    expect(headers['x-csrf-token']).toBe('session-library-csrf');
    if (staleWriteNext) {
      staleWriteNext = false;
      serverState = { ...serverState, readingStatus: 'completed', collectionIds: [], version: 2, updatedAt: '2026-11-10T10:01:00Z' };
      await json(route, {
        code: 'LIBRARY_STATE_VERSION_STALE',
        detail: 'Library state changed elsewhere',
        latest: serverState,
      }, 409, { etag: '"v2"' });
      return;
    }
    const expectedVersion = serverState.version;
    expect(headers['if-match']).toBe(`"v${expectedVersion}"`);
    serverState = { ...serverState, ...body, version: expectedVersion + 1, updatedAt: '2026-11-10T10:02:00Z' };
    await json(route, serverState, 200, { etag: `"v${serverState.version}"` });
  });

  await page.goto('/app/library');
  await expect(page.getByText('Bộ sưu tập đã được tải từ tài khoản.')).toBeVisible();
  await expect(page.getByText('RIÊNG TƯ · TÀI KHOẢN')).toBeVisible();
  await expectNoAxeViolations(page);

  await page.getByRole('textbox', { name: 'Tên bộ sưu tập mới' }).fill(collection.name);
  await page.getByRole('button', { name: 'Tạo bộ sưu tập' }).click();
  const collectionChip = page.locator('.library-collection-chip');
  await expect(collectionChip).toHaveAttribute('aria-pressed', 'false');

  const status = page.getByRole('combobox', { name: 'Trạng thái đọc: Tác phẩm tài khoản' });
  await status.selectOption('reading');
  await expect(status).toHaveValue('reading');
  expect(writes[0]).toEqual({
    ifMatch: '"v0"',
    csrf: 'session-library-csrf',
    body: { readingStatus: 'reading', collectionIds: [] },
  });

  staleWriteNext = true;
  await collectionChip.click();
  await expect(page.getByRole('alert')).toContainText('đổi trên thiết bị khác');
  await expect(status).toHaveValue('completed');
  await expect(collectionChip).toHaveAttribute('aria-pressed', 'false');
  expect(writes[1].ifMatch).toBe('"v1"');

  await collectionChip.click();
  await expect(collectionChip).toHaveAttribute('aria-pressed', 'true');
  expect(writes[2]).toEqual({
    ifMatch: '"v2"',
    csrf: 'session-library-csrf',
    body: { readingStatus: 'completed', collectionIds: [collectionId] },
  });

  await page.getByRole('button', { name: 'Xóa bộ sưu tập Đọc cuối tuần' }).click();
  await expect(page.getByRole('group', { name: 'Xác nhận xóa Đọc cuối tuần' })).toContainText('trạng thái đọc không đổi');
  await page.getByRole('button', { name: 'Xác nhận xóa' }).click();
  await expect(page.getByRole('button', { name: /Đọc cuối tuần/ })).toHaveCount(0);
  await expect(status).toHaveValue('completed');
  await expectNoAxeViolations(page);

  const dimensions = await page.evaluate(() => ({ document: document.documentElement.scrollWidth, viewport: document.documentElement.clientWidth }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
});

test('account library exposes a retry when collection loading fails', async ({ page }) => {
  await page.addInitScript(() => localStorage.removeItem('novel.demo.library.v1'));
  await installAcceptedAccountRoutes(page);
  let collectionReads = 0;
  await page.route('**/api/v1/me/library/collections', async (route) => {
    collectionReads += 1;
    if (collectionReads === 1) {
      await json(route, { code: 'LIBRARY_STATE_UNAVAILABLE' }, 503);
      return;
    }
    await json(route, []);
  });

  await page.goto('/app/library');
  await expect(page.getByRole('alert')).toContainText('Máy chủ chưa tải được dữ liệu');
  await expect(page.getByRole('button', { name: 'Tạo bộ sưu tập' })).toBeDisabled();
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Thử tải lại' }).click();
  await expect(page.getByText('Bộ sưu tập đã được tải từ tài khoản.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tạo bộ sưu tập' })).toBeEnabled();
  expect(collectionReads).toBe(2);
  await expectNoAxeViolations(page);
});

const acceptedAccount = {
  user: { id: 'user-1', displayName: 'Độc giả', contactEmail: 'reader@example.test', avatarUrl: null, status: 'ACTIVE' },
  identityProviders: ['google'],
  policy: { termsAcceptedVersion: 'terms-1', privacyNoticeAcknowledgedVersion: 'privacy-1', requiredAcceptanceComplete: true },
  roles: [],
  features: { privateLibraryEnabled: false, importEnabled: false, readerEnabled: false, commerceEnabled: false, cultivationEnabled: false },
};

async function installAcceptedAccountRoutes(page: Page): Promise<void> {
  await page.route('**/api/v1/auth/me', (route) => json(route, acceptedAccount));
  await page.route('**/api/v1/auth/policies/current', (route) => json(route, {
    terms: { version: 'terms-1', effectiveAt: '2026-11-01T00:00:00Z', documentUrl: '/api/v1/auth/policies/terms/terms-1' },
    privacyNotice: { version: 'privacy-1', effectiveAt: '2026-11-01T00:00:00Z', documentUrl: '/api/v1/auth/policies/privacy-notice/privacy-1' },
  }));
  await page.route('**/api/v1/auth/policies/terms/**', (route) => route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Terms' }));
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) => route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Privacy' }));
  await page.route('**/api/v1/auth/csrf', (route) => json(route, { token: 'session-library-csrf' }));
}

async function json(route: Route, body: unknown, status = 200, headers: Record<string, string> = {}): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) });
}
