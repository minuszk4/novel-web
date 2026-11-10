import { expect, test } from './support';

test.beforeEach(async ({ page }) => {
  await page.goto('/read/demo-story/chapter-1');
  await expect(
    page.getByRole('heading', { level: 1, name: 'Bức thư không có người gửi' }),
  ).toBeVisible();
});

test('Alt+Shift+T opens the table of contents and Escape restores focus', async ({ page }) => {
  const readerHeading = page.getByRole('heading', { level: 1, name: 'Bức thư không có người gửi' });
  await expect(readerHeading).toBeFocused();

  await page.keyboard.press('Alt+Shift+t');

  const dialog = page.getByRole('dialog', { name: 'Chương mẫu' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Đóng mục lục' })).toBeFocused();

  await page.keyboard.press('Escape');

  await expect(dialog).toBeHidden();
  await expect(readerHeading).toBeFocused();
});

test('chapter shortcuts navigate and move focus to the new chapter heading', async ({ page }) => {
  await page.keyboard.press('Alt+Shift+j');
  await expect(page).toHaveURL(/\/read\/demo-story\/chapter-2$/);
  const secondHeading = page.getByRole('heading', { level: 1, name: 'Căn phòng hướng đông' });
  await expect(secondHeading).toBeFocused();

  await page.keyboard.press('Alt+Shift+k');
  await expect(page).toHaveURL(/\/read\/demo-story\/chapter-1$/);
  await expect(
    page.getByRole('heading', { level: 1, name: 'Bức thư không có người gửi' }),
  ).toBeFocused();
});

test('settings dialog traps focus and returns it to its opener', async ({ page }) => {
  const opener = page.getByRole('button', { name: 'Cài đặt trình đọc' });
  await opener.click();

  const dialog = page.getByRole('dialog', { name: 'Cài đặt đọc' });
  const closeButton = dialog.getByRole('button', { name: 'Đóng cài đặt' });
  const lastButton = dialog.getByRole('button', { name: 'Đặt lại mặc định' });
  await expect(closeButton).toBeFocused();

  await page.keyboard.press('Shift+Tab');
  await expect(lastButton).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(closeButton).toBeFocused();

  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(opener).toBeFocused();
});

test('clicking the panel backdrop closes it and restores focus', async ({ page }) => {
  const opener = page.getByRole('button', { name: 'Mở mục lục' });
  await opener.click();

  const dialog = page.getByRole('dialog', { name: 'Chương mẫu' });
  await expect(dialog).toBeVisible();
  await page.locator('.reader-panel-backdrop').click({ position: { x: 400, y: 20 } });

  await expect(dialog).toBeHidden();
  await expect(opener).toBeFocused();
});

test('selecting a table-of-contents chapter closes the dialog and focuses its heading', async ({
  page,
}) => {
  await page.getByRole('button', { name: 'Mở mục lục' }).click();
  const dialog = page.getByRole('dialog', { name: 'Chương mẫu' });
  await dialog.getByRole('button', { name: /Căn phòng hướng đông/ }).click();

  await expect(page).toHaveURL(/\/read\/demo-story\/chapter-2$/);
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('heading', { level: 1, name: 'Căn phòng hướng đông' })).toBeFocused();
});

test('reader dialog fits a 360px viewport without horizontal page overflow', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.getByRole('button', { name: 'Cài đặt trình đọc' }).click();
  await expect(page.getByRole('dialog', { name: 'Cài đặt đọc' })).toBeVisible();

  const widths = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(widths.document).toBeLessThanOrEqual(widths.viewport);
});
