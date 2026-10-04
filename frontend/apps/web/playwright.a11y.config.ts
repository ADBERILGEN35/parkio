import { defineConfig, devices } from '@playwright/test';

/**
 * CL-F30 browser-measured accessibility: axe-core (WCAG 2.0/2.1/2.2 A and AA) and a keyboard walk,
 * in Turkish and English, for the web app (`a11y-web`) and the marketing site (`a11y-marketing`).
 *
 * By default the web app runs on the Vite dev server and the marketing site on
 * `scripts/serve-marketing-site.mjs`. To measure a built candidate instead, start it and pass its
 * origin in A11Y_WEB_URL and/or A11Y_MARKETING_URL; the matching local server is then not started.
 * Per-page JSON results are written to test-results/a11y/.
 */
const WEB_PORT = 5195;
const MARKETING_PORT = 5196;
const WEB_URL = process.env.A11Y_WEB_URL ?? `http://localhost:${WEB_PORT}`;
const MARKETING_URL = process.env.A11Y_MARKETING_URL ?? `http://127.0.0.1:${MARKETING_PORT}`;

export default defineConfig({
  testDir: './a11y',
  testMatch: /\.a11y\.ts$/,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  use: { ...devices['Desktop Chrome'], trace: 'retain-on-failure' },
  // Playwright clears the output folder of each project it runs. One folder per project, so the
  // marketing run that follows the web run in `e2e:a11y` keeps the web results in test-results/a11y/
  // for the CI artifact (#253 review B1).
  projects: [
    {
      name: 'a11y-web',
      testMatch: /web\.a11y\.ts$/,
      outputDir: 'test-results/a11y-web-output',
      use: { baseURL: WEB_URL },
    },
    {
      name: 'a11y-marketing',
      testMatch: /marketing\.a11y\.ts$/,
      outputDir: 'test-results/a11y-marketing-output',
      use: { baseURL: MARKETING_URL },
    },
  ],
  webServer: [
    ...(process.env.A11Y_WEB_URL
      ? []
      : [
          {
            command: `pnpm exec vite --port ${WEB_PORT} --strictPort`,
            url: WEB_URL,
            timeout: 120_000,
            reuseExistingServer: !process.env.CI,
            // Public explore on, as in the release images, so /explore is measured with its map and
            // facilities instead of its flag-off "unavailable" card.
            env: { VITE_API_BASE_URL: `${WEB_URL}/api/v1`, VITE_PUBLIC_EXPLORE_ENABLED: 'true' },
          },
        ]),
    ...(process.env.A11Y_MARKETING_URL
      ? []
      : [
          {
            command: `node ../../../scripts/serve-marketing-site.mjs --port ${MARKETING_PORT}`,
            url: MARKETING_URL,
            timeout: 30_000,
            reuseExistingServer: !process.env.CI,
          },
        ]),
  ],
});
