import { expect, expectNoAxeViolations, test } from './support';
import type { Page } from '@playwright/test';

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

for (const viewport of viewports) {
  test(`required policy consent gates onboarding at ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto('/app/setup');

    const checkboxes = page.getByRole('checkbox');
    const continueButton = page.getByRole('button', { name: /Vào không gian đọc/ });
    await expect(continueButton).toBeDisabled();
    await expectNoAxeViolations(page);

    await page.locator('.consent-row').nth(0).click();
    await expect(checkboxes.nth(0)).toBeChecked();
    await expect(continueButton).toBeDisabled();
    await expectNoAxeViolations(page);

    await page.locator('.consent-row').nth(1).click();
    await expect(checkboxes.nth(1)).toBeChecked();
    await expect(continueButton).toBeEnabled();
    await expect
      .poll(() => continueButton.evaluate((element) => getComputedStyle(element).backgroundColor))
      .toBe('rgb(64, 86, 181)');
    await expectNoAxeViolations(page);

    await continueButton.click();
    await expect(page).toHaveURL(/\/app\/home$/);
  });
}
