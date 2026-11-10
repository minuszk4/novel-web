import { expect, expectNoAxeViolations, test } from './support';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`private library empty and populated states work at ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
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
    const allTab = page.getByRole('tab', { name: /Tất cả/ });
    await expect(allTab).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('tab', { name: /Đang đọc/ })).toBeDisabled();
    await expect(page.getByRole('tab', { name: /Đọc sau/ })).toBeDisabled();

    const work = page.locator('.library-work-row');
    await expect(work.getByRole('heading', { name: 'Quán Trọ Ánh Trăng' })).toBeVisible();
    await expect(work).toContainText('Nguyệt Kiều');
    await expect(work).toContainText('quyen-truyen-rieng.txt');
    await expect(work.locator('.private-tag')).toHaveText('RIÊNG TƯ');
    await expect(work).toContainText('chưa có chương để đọc');
    await expectNoAxeViolations(page);

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  });
}
