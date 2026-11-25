import AxeBuilder from '@axe-core/playwright';
import { expect, test as base, type Page } from '@playwright/test';

type BrowserFixtures = {
  pageErrors: string[];
};

export { expect };

export const test = base.extend<BrowserFixtures>({
  pageErrors: [
    async ({ page }, use) => {
      const errors: string[] = [];
      page.on('pageerror', (error) => errors.push(error.message));
      // Existing authoring journeys run with collaboration disabled by default.
      // Match the API's explicit disabled response so owner pages can load while
      // feature-specific tests register their own route for this endpoint.
      await page.route('**/api/v1/authoring/collaborations', (route) =>
        route.fulfill({
          status: 503,
          contentType: 'application/problem+json',
          headers: { 'Cache-Control': 'no-store', Pragma: 'no-cache' },
          body: JSON.stringify({ code: 'MANUSCRIPT_COLLABORATION_UNAVAILABLE' }),
        }),
      );
      await use(errors);
      expect(errors).toEqual([]);
    },
    { auto: true },
  ],
});

const wcagTags = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice'];

export async function expectNoAxeViolations(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(wcagTags).analyze();
  const violations = results.violations.map(({ id, impact, help, nodes }) => ({
    id,
    impact,
    help,
    nodes: nodes.map(({ target, failureSummary }) => ({ target, failureSummary })),
  }));

  expect(violations, JSON.stringify(violations, null, 2)).toEqual([]);
}
