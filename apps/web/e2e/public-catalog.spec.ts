import { expect, expectNoAxeViolations, test } from './support';
import { join } from 'node:path';
import type { Page, Route } from '@playwright/test';

const works = [
  {
    id: '11111111-1111-4111-8111-111111111111',
    title: 'Long Vân Truyện',
    author: 'Minh An',
    sourceLanguage: 'vi',
    readyChapterCount: 8,
  },
  {
    id: '22222222-2222-4222-8222-222222222222',
    title: 'Sơn Hà Ký',
    author: 'Nguyệt Kiều',
    sourceLanguage: 'vi',
    readyChapterCount: 13,
  },
];

const enabledStatus = {
  apiVersion: '7.2.0',
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
    publicCatalogEnabled: true,
    translationFakePreviewEnabled: false,
  },
};

async function json(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

async function installCatalog(page: Page, enabled: boolean): Promise<void> {
  await page.route('**/api/v1/system/status', (route) =>
    json(route, {
      ...enabledStatus,
      features: { ...enabledStatus.features, publicCatalogEnabled: enabled },
    }),
  );
}

test('does not request or display public works when the server feature flag is off', async ({
  page,
}) => {
  let catalogRequests = 0;
  await installCatalog(page, false);
  await page.route('**/api/v1/catalog/works**', async (route) => {
    catalogRequests += 1;
    await json(route, { items: works, nextCursor: null });
  });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/');

  await expect(
    page.getByRole('heading', { name: 'Có những thế giới chỉ mở ra khi ta đọc chậm lại.' }),
  ).toBeVisible();
  await expect(page.locator('.public-catalog')).toHaveCount(0);
  expect(catalogRequests).toBe(0);
  await expectNoAxeViolations(page);
});

test('searches the enabled public metadata catalog and hands reading off through sign-in', async ({
  page,
}) => {
  const catalogRequests: URL[] = [];
  await installCatalog(page, true);
  await page.route('**/api/v1/catalog/works**', async (route) => {
    const requestUrl = new URL(route.request().url());
    catalogRequests.push(requestUrl);
    const query = requestUrl.searchParams.get('q');
    if (query === 'dragon') {
      await json(route, { items: [works[1]], nextCursor: null });
      return;
    }
    if (requestUrl.searchParams.has('cursor')) {
      await json(route, { items: [works[1]], nextCursor: null });
      return;
    }
    await json(route, { items: [works[0]], nextCursor: 'signed-page-2' });
  });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'Truyện công khai' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Long Vân Truyện' })).toBeVisible();
  await expect(page.getByText('Minh An')).toBeVisible();
  await expect(page.getByText('8', { exact: true })).toBeVisible();
  await expect(page.getByText('chapter body')).toHaveCount(0);
  await expect(page.locator('.public-catalog-read-link')).toHaveAttribute(
    'href',
    '/works/11111111-1111-4111-8111-111111111111',
  );
  await expectNoAxeViolations(page);

  await page.getByRole('searchbox', { name: 'Tên truyện hoặc tác giả' }).fill('Dragon');
  await page.getByLabel('Ngôn ngữ nguồn').fill('vi!');
  await page.getByRole('button', { name: 'Tìm truyện' }).click();
  await expect(page.getByRole('alert')).toContainText('Kiểm tra lại từ khóa và mã ngôn ngữ');
  expect(catalogRequests).toHaveLength(1);

  await page.getByLabel('Ngôn ngữ nguồn').fill('VI');
  await page.getByRole('button', { name: 'Tìm truyện' }).click();
  await expect(page).toHaveURL(/\?q=dragon&language=vi$/);
  await expect(page.getByRole('heading', { name: 'Sơn Hà Ký' })).toBeVisible();
  expect(catalogRequests.at(-1)?.searchParams.get('q')).toBe('dragon');
  expect(catalogRequests.at(-1)?.searchParams.get('language')).toBe('vi');

  await page.getByRole('button', { name: 'Xóa bộ lọc' }).click();
  await expect(page).toHaveURL('/');
  await expect(page.getByRole('heading', { name: 'Long Vân Truyện' })).toBeVisible();

  await page.getByRole('button', { name: 'Tải thêm truyện' }).click();
  await expect(page.getByRole('heading', { name: 'Sơn Hà Ký' })).toBeVisible();
  expect(catalogRequests.at(-1)?.searchParams.get('cursor')).toBe('signed-page-2');
});

test('opens public metadata detail and signs in before entering the Reader', async ({ page }) => {
  const workId = works[0].id;
  const requests: string[] = [];
  await installCatalog(page, true);
  await page.route(
    (url) => url.pathname === '/api/v1/catalog/works/' + workId,
    async (route) => {
      requests.push(new URL(route.request().url()).pathname);
      await json(route, { ...works[0], readyChapterCount: 21 });
    },
  );
  await page.route(
    (url) => url.pathname === '/api/v1/catalog/works/' + workId + '/chapters',
    async (route) => {
      const requestUrl = new URL(route.request().url());
      requests.push(requestUrl.pathname);
      if (requestUrl.searchParams.get('afterOrdinal') === '20') {
        await json(route, {
          workId,
          items: [{ ordinal: 21, title: 'Chương hai mươi mốt' }],
          nextAfterOrdinal: null,
        });
        return;
      }
      await json(route, {
        workId,
        items: Array.from({ length: 20 }, (_, index) => ({
          ordinal: index + 1,
          title: index === 0 ? 'Chương một' : 'Chương ' + (index + 1),
        })),
        nextAfterOrdinal: 20,
      });
    },
  );
  await page.route(
    (url) => url.pathname === '/api/v1/works/' + workId + '/subscription',
    async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/problem+json',
        headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
        body: JSON.stringify({ code: 'UNAUTHENTICATED' }),
      });
    },
  );
  await page.route('**/api/v1/catalog/works**', async (route) => {
    if (new URL(route.request().url()).pathname !== '/api/v1/catalog/works') {
      await route.fallback();
      return;
    }
    await json(route, { items: [works[0]], nextCursor: null });
  });

  for (const viewport of [
    { width: 360, height: 800 },
    { width: 180, height: 400 },
  ]) {
    await page.setViewportSize(viewport);
    await page.goto('/works/' + workId);
    await expect(page.getByRole('heading', { name: 'Long Vân Truyện' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '21 chương' })).toBeVisible();
    await expect(page.getByText('Chương một')).toBeVisible();
    await expect(page.getByText('chapter body')).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Đăng nhập để xem quyền đọc' })).toHaveAttribute(
      'href',
      '/sign-in?returnTo=%2Fapp%2Fworks%2F' + workId,
    );
    await expect(
      page.getByRole('link', { name: 'Đăng nhập để theo dõi cập nhật' }),
    ).toHaveAttribute('href', '/sign-in?returnTo=%2Fworks%2F' + workId);
    await expect(page.getByRole('link', { name: /Đăng nhập để đọc/ }).first()).toHaveAttribute(
      'href',
      '/sign-in?returnTo=%2Fapp%2Fworks%2F' + workId,
    );
    await expectNoAxeViolations(page);
    const evidenceDirectory = process.env['PUBLIC_WORK_DETAIL_EVIDENCE_DIR'];
    if (evidenceDirectory) {
      await page.screenshot({
        path: join(
          evidenceDirectory,
          'public-work-detail-' + viewport.width + 'x' + viewport.height + '.png',
        ),
        fullPage: true,
      });
    }

    if (viewport.width === 360) {
      await page.getByRole('button', { name: 'Tải thêm chương' }).click();
      await expect(page.getByText('Chương hai mươi mốt')).toBeVisible();
      const widths = await page.evaluate(() => ({
        body: document.body.scrollWidth,
        viewport: document.documentElement.clientWidth,
        clipped: [
          ...document.querySelectorAll<HTMLElement>(
            '.public-work-chapters .detail-chapter-row, .public-work-detail-page .work-detail-copy > .button',
          ),
        ]
          .map((element) => ({
            label: element.innerText,
            content: element.scrollWidth,
            width: element.clientWidth,
          }))
          .filter((element) => element.content > element.width + 1),
      }));
      expect(widths.body).toBeLessThanOrEqual(widths.viewport);
      expect(widths.clipped).toEqual([]);
    }
  }
  expect(requests.filter((path) => path.endsWith('/chapters'))).toHaveLength(3);
  expect(requests.filter((path) => path === '/api/v1/catalog/works/' + workId)).toHaveLength(2);
  expect(requests.some((path) => path.endsWith('/content'))).toBe(false);
});

test('follows, mutes, re-enables and unfollows a public work with fresh CSRF and ETags', async ({
  page,
}) => {
  const workId = works[0].id;
  const subscriptionPath = '/api/v1/works/' + workId + '/subscription';
  const etags: string[] = [];
  let state = { following: false, notificationsEnabled: false };
  let version = 0;
  await installCatalog(page, true);
  await page.route('**/api/v1/auth/csrf', (route) =>
    json(route, { token: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }),
  );
  await page.route('**/api/v1/catalog/works/' + workId, (route) => json(route, works[0]));
  await page.route('**/api/v1/catalog/works/' + workId + '/chapters**', (route) =>
    json(route, { workId, items: [{ ordinal: 1, title: 'Chương một' }], nextAfterOrdinal: null }),
  );
  await page.route(
    (url) => url.pathname === subscriptionPath,
    async (route) => {
      const request = route.request();
      if (request.method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: {
            'Cache-Control': 'no-store',
            Pragma: 'no-cache',
            ETag: version === 0 ? '"subscription-absent"' : '"subscription-' + version + '"',
          },
          body: JSON.stringify(state),
        });
        return;
      }
      etags.push(request.headers()['if-match'] ?? '');
      if (request.method() === 'PUT') {
        const body = request.postDataJSON() as { notificationsEnabled: boolean };
        state = { following: true, notificationsEnabled: body.notificationsEnabled };
        version += 1;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: {
            'Cache-Control': 'no-store',
            Pragma: 'no-cache',
            ETag: '"subscription-' + version + '"',
          },
          body: JSON.stringify(state),
        });
        return;
      }
      state = { following: false, notificationsEnabled: false };
      version += 1;
      await route.fulfill({
        status: 204,
        headers: {
          'Cache-Control': 'no-store',
          Pragma: 'no-cache',
          ETag: '"subscription-' + version + '"',
        },
      });
    },
  );

  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/works/' + workId);
  await page.getByRole('button', { name: 'Theo dõi cập nhật chương' }).click();
  await expect(page.getByRole('status')).toContainText('Đã lưu lựa chọn theo dõi truyện này.');
  await page.getByRole('button', { name: 'Tạm tắt thông báo' }).click();
  await expect(page.getByRole('status')).toContainText(
    'Đã lưu lựa chọn tạm tắt thông báo cho truyện này.',
  );
  await page.getByRole('button', { name: 'Bật lại lựa chọn thông báo' }).click();
  await expect(page.getByRole('status')).toContainText('Đã lưu lựa chọn theo dõi truyện này.');
  await page.getByRole('button', { name: 'Bỏ theo dõi' }).click();
  await expect(page.getByRole('status')).toContainText('Đã bỏ theo dõi truyện này.');
  await expect(page.getByRole('button', { name: 'Theo dõi cập nhật chương' })).toBeVisible();
  expect(etags).toEqual([
    '"subscription-absent"',
    '"subscription-1"',
    '"subscription-2"',
    '"subscription-3"',
  ]);
  await expectNoAxeViolations(page);
});

test('does not request public work data when the catalog feature is disabled on a deep link', async ({
  page,
}) => {
  let workRequests = 0;
  await installCatalog(page, false);
  await page.route('**/api/v1/catalog/works/**', async (route) => {
    workRequests += 1;
    await json(route, {});
  });
  await page.goto('/works/' + works[0].id);
  await expect(page.getByRole('heading', { name: 'Danh mục chưa khả dụng' })).toBeVisible();
  expect(workRequests).toBe(0);
  await expectNoAxeViolations(page);
});

test('recovers from a failed page request without hiding search', async ({ page }) => {
  let catalogRequests = 0;
  await installCatalog(page, true);
  await page.route('**/api/v1/catalog/works**', async (route) => {
    catalogRequests += 1;
    if (catalogRequests === 1) {
      await json(route, { code: 'CATALOG_TEMPORARILY_UNAVAILABLE' }, 503);
      return;
    }
    await json(route, { items: [works[0]], nextCursor: null });
  });
  await page.goto('/');

  await expect(page.getByRole('alert')).toContainText('Danh mục đang tạm thời không khả dụng');
  await expect(page.getByRole('searchbox', { name: 'Tên truyện hoặc tác giả' })).toBeVisible();
  await page.getByRole('button', { name: 'Thử tải lại' }).click();
  await expect(page.getByRole('heading', { name: 'Long Vân Truyện' })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
});

test('shows Retry-After without auto-retry and preserves the search for explicit recovery', async ({
  page,
}) => {
  const catalogRequests: URL[] = [];
  await installCatalog(page, true);
  await page.route('**/api/v1/catalog/works**', async (route) => {
    const requestUrl = new URL(route.request().url());
    catalogRequests.push(requestUrl);
    if (catalogRequests.length === 2) {
      await route.fulfill({
        status: 429,
        headers: { 'Retry-After': '8', 'Cache-Control': 'no-store' },
        contentType: 'application/problem+json',
        body: JSON.stringify({ code: 'PUBLIC_CATALOG_RATE_LIMITED' }),
      });
      return;
    }
    await json(route, { items: [works[0]], nextCursor: null });
  });
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Long Vân Truyện' })).toBeVisible();

  const query = page.getByRole('searchbox', { name: 'Tên truyện hoặc tác giả' });
  await query.fill('kiem');
  await page.getByRole('button', { name: 'Tìm truyện' }).click();

  await expect(page.getByRole('alert')).toContainText('Hãy thử lại sau 8 giây');
  await expect(query).toHaveValue('kiem');
  await expect(page.locator('.public-catalog-announcement')).toBeEmpty();
  expect(catalogRequests).toHaveLength(2);
  const evidenceDirectory = process.env['PUBLIC_CATALOG_EVIDENCE_DIR'];
  if (evidenceDirectory) {
    await page.screenshot({
      path: join(evidenceDirectory, 'catalog-429-retry-after-mobile-360x800.png'),
      fullPage: true,
    });
  }
  await page.waitForTimeout(1_200);
  expect(catalogRequests).toHaveLength(2);
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Thử tải lại' }).click();
  await expect(page.getByRole('heading', { name: 'Long Vân Truyện' })).toBeVisible();
  expect(catalogRequests).toHaveLength(3);
  expect(catalogRequests[2].searchParams.get('q')).toBe('kiem');
});

for (const viewport of [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
  { name: 'effective 200% zoom proxy 180px', width: 180, height: 400 },
]) {
  test(`public catalog reflows and has no axe violations at ${viewport.name}`, async ({ page }) => {
    await installCatalog(page, true);
    await page.route('**/api/v1/catalog/works**', (route) =>
      json(route, { items: works, nextCursor: null }),
    );
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Truyện công khai' })).toBeVisible();

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
      body: document.body.scrollWidth,
      overflowing: [...document.querySelectorAll<HTMLElement>('*')]
        .map((element) => ({
          tag: element.tagName,
          className: String(element.className),
          text: element.innerText?.slice(0, 50),
          right: Math.ceil(element.getBoundingClientRect().right),
          width: Math.ceil(element.getBoundingClientRect().width),
        }))
        .filter((element) => element.right > document.documentElement.clientWidth)
        .slice(0, 8),
    }));
    expect(dimensions.document, JSON.stringify(dimensions.overflowing)).toBeLessThanOrEqual(
      dimensions.viewport,
    );
    expect(dimensions.body).toBeLessThanOrEqual(dimensions.viewport);
    await expectNoAxeViolations(page);
    const evidenceDirectory = process.env['PUBLIC_CATALOG_EVIDENCE_DIR'];
    if (evidenceDirectory) {
      await page.screenshot({
        path: join(evidenceDirectory, `catalog-${viewport.width}x${viewport.height}.png`),
        fullPage: true,
      });
    }
  });
}
