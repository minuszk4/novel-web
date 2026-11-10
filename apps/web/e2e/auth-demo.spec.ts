import { expect, expectNoAxeViolations, test } from './support';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`sign-in identifies Google as unavailable and opens demo setup at ${viewport.name}`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/sign-in');

    const providerStatus = page.getByRole('status');
    await expect(providerStatus).toContainText('Google Sign-in chưa kết nối');
    await expect(providerStatus).toContainText(
      'Chưa có dịch vụ xác thực hoặc tài khoản nào được tạo',
    );
    await expect(page.getByRole('button', { name: /Google/i })).toHaveCount(0);
    await expectNoAxeViolations(page);

    await page.getByRole('link', { name: 'Tiếp tục với bản demo' }).click();
    await expect(page).toHaveURL(/\/app\/setup$/);
    await expect(page.getByRole('heading', { name: 'Một vài điều cần biết' })).toBeVisible();
  });
}
