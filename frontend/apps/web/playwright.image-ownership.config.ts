import { defineConfig, devices } from '@playwright/test';

/**
 * Replacement nginx-image ownership acceptance.
 * Mocks backend APIs; does not contact production. CSP remains enforced.
 */
const PORT = process.env.PARKIO_WEB_IMAGE_PORT || '18097';
const BASE_URL = `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  testMatch: /pending-profile-ownership\.spec\.ts/,
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: process.env.PARKIO_OWNERSHIP_JSON || 'ownership-image-report.json' }]],
  timeout: 45_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: BASE_URL,
    trace: 'off',
    bypassCSP: false,
  },
  projects: [
    {
      name: 'ownership-chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
