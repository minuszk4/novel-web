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

test('mobile setup keeps both demo consent rows above the sticky action', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/setup');
  await expect(page.locator('.prototype-notice')).toContainText('không có hiệu lực pháp lý');

  const layout = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('.consent-row')];
    const actions = document.querySelector('.mobile-form-actions');
    if (rows.length !== 2 || !actions) throw new Error('Setup consent layout is incomplete');
    return {
      viewportWidth: innerWidth,
      documentWidth: document.documentElement.scrollWidth,
      lastConsentBottom: rows[1].getBoundingClientRect().bottom,
      actionTop: actions.getBoundingClientRect().top,
    };
  });

  expect(layout.documentWidth).toBe(layout.viewportWidth);
  expect(layout.lastConsentBottom).toBeLessThanOrEqual(layout.actionTop);
  expect(layout.actionTop - layout.lastConsentBottom).toBeGreaterThanOrEqual(8);
  await expectNoAxeViolations(page);
});

test('mobile setup supports keyboard consent and continuation', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/app/setup');

  const checkboxes = page.getByRole('checkbox');
  const terms = checkboxes.nth(0);
  const privacy = checkboxes.nth(1);
  const continueButton = page.getByRole('button', { name: /Vào không gian đọc/ });
  await expect(continueButton).toBeDisabled();

  for (let tabCount = 0; tabCount < 20; tabCount += 1) {
    if (await terms.evaluate((element) => element === document.activeElement)) break;
    await page.keyboard.press('Tab');
  }
  await expect(terms).toBeFocused();
  expect(
    await terms.evaluate(
      (element) => getComputedStyle(element.closest('.consent-row')!).outlineStyle,
    ),
  ).toBe('solid');

  await page.keyboard.press('Space');
  await expect(terms).toBeChecked();
  await page.keyboard.press('Tab');
  await expect(privacy).toBeFocused();
  await page.keyboard.press('Space');
  await expect(privacy).toBeChecked();
  await expect(continueButton).toBeEnabled();

  await page.keyboard.press('Tab');
  await expect(continueButton).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/app\/home$/);
});
