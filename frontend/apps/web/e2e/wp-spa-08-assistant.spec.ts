/**
 * WP-SPA-08 focused E2E: flag-gated assistant entry on /map.
 * Uses page fixtures; does not call live geocoding.
 */
import { test, expect } from '@playwright/test';

test.describe('WP-SPA-08 smart parking assistant', () => {
  test('flag off keeps assistant entry hidden', async ({ page }) => {
    await page.addInitScript(() => {
      (window as unknown as { __PARKIO_TEST_FLAGS__?: Record<string, boolean> }).__PARKIO_TEST_FLAGS__ =
        { smartParkingAssistant: false };
    });
    // MapPage reads Vite env at build time; this E2E asserts the default (flag off) build.
    await page.goto('/map');
    await expect(page.getByTestId('assistant-entry')).toHaveCount(0);
  });
});
