import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: /full-stack-smoke\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['junit', { outputFile: 'reports/full-stack-smoke-junit.xml' }]],
  outputDir: 'reports/full-stack-smoke-results',
  use: {
    baseURL: process.env['FULL_STACK_URL'] ?? 'http://127.0.0.1:18088',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...devices['Desktop Chrome'],
  },
});
