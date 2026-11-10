import { expect, expectNoAxeViolations, test } from './support';

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
  { name: 'tablet 768px', width: 768, height: 1024 },
  { name: 'desktop 1280px', width: 1280, height: 900 },
] as const;

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

for (const viewport of viewports) {
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
