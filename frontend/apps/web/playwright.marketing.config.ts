import { defineConfig, devices } from '@playwright/test';

const PORT = 5194;
const BASE_URL = `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  testMatch: /marketing-site\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    ...devices['Desktop Chrome'],
    baseURL: BASE_URL,
    trace: 'on-first-retry',
  },
  webServer: {
    command: `node ../../../scripts/serve-marketing-site.mjs --port ${PORT}`,
    url: BASE_URL,
    timeout: 30_000,
    reuseExistingServer: !process.env.CI,
  },
});
