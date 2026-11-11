import { expect, expectNoAxeViolations, test } from './support';
import type { Page } from '@playwright/test';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

async function addPrivateWork(page: Page, viewport: (typeof viewports)[number]): Promise<void> {
  await page.setViewportSize({ width: viewport.width, height: viewport.height });
  await page.goto('/app/setup');
  await page.locator('.consent-row').nth(0).click();
  await page.locator('.consent-row').nth(1).click();
  await page.getByRole('button', { name: 'Vào không gian đọc' }).click();
  await expect(page).toHaveURL(/\/app\/home$/);

  await page
    .getByRole('banner')
    .getByRole('link', { name: /Thêm truyện/ })
    .click();
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
  await expect(page).toHaveURL(/\/app\/home$/);
}

for (const viewport of viewports) {
  test(`private search result and no-result recovery work at ${viewport.name}`, async ({
    page,
  }) => {
    await addPrivateWork(page, viewport);
    await page.getByRole('link', { name: 'Tìm kiếm', exact: true }).click();
    await expect(page).toHaveURL(/\/app\/search$/);

    const search = page.getByRole('searchbox', {
      name: 'Tìm trong thư viện theo tên tác phẩm hoặc tác giả',
    });
    const resultAnnouncement = page.locator('.search-result-announcement');
    await expect(search).toBeVisible();
    await expect(resultAnnouncement).toBeEmpty();
    await expect(resultAnnouncement).toHaveAttribute('role', 'status');
    await expect(resultAnnouncement).toHaveAttribute('aria-live', 'polite');
    await expect(resultAnnouncement).toHaveAttribute('aria-atomic', 'true');
    await expect(page.getByText('1 kết quả', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Quán Trọ Ánh Trăng' })).toBeVisible();
    await expect(page.getByText('RIÊNG TƯ', { exact: true })).toBeVisible();
    await expectNoAxeViolations(page);

    await search.fill('Nguyệt');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: 'Quán Trọ Ánh Trăng' })).toBeVisible();
    await expect(page.getByText('1 kết quả', { exact: true })).toBeVisible();
    await expect(resultAnnouncement).toHaveText('Tìm thấy 1 kết quả cho “nguyệt”.');
    await expectNoAxeViolations(page);

    await search.fill('không tồn tại 123');
    await expect(page.getByText('0 kết quả')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Không tìm thấy kết quả' })).toBeVisible();
    await expect(page.getByText(/Không có kết quả cho “không tồn tại 123”/)).toBeVisible();
    await expect(resultAnnouncement).toHaveText('Không tìm thấy kết quả cho “không tồn tại 123”.');
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Xóa tìm kiếm' }).click();
    await expect(search).toHaveValue('');
    await expect(search).toBeFocused();
    await expect(resultAnnouncement).toHaveText('Có 1 kết quả trong thư viện riêng tư.');
    await expect(page.getByRole('heading', { name: 'Quán Trọ Ánh Trăng' })).toBeVisible();
    await expectNoAxeViolations(page);
  });
}
