import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: /static-host-security\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['junit', { outputFile: 'reports/static-host-e2e-junit.xml' }]],
  outputDir: 'reports/static-host-e2e-results',
  use: {
    baseURL: process.env['STATIC_HOST_URL'] ?? 'http://127.0.0.1:18080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...devices['Desktop Chrome'],
  },
});
