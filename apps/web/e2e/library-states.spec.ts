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
    const readingStatus = work.getByRole('combobox', {
      name: 'Trạng thái đọc: Quán Trọ Ánh Trăng',
    });
    await expect(readingStatus).toHaveValue('want-to-read');
    await readingStatus.selectOption('reading');
    await readingFilter.click();
    await expect(work).toBeVisible();
    await expect(readingFilter).toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('textbox', { name: 'Tên bộ sưu tập mới' }).fill('Đọc cuối tuần');
    await page.getByRole('button', { name: 'Tạo bộ sưu tập' }).click();
    const collection = page.locator(
      '.library-collection-list[aria-label="Bộ sưu tập của Quán Trọ Ánh Trăng"] .library-collection-chip',
    );
    await expect(collection).toHaveAttribute('aria-pressed', 'false');
    await collection.click();
    await expect(collection).toHaveAttribute('aria-pressed', 'true');
    await collection.click();
    await expect(collection).toHaveAttribute('aria-pressed', 'false');
    await collection.click();

    await page.reload();
    await expect(page.getByRole('heading', { name: 'Quán Trọ Ánh Trăng' })).toBeVisible();
    await expect(
      page.getByRole('combobox', { name: 'Trạng thái đọc: Quán Trọ Ánh Trăng' }),
    ).toHaveValue('reading');
    await expect(
      page.locator(
        '.library-collection-list[aria-label="Bộ sưu tập của Quán Trọ Ánh Trăng"] .library-collection-chip',
      ),
    ).toHaveAttribute('aria-pressed', 'true');
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

test('account library uses server collections, CSRF and ETag conflict recovery', async ({
  page,
}) => {
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

  await page.route('**/api/v1/libraries**', (route) =>
    json(route, {
      items: [
        {
          id: '33333333-3333-4333-8333-333333333333',
          name: 'Thư viện riêng',
          visibility: 'private',
          isDefault: true,
          createdAt: '2026-11-10T10:00:00Z',
        },
      ],
      nextCursor: null,
    }),
  );
  await page.route('**/api/v1/works**', (route) =>
    json(route, {
      items: [
        {
          id: workId,
          libraryId: '33333333-3333-4333-8333-333333333333',
          title: 'Tác phẩm tài khoản',
          author: 'Độc giả',
          language: 'vi',
          visibility: 'private',
          chapterCount: 0,
          createdAt: '2026-11-10T00:00:00Z',
        },
      ],
      nextCursor: null,
    }),
  );

  const collection = {
    id: collectionId,
    name: 'Đọc cuối tuần',
    workCount: 0,
    createdAt: '2026-11-10T10:00:00Z',
    updatedAt: '2026-11-10T10:00:00Z',
  };
  let accountCollections: (typeof collection)[] = [];
  let serverState = {
    workId,
    readingStatus: 'want-to-read',
    collectionIds: [] as string[],
    version: 0,
    updatedAt: null as string | null,
  };
  let staleWriteNext = false;
  const writes: {
    ifMatch: string | undefined;
    csrf: string | undefined;
    body: { readingStatus: string; collectionIds: string[] };
  }[] = [];

  await page.route('**/api/v1/me/library/collections', async (route) => {
    if (route.request().method() === 'GET') {
      await json(route, accountCollections);
      return;
    }
    const headers = route.request().headers();
    expect(headers['x-csrf-token']).toBe('session-library-csrf');
    const created = { ...collection, ...(route.request().postDataJSON() as { name: string }) };
    accountCollections = [created];
    await json(route, created, 201);
  });
  await page.route(`**/api/v1/me/library/collections/${collectionId}`, async (route) => {
    expect(route.request().method()).toBe('DELETE');
    expect(route.request().headers()['x-csrf-token']).toBe('session-library-csrf');
    accountCollections = [];
    serverState = {
      ...serverState,
      collectionIds: [],
      version: serverState.version + 1,
      updatedAt: '2026-11-10T10:01:00Z',
    };
    await route.fulfill({ status: 204 });
  });
  await page.route(`**/api/v1/me/works/${workId}/library-state`, async (route) => {
    if (route.request().method() === 'GET') {
      await json(route, serverState, 200, { etag: `"v${serverState.version}"` });
      return;
    }

    const headers = route.request().headers();
    const body = route.request().postDataJSON() as {
      readingStatus: string;
      collectionIds: string[];
    };
    writes.push({ ifMatch: headers['if-match'], csrf: headers['x-csrf-token'], body });
    expect(headers['x-csrf-token']).toBe('session-library-csrf');
    if (staleWriteNext) {
      staleWriteNext = false;
      serverState = {
        ...serverState,
        readingStatus: 'completed',
        collectionIds: [],
        version: 2,
        updatedAt: '2026-11-10T10:01:00Z',
      };
      await json(
        route,
        {
          code: 'LIBRARY_STATE_VERSION_STALE',
          detail: 'Library state changed elsewhere',
          latest: serverState,
        },
        409,
        { etag: '"v2"' },
      );
      return;
    }
    const expectedVersion = serverState.version;
    expect(headers['if-match']).toBe(`"v${expectedVersion}"`);
    serverState = {
      ...serverState,
      ...body,
      version: expectedVersion + 1,
      updatedAt: '2026-11-10T10:02:00Z',
    };
    await json(route, serverState, 200, { etag: `"v${serverState.version}"` });
  });

  await page.goto('/app/library');
  await expect(page.getByText('Bộ sưu tập đã được tải từ tài khoản.')).toBeVisible();
  await expect(page.getByText('RIÊNG TƯ · TÀI KHOẢN')).toBeVisible();
  await expectNoAxeViolations(page);

  await page.getByRole('textbox', { name: 'Tên bộ sưu tập mới' }).fill(collection.name);
  await page.getByRole('button', { name: 'Tạo bộ sưu tập' }).click();
  const collectionChip = page.locator('.library-work-collections .library-collection-chip');
  await page.locator('.library-work-collections summary').click();
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
  await expect(page.getByRole('group', { name: 'Xác nhận xóa Đọc cuối tuần' })).toContainText(
    'trạng thái đọc không đổi',
  );
  await page.getByRole('button', { name: 'Xác nhận xóa' }).click();
  await expect(page.getByRole('button', { name: /Đọc cuối tuần/ })).toHaveCount(0);
  await expect(status).toHaveValue('completed');
  await expectNoAxeViolations(page);

  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
});

test('account library loads scoped catalog pages and keeps local demo imports separate', async ({
  page,
}) => {
  const firstLibraryId = '33333333-3333-4333-8333-333333333333';
  const secondLibraryId = '44444444-4444-4444-8444-444444444444';
  const firstWorkId = '55555555-5555-4555-8555-555555555555';
  const secondWorkId = '66666666-6666-4666-8666-666666666666';
  const firstChapterId = '77777777-7777-4777-8777-777777777777';
  await page.setViewportSize({ width: 360, height: 800 });
  await page.addInitScript(() =>
    localStorage.setItem(
      'novel.demo.library.v1',
      JSON.stringify({
        version: 1,
        importedWork: {
          id: 'demo-local-work',
          title: 'Bản nhập demo',
          author: 'Người đọc',
          sourceName: 'demo.txt',
          importedAt: '2026-11-10T00:00:00Z',
          visibility: 'private',
        },
        readingStatusByWork: {},
        collections: [],
        collectionIdsByWork: {},
      }),
    ),
  );
  await installAcceptedAccountRoutes(page);

  await page.route('**/api/v1/libraries**', (route) =>
    json(route, {
      items: [
        {
          id: firstLibraryId,
          name: 'Thư viện chính',
          visibility: 'private',
          isDefault: true,
          createdAt: '2026-11-10T00:00:00Z',
        },
        {
          id: secondLibraryId,
          name: 'Kệ Workspace',
          visibility: 'private',
          isDefault: false,
          createdAt: '2026-11-09T00:00:00Z',
        },
      ],
      nextCursor: null,
    }),
  );
  let pageTwoFailureRetried = false;
  await page.route('**/api/v1/works**', (route) => {
    const requestUrl = new URL(route.request().url());
    const libraryId = requestUrl.searchParams.get('libraryId');
    if (libraryId === secondLibraryId) {
      return json(route, { items: [], nextCursor: null });
    }
    const cursor = requestUrl.searchParams.get('cursor');
    if (cursor && !pageTwoFailureRetried) {
      pageTwoFailureRetried = true;
      return json(route, { code: 'LIBRARY_UNAVAILABLE' }, 503);
    }
    const workId = cursor ? secondWorkId : firstWorkId;
    const title = cursor ? 'Tác phẩm hai' : 'Tác phẩm một';
    return json(route, {
      items: [
        {
          id: workId,
          libraryId: firstLibraryId,
          title,
          author: 'Tác giả',
          language: 'vi',
          visibility: 'private',
          chapterCount: 3,
          createdAt: '2026-11-10T00:00:00Z',
        },
      ],
      nextCursor: cursor ? null : 'page-2',
    });
  });
  await page.route('**/api/v1/me/works/*/library-state', (route) => {
    const workId = new URL(route.request().url()).pathname.split('/')[5];
    return json(
      route,
      {
        workId,
        readingStatus: workId === secondWorkId ? 'completed' : 'want-to-read',
        collectionIds: [],
        version: 0,
        updatedAt: null,
      },
      200,
      { etag: '"v0"' },
    );
  });
  await page.route(`**/api/v1/works/${firstWorkId}/reader-manifest**`, (route) =>
    json(route, {
      work: {
        id: firstWorkId,
        libraryId: firstLibraryId,
        title: 'Tác phẩm một',
        author: 'Tác giả',
        language: 'vi',
        visibility: 'private',
        chapterCount: 3,
        createdAt: '2026-11-10T00:00:00Z',
      },
      sourceLanguage: 'vi',
      chapters: [
        { id: firstChapterId, ordinal: 1, title: 'Chương mở đầu', accessState: 'readable' },
      ],
      nextCursor: null,
    }),
  );
  await page.route(`**/api/v1/chapters/${firstChapterId}/content**`, (route) =>
    json(route, {
      chapterId: firstChapterId,
      workId: firstWorkId,
      sourceRevisionId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      sourceLanguage: 'vi',
      segments: [
        {
          anchor: 'opening',
          type: 'paragraph',
          sourceText: 'Một đoạn mở đầu để kiểm tra luồng đọc.',
        },
      ],
      nextSegmentCursor: null,
    }),
  );

  await page.goto('/app/library');
  await expect(page.getByRole('heading', { name: 'Tác phẩm một' })).toBeVisible();
  await expect(page.getByText('“Bản nhập demo” chỉ được lưu trong demo')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tất cả 1' })).toBeVisible();
  await expect(page.getByText('Bộ lọc và số đếm hiện dựa trên 1 tác phẩm đã tải.')).toBeVisible();
  await expect(page.locator('.library-page .page-heading > .button')).toBeHidden();
  await expect(page.locator('.topbar-actions .quiet-link')).toBeVisible();
  const detailLink = page.getByRole('link', { name: 'Chi tiết tác phẩm' });
  await expect(detailLink).toBeVisible();
  for (let tab = 0; tab < 25; tab += 1) {
    if (await detailLink.evaluate((element) => document.activeElement === element)) break;
    await page.keyboard.press('Tab');
  }
  await expect(detailLink).toBeFocused();
  const focusGeometry = await page.evaluate(() => {
    const link = document.querySelector<HTMLElement>('.library-reader-link');
    const mobileNav = document.querySelector<HTMLElement>('.mobile-nav');
    if (!link || !mobileNav) throw new Error('Library Reader link or mobile navigation is missing');
    const linkBox = link.getBoundingClientRect();
    const navBox = mobileNav.getBoundingClientRect();
    return { linkHeight: linkBox.height, navGap: navBox.top - linkBox.bottom };
  });
  expect(focusGeometry.linkHeight).toBeGreaterThanOrEqual(44);
  expect(focusGeometry.navGap).toBeGreaterThanOrEqual(8);
  await expectNoAxeViolations(page);
  const libraryDimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(libraryDimensions.document).toBeLessThanOrEqual(libraryDimensions.viewport);
  await page.screenshot({ path: test.info().outputPath('audit-01-account-library-mobile.png') });
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(new RegExp(`/app/works/${firstWorkId}$`));
  await expect(page.getByRole('heading', { name: 'Tác phẩm một' })).toBeVisible();
  await page.getByRole('button', { name: 'Đọc từ đầu' }).click();
  await expect(page).toHaveURL(new RegExp(`/app/reader/${firstWorkId}/${firstChapterId}$`));
  await expect(page.getByRole('heading', { name: 'Chương mở đầu' })).toBeFocused();
  await expectNoAxeViolations(page);
  const readerDimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(readerDimensions.document).toBeLessThanOrEqual(readerDimensions.viewport);
  await page.screenshot({ path: test.info().outputPath('audit-02-account-reader-handoff.png') });
  await page.getByRole('link', { name: 'Quay lại chi tiết tác phẩm' }).click();
  await expect(page).toHaveURL(new RegExp(`/app/works/${firstWorkId}$`));
  await page.getByRole('link', { name: '← Về thư viện' }).click();
  await expect(page).toHaveURL(/\/app\/library$/);
  await expect(page.getByRole('heading', { name: 'Tác phẩm một' })).toBeVisible();
  await page.getByRole('button', { name: 'Hoàn thành 0' }).click();
  await expect(page.getByRole('heading', { name: 'Không có truyện trong mục này' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tải thêm tác phẩm' })).toBeVisible();
  await page.getByRole('button', { name: 'Tải thêm tác phẩm' }).click();
  await expect(
    page
      .locator('.library-status-message.library-sync-error')
      .filter({ hasText: 'Máy chủ chưa tải được thư viện' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Thử lại trang tiếp theo' }).click();
  await expect(page.getByRole('heading', { name: 'Tác phẩm hai' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Hoàn thành 1' })).toBeVisible();
  await page.getByRole('button', { name: 'Tất cả 2' }).click();
  await expect(page.getByRole('heading', { name: 'Tác phẩm một' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tất cả 2' })).toBeVisible();

  await page.getByRole('combobox', { name: 'Thư viện tài khoản' }).selectOption(secondLibraryId);
  await expect(page.getByRole('heading', { name: 'Thư viện này chưa có tác phẩm' })).toBeVisible();
  await expect(page.getByText('“Bản nhập demo” chỉ được lưu trong demo')).toBeVisible();
  await expectNoAxeViolations(page);
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
  await expect(
    page
      .locator('.library-status-message.library-sync-error')
      .filter({ hasText: 'Máy chủ chưa tải được dữ liệu' }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tạo bộ sưu tập' })).toBeDisabled();
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Thử tải lại' }).click();
  await expect(page.getByText('Bộ sưu tập đã được tải từ tài khoản.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tạo bộ sưu tập' })).toBeEnabled();
  expect(collectionReads).toBe(2);
  await expectNoAxeViolations(page);
});

const acceptedAccount = {
  user: {
    id: 'user-1',
    displayName: 'Độc giả',
    contactEmail: 'reader@example.test',
    avatarUrl: null,
    status: 'ACTIVE',
  },
  identityProviders: ['google'],
  policy: {
    termsAcceptedVersion: 'terms-1',
    privacyNoticeAcknowledgedVersion: 'privacy-1',
    requiredAcceptanceComplete: true,
  },
  roles: [],
  features: {
    privateLibraryEnabled: false,
    importEnabled: false,
    readerEnabled: false,
    commerceEnabled: false,
    cultivationEnabled: false,
  },
};

async function installAcceptedAccountRoutes(page: Page): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      apiVersion: '4.2.1',
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
      },
    }),
  );
  await page.route('**/api/v1/auth/me', (route) => json(route, acceptedAccount));
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
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Terms' }),
  );
  await page.route('**/api/v1/auth/policies/privacy-notice/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/markdown', body: '# Privacy' }),
  );
  await page.route('**/api/v1/libraries**', (route) =>
    json(route, { items: [], nextCursor: null }),
  );
  await page.route('**/api/v1/works**', (route) => json(route, { items: [], nextCursor: null }));
  await page.route('**/api/v1/me/library/collections', (route) => json(route, []));
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, { token: 'session-library-csrf' }),
  );
}

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
