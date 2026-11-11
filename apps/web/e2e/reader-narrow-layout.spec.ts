import { expect, test } from './support';

test('demo Reader controls reflow at a narrow 200%-equivalent viewport', async ({ page }) => {
  await page.setViewportSize({ width: 180, height: 400 });
  await page.goto('/read/demo-story/chapter-1');

  const tableOfContents = page.getByRole('button', { name: 'Mở mục lục' });
  const settings = page.getByRole('button', { name: 'Cài đặt trình đọc' });
  const progressLabel = page.locator('.reader-progress-label');
  const modeSwitch = page.locator('.reader-mode-switch');
  const bookmark = page.getByRole('button', { name: 'Đánh dấu trang' });
  const bookmarkControl = page.locator('.bookmark-button');
  const nextChapter = page.getByRole('link', { name: /Chương tiếp/ });

  await expect(tableOfContents).toBeVisible();
  await expect(settings).toBeVisible();
  await expect(bookmark).toBeVisible();
  await expect(nextChapter).toBeVisible();

  const bounds = await page.evaluate(() => {
    const rect = (selector: string) => {
      const element = document.querySelector(selector);
      if (!element) throw new Error(`Missing element: ${selector}`);
      const box = element.getBoundingClientRect();
      return {
        left: box.left,
        right: box.right,
        top: box.top,
        bottom: box.bottom,
        width: box.width,
        height: box.height,
      };
    };
    return {
      viewportWidth: innerWidth,
      documentWidth: document.documentElement.scrollWidth,
      tableOfContents: rect('.reader-tools .reader-tool:first-child'),
      settings: rect('.reader-tools .reader-tool:last-child'),
      modeSwitch: rect('.reader-mode-switch'),
      progress: rect('.reader-progress-label'),
      bookmark: rect('.bookmark-button'),
      nextChapter: rect('.reader-bottom-actions .button'),
    };
  });

  expect(bounds.documentWidth).toBe(bounds.viewportWidth);
  for (const control of [
    bounds.tableOfContents,
    bounds.settings,
    bounds.bookmark,
    bounds.nextChapter,
  ]) {
    expect(control.left).toBeGreaterThanOrEqual(0);
    expect(control.right).toBeLessThanOrEqual(bounds.viewportWidth);
    expect(control.width).toBeGreaterThanOrEqual(44);
    expect(control.height).toBeGreaterThanOrEqual(44);
  }
  expect(bounds.progress.top).toBeGreaterThanOrEqual(bounds.modeSwitch.bottom);

  await bookmark.click();
  await expect(bookmarkControl).toHaveAttribute('aria-pressed', 'true');
  await expect(
    page.getByRole('button', { name: 'Đã lưu dấu trang, bỏ dấu trang chương' }),
  ).toBeVisible();
});

test('Reader settings keep mobile touch controls at least 44 by 44 CSS pixels', async ({
  page,
}) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/read/demo-story/chapter-1');
  await page.getByRole('button', { name: 'Cài đặt trình đọc' }).click();

  const controls = [
    page.getByRole('button', { name: 'Đóng cài đặt' }),
    page.getByRole('button', { name: 'A−' }),
    page.getByRole('button', { name: 'A+' }),
    page.getByRole('button', { name: 'Sáng' }),
    page.getByRole('button', { name: 'Sepia' }),
    page.getByRole('button', { name: 'Tối' }),
    page.getByRole('button', { name: 'Đặt lại mặc định' }),
  ];

  for (const control of controls) {
    const bounds = await control.boundingBox();
    expect(bounds?.width ?? 0).toBeGreaterThanOrEqual(44);
    expect(bounds?.height ?? 0).toBeGreaterThanOrEqual(44);
  }
});

test('empty home exposes the sample Reader without scrolling on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/home');

  const sampleReader = page.getByRole('link', { name: 'Đọc thử tác phẩm mẫu' });
  const mobileNavigation = page.getByRole('navigation', { name: 'Điều hướng di động' });
  await expect(sampleReader).toBeVisible();
  const sampleBounds = await sampleReader.boundingBox();
  const navigationBounds = await mobileNavigation.boundingBox();
  expect(sampleBounds).not.toBeNull();
  expect(navigationBounds).not.toBeNull();
  expect(sampleBounds!.y + sampleBounds!.height).toBeLessThan(navigationBounds!.y);

  await sampleReader.click();
  await expect(page).toHaveURL(/\/works\/demo-story$/);
});
