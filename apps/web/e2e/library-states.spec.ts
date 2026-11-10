import { expect, expectNoAxeViolations, test } from './support';

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
    const collection = page.getByRole('button', { name: /Đọc cuối tuần/ });
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
    await expect(page.getByRole('button', { name: /Đọc cuối tuần/ })).toHaveAttribute('aria-pressed', 'true');
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
