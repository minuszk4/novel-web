import { expect, expectNoAxeViolations, test } from './support';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

const rankLadder = [
  'Phàm nhân',
  ...Array.from({ length: 9 }, (_, index) => `Luyện Khí tầng ${index + 1}`),
  'Trúc Cơ',
  'Kim Đan',
  'Nguyên Anh',
  'Hóa Thần',
  'Luyện Hư',
  'Hợp Thể',
  'Đại Thừa',
  'Độ Kiếp',
];

for (const viewport of viewports) {
  test(`cultivation rank and opt-in states are clear at ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/app/cultivation');

    await expect(page.getByRole('heading', { name: 'Hành trình tu luyện' })).toBeVisible();
    await expect(page.getByText('Số liệu mẫu để minh họa giao diện.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Bật trong bản demo' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Luyện Khí tầng 6' })).toBeVisible();
    await expect(
      page.getByRole('progressbar', { name: 'Ví dụ tiến độ tới Luyện Khí tầng 7' }),
    ).toHaveAttribute('aria-valuenow', '20');
    for (const rank of rankLadder) {
      await expect(page.locator('.rank-ladder').getByText(rank, { exact: true })).toBeVisible();
    }
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Bật trong bản demo' }).click();
    await expect(
      page.getByRole('heading', { name: 'Hành trình đang bật trong demo' }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Tạm dừng tích lũy' })).toBeVisible();
    await expect(page.getByText(/Phiên demo không tự cộng XP/)).toBeVisible();
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Tạm dừng tích lũy' }).click();
    await expect(page.getByText(/Đang tạm dừng/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Tiếp tục' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Luyện Khí tầng 6' })).toBeVisible();
    await expectNoAxeViolations(page);

    await page.getByRole('button', { name: 'Tiếp tục' }).click();
    await expect(page.getByText(/Đang bật\. Phiên demo/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Tạm dừng tích lũy' })).toBeVisible();
    await expectNoAxeViolations(page);

    const dimensions = await page.evaluate(() => ({
      document: document.documentElement.scrollWidth,
      viewport: document.documentElement.clientWidth,
    }));
    expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  });
}
