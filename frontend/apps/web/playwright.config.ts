import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for the single mocked smoke flow (see `e2e/smoke.spec.ts`).
 *
 * The app is served by the Vite dev server; all backend traffic is mocked at the
 * network layer inside the test (`page.route`), so no real services are hit.
 * `VITE_API_BASE_URL` is pointed at the dev-server origin so API calls stay
 * same-origin (no CORS preflight) and are easy to intercept with `**\/api/v1/**`.
 *
 * This is intentionally NOT wired into `pnpm test` — run it explicitly with
 * `pnpm e2e` (requires `pnpm exec playwright install chromium` once).
 */
const PORT = 5193;
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: 'list',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    {
      name: 'iphone-14',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 390, height: 844 },
        deviceScaleFactor: 3,
        isMobile: true,
        hasTouch: true,
      },
    },
    {
      name: 'pixel-8',
      use: {
        ...devices['Pixel 7'],
        viewport: { width: 412, height: 915 },
        deviceScaleFactor: 2.625,
      },
    },
  ],
  webServer: {
    command: `pnpm exec vite --port ${PORT} --strictPort`,
    url: BASE_URL,
    timeout: 120_000,
    reuseExistingServer: !process.env.CI,
    env: {
      VITE_API_BASE_URL: `${BASE_URL}/api/v1`,
    },
  },
});
