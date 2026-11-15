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
    '/sign-in?returnTo=%2Fapp%2Fworks%2F11111111-1111-4111-8111-111111111111',
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
