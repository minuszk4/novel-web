import { expect, expectNoAxeViolations, test } from './support';
import type { Page } from '@playwright/test';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

const themes = [
  { name: 'Sepia', button: 'Sepia', className: 'reader-sepia' },
  { name: 'dark', button: 'Tối', className: 'reader-dark' },
] as const;

async function openReaderSettings(page: Page, viewport: (typeof viewports)[number]) {
  await page.setViewportSize({ width: viewport.width, height: viewport.height });
  await page.goto('/read/demo-story/chapter-1');
  await page.getByRole('button', { name: 'Cài đặt trình đọc' }).click();
  await expect(page.getByRole('dialog', { name: 'Cài đặt đọc' })).toBeVisible();
}

for (const viewport of viewports) {
  for (const theme of themes) {
    test(`Reader ${theme.name} theme is accessible at ${viewport.name}`, async ({ page }) => {
      await openReaderSettings(page, viewport);
      await page.getByRole('button', { name: theme.button }).click();
      await expect(page.locator('.reader-app')).toHaveClass(new RegExp(theme.className));
      await expectNoAxeViolations(page);
    });
  }

  test(`Reader font size limits stay accessible and fit ${viewport.name}`, async ({ page }) => {
    await openReaderSettings(page, viewport);
    const dialog = page.getByRole('dialog', { name: 'Cài đặt đọc' });
    const increase = dialog.getByRole('button', { name: 'A+' });
    const decrease = dialog.getByRole('button', { name: 'A−' });

    for (let step = 0; step < 8; step += 1) await increase.click();
    await expect(page.locator('.reader-article')).toHaveCSS('font-size', '28px');
    await expect(increase).toBeDisabled();
    await expectNoAxeViolations(page);

    for (let step = 0; step < 12; step += 1) await decrease.click();
    await expect(page.locator('.reader-article')).toHaveCSS('font-size', '16px');
    await expect(decrease).toBeDisabled();
    await expectNoAxeViolations(page);

    const widths = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(widths.document).toBeLessThanOrEqual(widths.viewport);
  });
}
