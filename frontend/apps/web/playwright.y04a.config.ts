import { defineConfig, devices } from '@playwright/test';

/**
 * Y04A actual-app analytics acceptance.
 * Test-only sink host + municipal flag. Never reuse these env vars in release.
 */
const PORT = 5197;
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/y04a-product-analytics.spec.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  timeout: 90_000,
  expect: { timeout: 20_000 },
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'y04a-chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: `pnpm exec vite --port ${PORT} --strictPort --host 127.0.0.1`,
    url: BASE_URL,
    timeout: 180_000,
    reuseExistingServer: false,
    env: {
      ...process.env,
      VITE_API_BASE_URL: `${BASE_URL}/api/v1`,
      VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'true',
      VITE_PRODUCT_ANALYTICS_VENDOR_ENABLED: 'true',
      VITE_PRODUCT_ANALYTICS_ALLOW_TEST_SINK: 'true',
      VITE_POSTHOG_KEY: 'phc_y04aTestOnlyKeyNotReal',
      VITE_POSTHOG_HOST: 'https://parkio-y04a-sink.test',
    },
  },
});
