import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: 'account-reader-performance.spec.ts',
  fullyParallel: false,
  forbidOnly: true,
  workers: 1,
  reporter: [['list'], ['junit', { outputFile: 'reports/reader-performance-junit.xml' }]],
  outputDir: 'reports/reader-performance-results',
  timeout: 180_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: 'http://127.0.0.1:4310',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...devices['Desktop Chrome'],
  },
  webServer: {
    command: 'node e2e/performance/serve-dist.mjs',
    url: 'http://127.0.0.1:4310',
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
