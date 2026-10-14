import { defineConfig, devices } from '@playwright/test';

/**
 * N-11 map style/tile unavailable alerts (`e2e/map-unavailable.spec.ts`) on a dedicated Vite
 * server with a synthetic MapTiler key and public explore on. The default e2e server has no key
 * (inline OSM style) and explore off, so those failures cannot be mocked there.
 */
const PORT = 5199;
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  testMatch: /map-unavailable\.spec\.ts/,
  globalSetup: './playwright.warm-up.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  timeout: 45_000,
  expect: { timeout: 12_000 },
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'map-unavailable-chromium',
      use: { ...devices['Desktop Chrome'] },
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
      VITE_MAPTILER_KEY: 'n11SyntheticMapKeyNotReal00',
      VITE_PUBLIC_EXPLORE_ENABLED: 'true',
    },
  },
});
