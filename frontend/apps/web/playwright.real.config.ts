import { defineConfig, devices } from '@playwright/test';

const enabled = process.env.PARKIO_REAL_E2E === 'true';
const baseURL = process.env.PARKIO_REAL_BASE_URL ?? 'http://localhost:5173';
const apiBaseURL = process.env.PARKIO_REAL_API_BASE_URL ?? 'http://localhost:8080/api/v1';
const startWebServer = enabled && process.env.PARKIO_REAL_E2E_START_WEB === 'true';
const port = Number(process.env.PARKIO_REAL_E2E_PORT ?? '5173');

export default defineConfig({
  testDir: './e2e-real',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report/real-e2e', open: 'never' }],
  ],
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    extraHTTPHeaders: {
      'X-Parkio-Real-E2E': 'true',
    },
  },
  projects: [
    {
      name: 'real-e2e',
      testMatch: /.*\.real\.spec\.ts/,
      use: {
        ...devices['Desktop Chrome'],
      },
    },
  ],
  webServer: startWebServer
    ? {
        command: `pnpm exec vite --host localhost --port ${port} --strictPort`,
        url: baseURL,
        timeout: 120_000,
        reuseExistingServer: true,
        env: {
          VITE_API_BASE_URL: apiBaseURL,
        },
      }
    : undefined,
});
