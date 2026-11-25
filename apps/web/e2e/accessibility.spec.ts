import { expect, expectNoAxeViolations, test } from './support';

const pages = [
  { name: 'landing', path: '/' },
  { name: 'sign-in', path: '/sign-in' },
  { name: 'setup', path: '/app/setup' },
  { name: 'home', path: '/app/home' },
  { name: 'library', path: '/app/library' },
  { name: 'search', path: '/app/search' },
  { name: 'import', path: '/app/import' },
  { name: 'import review', path: '/app/import/review' },
  { name: 'cultivation', path: '/app/cultivation' },
  { name: 'settings', path: '/app/settings' },
  { name: 'work detail', path: '/works/demo-story' },
  { name: 'reader', path: '/read/demo-story/chapter-1' },
] as const;

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  for (const route of pages) {
    test(`${route.name} has no axe violations at ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto(route.path);
      await expect(page.locator('h1').first()).toBeVisible();

      await expectNoAxeViolations(page);
    });
  }
}

for (const route of pages) {
  test(`${route.name} reflows without page overflow at 320 CSS px`, async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 800 });
    await page.goto(route.path);
    await expect(page.locator('h1').first()).toBeVisible();

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
      body: document.body.scrollWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
    expect(dimensions.body).toBeLessThanOrEqual(dimensions.viewport);
    await expectNoAxeViolations(page);
  });
}

for (const viewport of viewports) {
  for (const panel of [
    { name: 'table of contents', trigger: 'Mở mục lục', dialog: 'Chương mẫu' },
    { name: 'reader settings', trigger: 'Cài đặt trình đọc', dialog: 'Cài đặt đọc' },
  ]) {
    test(`${panel.name} dialog has no axe violations at ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto('/read/demo-story/chapter-1');
      await page.getByRole('button', { name: panel.trigger }).click();
      await expect(page.getByRole('dialog', { name: panel.dialog })).toBeVisible();

      await expectNoAxeViolations(page);
    });
  }
}

test('offline and unverified VNPAY states explain the restriction in text', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.route('**/api/v1/system/status', (route) => route.abort());
  await page.route('**/api/v1/credit-packs', (route) => route.abort());

  await page.goto('/app/credits');

  const connectionStatus = page.locator('.demo-banner');
  await expect(connectionStatus).toHaveAttribute('role', 'status');
  await expect(connectionStatus).toContainText('API local chưa phản hồi.');
  await expect(connectionStatus).toContainText('VNPAY Test chưa được bật.');
  await expect(connectionStatus.getByRole('button', { name: 'Thử lại' })).toBeVisible();

  const shopStatus = page.locator('.credit-shop-state-card');
  await expect(shopStatus).toHaveAttribute('role', 'alert');
  await expect(
    shopStatus.getByRole('heading', { name: 'Chưa kiểm tra được môi trường' }),
  ).toBeVisible();
  await expect(shopStatus).toContainText('danh sách gói và nút nạp đang bị khóa');
  const retryButton = shopStatus.getByRole('button', { name: 'Kiểm tra lại' });
  await expect(retryButton).toBeVisible();
  await retryButton.focus();
  const retryAndNavBounds = await page.evaluate(() => {
    const button = document
      .querySelector('.credit-shop-state-card button')!
      .getBoundingClientRect();
    const nav = document.querySelector('.mobile-nav')!.getBoundingClientRect();
    return { buttonBottom: button.bottom, navTop: nav.top };
  });
  expect(retryAndNavBounds.buttonBottom).toBeLessThanOrEqual(retryAndNavBounds.navTop);
  await expectNoAxeViolations(page);
});
