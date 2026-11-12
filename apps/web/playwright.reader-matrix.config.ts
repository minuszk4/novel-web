import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testMatch: 'account-reader.spec.ts',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 2,
  reporter: [['list'], ['junit', { outputFile: 'reports/reader-browser-matrix-junit.xml' }]],
  outputDir: 'reports/reader-browser-matrix-results',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: 'http://127.0.0.1:4310',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    {
      name: 'firefox-smoke',
      grep: /account Reader preserves its route through (?:sign-in|policy setup) recovery|uses the saved scroll ratio|keeps an explicitly requested unavailable chapter|real mouse text selection|keyboard-style Selection API range|discards a cached text selection after chapter navigation/,
      use: { ...devices['Desktop Firefox'] },
    },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: {
    command: 'node e2e/performance/serve-dist.mjs',
    url: 'http://127.0.0.1:4310',
    reuseExistingServer: false,
    timeout: 30_000,
  },
});
