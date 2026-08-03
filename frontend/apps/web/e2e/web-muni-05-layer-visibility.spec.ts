import { expect, test } from '@playwright/test';

/**
 * WEB-MUNI-05 — dual-inventory layer visibility smoke.
 * Default Playwright builds keep municipal discovery OFF.
 * Full toggle coverage lives in Vitest with `municipalDiscoveryEnabled`.
 */
test.describe('WEB-MUNI-05 map layer visibility flag default', () => {
  test('map remains usable without municipal layer controls when flag is off', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem('parkio.locale', 'en');
    });

    await page.route('**/api/v1/parking/spots/nearby**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      });
    });
    await page.route('**/api/v1/parking/facilities/nearby**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      });
    });

    await page.goto('/map');
    await expect(page).toHaveURL(/\/(map|login)/);
    await expect(page.getByTestId('map-layer-visibility-controls')).toHaveCount(0);
    await expect(page.getByTestId('map-layer-municipal')).toHaveCount(0);
    await expect(page.getByTestId('map-layers-both-hidden')).toHaveCount(0);
  });
});
