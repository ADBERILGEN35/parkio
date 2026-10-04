import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for the mocked e2e specs that share the default Vite dev server.
 * Frontend CI runs them on desktop and 360 px (`pnpm e2e:regression`), plus the
 * acceptance projects below in their own steps.
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
const WP03_ACCEPTANCE = /wp03-routing\.spec\.ts/;
// CX-F04 identity-handoff acceptance: viewport-independent, run once in its own
// project so CI can target it without relying on project-name skips.
const OWNERSHIP_ACCEPTANCE = /pending-profile-ownership\.spec\.ts/;
// CL-F21 auth-link language acceptance: same arrangement, its own project for CI.
const LINK_LOCALE_ACCEPTANCE = /auth-link-locale\.spec\.ts/;
// Specs that need a server of their own, so they run from their own configs: marketing-site
// (playwright.marketing.config.ts), service-worker-upgrade (playwright.sw-upgrade.config.ts),
// y04a-product-analytics (playwright.y04a.config.ts) and web-muni-09-accessibility
// (playwright.muni09.config.ts, municipal discovery on).
const OWN_SERVER_SPECS = [
  /marketing-site\.spec\.ts/,
  /service-worker-upgrade\.spec\.ts/,
  /y04a-product-analytics\.spec\.ts/,
  /web-muni-09-accessibility\.spec\.ts/,
];
const VIEWPORT_IGNORE = [
  WP03_ACCEPTANCE,
  OWNERSHIP_ACCEPTANCE,
  LINK_LOCALE_ACCEPTANCE,
  ...OWN_SERVER_SPECS,
];

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  globalSetup: './playwright.warm-up.ts',
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
    {
      name: 'wp03-chromium',
      testMatch: WP03_ACCEPTANCE,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'ownership-chromium',
      testMatch: OWNERSHIP_ACCEPTANCE,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'link-locale-chromium',
      testMatch: LINK_LOCALE_ACCEPTANCE,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'chromium',
      testIgnore: VIEWPORT_IGNORE,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      // Smallest mainstream Android width (360×800 — Galaxy A-series / many budget
      // phones). The tightest layout we support: validates no horizontal overflow,
      // no clipped CTAs, no chip wrapping, and that the preview clears the sheet.
      name: 'galaxy-360',
      testIgnore: VIEWPORT_IGNORE,
      use: {
        ...devices['Pixel 7'],
        viewport: { width: 360, height: 800 },
        deviceScaleFactor: 3,
        isMobile: true,
        hasTouch: true,
      },
    },
    {
      name: 'iphone-14',
      testIgnore: VIEWPORT_IGNORE,
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
      testIgnore: VIEWPORT_IGNORE,
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
