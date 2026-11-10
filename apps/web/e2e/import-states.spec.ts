import AxeBuilder from '@axe-core/playwright';
import { expect, test as base, type Page } from '@playwright/test';

const test = base.extend({
  pageErrors: [
    async ({ page }, use) => {
      const errors: string[] = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await use(errors);
      expect(errors).toEqual([]);
    },
    { auto: true },
  ],
});

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

const wcagTags = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];

async function openImportPage(page: Page, viewport: (typeof viewports)[number]): Promise<void> {
  await page.setViewportSize({ width: viewport.width, height: viewport.height });
  await page.goto('/app/setup');

  await page.locator('.consent-row').nth(0).click();
  await page.locator('.consent-row').nth(1).click();
  const continueButton = page.getByRole('button', { name: /Vào không gian đọc/ });
  await expect(continueButton).toBeEnabled();
  await continueButton.click();
  await expect(page).toHaveURL(/\/app\/home$/);

  await page
    .getByRole('banner')
    .getByRole('link', { name: /Thêm truyện/ })
    .click();
  await expect(page).toHaveURL(/\/app\/import$/);
  await expect(page.getByRole('heading', { name: 'Mang câu chuyện của bạn về đây' })).toBeVisible();
}

async function selectFile(page: Page, name = 'sample.txt'): Promise<void> {
  await page.locator('#work-file').setInputFiles({
    name,
    mimeType: name.toLowerCase().endsWith('.txt') ? 'text/plain' : 'application/pdf',
    buffer: Buffer.from('small local test fixture'),
  });
}

async function expectNoAxeViolations(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(wcagTags).analyze();
  const violations = results.violations.map(({ id, impact, help, nodes }) => ({
    id,
    impact,
    help,
    nodes: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })),
  }));

  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
}

for (const viewport of viewports) {
  test(`unsupported file error is announced and accessible at ${viewport.name}`, async ({
    page,
  }) => {
    await openImportPage(page, viewport);
    await selectFile(page, 'unsupported.pdf');

    await expect(page.getByRole('alert')).toContainText('Định dạng này chưa được hỗ trợ');
    await expect(page.getByRole('button', { name: /Xem lại thông tin/ })).toBeDisabled();
    await expectNoAxeViolations(page);
  });

  test(`selected file metadata and rights confirmation are accessible at ${viewport.name}`, async ({
    page,
  }) => {
    await openImportPage(page, viewport);
    await selectFile(page);

    const selectedFile = page.locator('.selected-file');
    await expect(selectedFile).toContainText('sample.txt');
    await expect(selectedFile.getByRole('img', { name: 'Đã chọn' })).toBeVisible();
    await expect(page.getByRole('checkbox')).toBeEnabled();
    await expect(page.getByRole('button', { name: /Xem lại thông tin/ })).toBeDisabled();
    await expectNoAxeViolations(page);

    await page.locator('.rights-check').click();
    await expect(page.getByRole('checkbox')).toBeChecked();
    await expect(page.getByRole('button', { name: /Xem lại thông tin/ })).toBeEnabled();
    await page.getByRole('button', { name: /Xem lại thông tin/ }).click();
    await expect(page).toHaveURL(/\/app\/import\/review$/);
    await expect(page.getByRole('heading', { name: 'Kiểm tra thông tin' })).toBeVisible();
    await expectNoAxeViolations(page);
  });
}

test('a long selected filename stays inside the mobile viewport', async ({ page }) => {
  const longName = `${'chuong-truyen-dai-'.repeat(12)}.txt`;
  await openImportPage(page, viewports[0]);
  await selectFile(page, longName);

  await expect(page.locator('.selected-file')).toContainText(longName);
  const dimensions = await page.evaluate(() => ({
    document: document.documentElement.scrollWidth,
    viewport: document.documentElement.clientWidth,
  }));
  expect(dimensions.document).toBeLessThanOrEqual(dimensions.viewport);
  await expectNoAxeViolations(page);
});
