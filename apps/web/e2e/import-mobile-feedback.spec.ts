import { expect, test } from './support';

async function openImport(page: import('@playwright/test').Page): Promise<void> {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/setup');
  await page.locator('.consent-row').nth(0).click();
  await page.locator('.consent-row').nth(1).click();
  await page.getByRole('button', { name: /Vào không gian đọc/ }).click();
  await page
    .getByRole('banner')
    .getByRole('link', { name: /Thêm truyện/ })
    .click();
  await expect(page).toHaveURL(/\/app\/import$/);
}

test('mobile import error is visible above the fixed action bar', async ({ page }) => {
  await openImport(page);
  await page.locator('#work-file').setInputFiles({
    name: 'unsupported.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('local fixture'),
  });

  const error = page.getByRole('alert');
  await expect(error).toContainText('Định dạng này chưa được hỗ trợ');
  const errorBox = await error.boundingBox();
  const actionBox = await page.locator('.mobile-form-actions').boundingBox();
  expect(errorBox).not.toBeNull();
  expect(actionBox).not.toBeNull();
  expect(errorBox!.y + errorBox!.height).toBeLessThanOrEqual(actionBox!.y);
  await expect(page.getByRole('button', { name: /Xem lại thông tin/ })).toBeDisabled();
});

test('mobile selected filename stays visible above the fixed action bar', async ({ page }) => {
  await openImport(page);
  await page.locator('#work-file').setInputFiles({
    name: 'Quan Tro Anh Trang.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('local fixture'),
  });

  const selectedFile = page.locator('.selected-file');
  await expect(selectedFile).toContainText('Quan Tro Anh Trang.txt');
  await expect(page.locator('.upload-dropzone')).toBeHidden();
  const fileBox = await selectedFile.boundingBox();
  const rightsBox = await page.locator('.rights-check').boundingBox();
  const actionBox = await page.locator('.mobile-form-actions').boundingBox();
  expect(fileBox).not.toBeNull();
  expect(rightsBox).not.toBeNull();
  expect(actionBox).not.toBeNull();
  expect(fileBox!.y + fileBox!.height).toBeLessThanOrEqual(actionBox!.y);
  expect(rightsBox!.y + rightsBox!.height).toBeLessThanOrEqual(actionBox!.y);

  const replaceButton = page.getByRole('button', { name: 'Đổi tệp' });
  await expect(replaceButton).toBeVisible();
  const replaceBox = await replaceButton.boundingBox();
  expect(replaceBox).not.toBeNull();
  expect(replaceBox!.width).toBeGreaterThanOrEqual(40);
  expect(replaceBox!.height).toBeGreaterThanOrEqual(40);
  expect(replaceBox!.y + replaceBox!.height).toBeLessThanOrEqual(actionBox!.y);

  const chooserPromise = page.waitForEvent('filechooser');
  await replaceButton.click();
  const chooser = await chooserPromise;
  await chooser.setFiles({
    name: 'replacement.md',
    mimeType: 'text/markdown',
    buffer: Buffer.from('replacement fixture'),
  });
  await expect(selectedFile).toContainText('replacement.md');
  await expect(page.getByRole('checkbox')).not.toBeChecked();
});

test('mobile review keeps its primary action above the bottom navigation', async ({ page }) => {
  await openImport(page);
  await page.locator('#work-file').setInputFiles({
    name: 'Quan Tro Anh Trang.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('local fixture'),
  });
  await page.locator('.rights-check').click();
  await page.getByRole('button', { name: /Xem lại thông tin/ }).click();
  await expect(page).toHaveURL(/\/app\/import\/review$/);
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);

  const submit = page.getByRole('button', { name: /Thêm vào thư viện demo/ });
  await expect(submit).toBeVisible();
  const submitBox = await submit.boundingBox();
  const navBox = await page.locator('.mobile-nav').boundingBox();
  expect(submitBox).not.toBeNull();
  expect(navBox).not.toBeNull();
  expect(submitBox!.y + submitBox!.height).toBeLessThanOrEqual(navBox!.y);
});
