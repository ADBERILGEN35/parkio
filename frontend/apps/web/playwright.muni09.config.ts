import { defineConfig, devices } from '@playwright/test';

/**
 * WEB-MUNI-09 municipal discovery accessibility (`e2e/web-muni-09-accessibility.spec.ts`) on a
 * dedicated Vite dev server with municipal discovery on, never the flag-off default server.
 * The spec also checks the flag in the test process and fails without it, so run it with
 * VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true in the environment, as `pnpm e2e:muni09` does.
 */
const PORT = 5198;
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  testMatch: /web-muni-09-accessibility\.spec\.ts/,
  globalSetup: './playwright.warm-up.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'muni09-desktop',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      // The same 360 px phone as the default config's galaxy-360 project.
      name: 'muni09-360',
      use: {
        ...devices['Pixel 7'],
        viewport: { width: 360, height: 800 },
        deviceScaleFactor: 3,
        isMobile: true,
        hasTouch: true,
      },
    },
  ],
  webServer: {
    command: `pnpm exec vite --port ${PORT} --strictPort`,
    url: BASE_URL,
    timeout: 120_000,
    reuseExistingServer: false,
    env: {
      ...process.env,
      VITE_API_BASE_URL: `${BASE_URL}/api/v1`,
      VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'true',
    },
  },
});
