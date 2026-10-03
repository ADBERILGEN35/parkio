import { defineConfig, devices } from '@playwright/test';

/**
 * CL-F39.3 service-worker upgrade acceptance (`e2e/service-worker-upgrade.spec.ts`). The spec
 * serves two synthetic releases from its own local server, so no web server is configured.
 */
export default defineConfig({
  testDir: './e2e',
  testMatch: /service-worker-upgrade\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: 'list',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: {
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'sw-upgrade-chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
