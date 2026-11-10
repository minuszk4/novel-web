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

const pages = [
  { name: 'landing', path: '/' },
  { name: 'sign-in', path: '/sign-in' },
  { name: 'setup', path: '/app/setup' },
  { name: 'home', path: '/app/home' },
  { name: 'library', path: '/app/library' },
  { name: 'search', path: '/app/search' },
  { name: 'import', path: '/app/import' },
  { name: 'import review', path: '/app/import/review' },
  { name: 'cultivation', path: '/app/cultivation' },
  { name: 'settings', path: '/app/settings' },
  { name: 'work detail', path: '/works/demo-story' },
  { name: 'reader', path: '/read/demo-story/chapter-1' },
] as const;

const viewports = [
  { name: 'mobile 360px', width: 360, height: 800 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

const wcagTags = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];

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
  for (const route of pages) {
    test(`${route.name} has no axe violations at ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto(route.path);
      await expect(page.locator('h1').first()).toBeVisible();

      await expectNoAxeViolations(page);
    });
  }
}

for (const viewport of [viewports[0], viewports[1]]) {
  for (const panel of [
    { name: 'table of contents', trigger: 'Mở mục lục', dialog: 'Chương mẫu' },
    { name: 'reader settings', trigger: 'Cài đặt trình đọc', dialog: 'Cài đặt đọc' },
  ]) {
    test(`${panel.name} dialog has no axe violations at ${viewport.name}`, async ({ page }) => {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto('/read/demo-story/chapter-1');
      await page.getByRole('button', { name: panel.trigger }).click();
      await expect(page.getByRole('dialog', { name: panel.dialog })).toBeVisible();

      await expectNoAxeViolations(page);
    });
  }
}
