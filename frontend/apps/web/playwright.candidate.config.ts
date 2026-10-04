import { defineConfig, devices } from '@playwright/test';

/**
 * CX-F11 exact-candidate web acceptance (U16).
 *
 * - `cxf11-acceptance` runs the scenarios in `acceptance/`. With no CXF11_WEB_URL it uses a Vite dev
 *   server built with the release bake flags (docker/web-hosted-beta.release-bake.env: public
 *   Explore and municipal discovery on), so it behaves like the candidate.
 * - With CXF11_WEB_URL set to a running web image, the existing e2e specs run against it too
 *   (`cxf11-existing-desktop`, `cxf11-existing-360`). The marketing and service-worker specs are
 *   excluded because they need their own servers.
 *
 * Every API call is mocked by the specs, and requests to other hosts are aborted.
 */
const PORT = 5197;
const CANDIDATE_URL = process.env.CXF11_WEB_URL;
const BASE_URL = CANDIDATE_URL ?? `http://localhost:${PORT}`;
const EXISTING_EXCLUDED = [/marketing-site\.spec\.ts/, /service-worker-upgrade\.spec\.ts/];

export default defineConfig({
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  // The first load on a cold Vite dev server can take a minute; a built image is fast.
  timeout: 150_000,
  expect: { timeout: 10_000 },
  use: { baseURL: BASE_URL, trace: 'retain-on-failure' },
  projects: [
    {
      name: 'cxf11-acceptance',
      testDir: './acceptance',
      testMatch: /\.cxf11\.ts$/,
      use: { ...devices['Desktop Chrome'] },
    },
    ...(CANDIDATE_URL
      ? [
          {
            name: 'cxf11-existing-desktop',
            testDir: './e2e',
            testMatch: /\.spec\.ts$/,
            testIgnore: EXISTING_EXCLUDED,
            use: { ...devices['Desktop Chrome'] },
          },
          {
            name: 'cxf11-existing-360',
            testDir: './e2e',
            testMatch: /\.spec\.ts$/,
            testIgnore: EXISTING_EXCLUDED,
            use: {
              ...devices['Pixel 7'],
              viewport: { width: 360, height: 800 },
              deviceScaleFactor: 3,
              isMobile: true,
              hasTouch: true,
            },
          },
        ]
      : []),
  ],
  webServer: CANDIDATE_URL
    ? undefined
    : {
        command: `pnpm exec vite --port ${PORT} --strictPort`,
        url: BASE_URL,
        timeout: 120_000,
        reuseExistingServer: !process.env.CI,
        env: {
          VITE_API_BASE_URL: `${BASE_URL}/api/v1`,
          VITE_APP_ENV: 'hosted-beta',
          // hosted-beta is production-like and refuses to start without a MapTiler key. A synthetic one
          // (not a placeholder pattern): tiles never load in these runs anyway.
          VITE_MAPTILER_KEY: 'cxf11SyntheticMapKeyNotReal00',
          VITE_PUBLIC_EXPLORE_ENABLED: 'true',
          VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'true',
          VITE_SMART_RETURN_ENABLED: 'true',
          VITE_SMART_PARKING_ASSISTANT_ENABLED: 'false',
        },
      },
});
