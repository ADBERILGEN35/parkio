import { expect, test } from '@playwright/test';

/**
 * WEB-MUNI-09 — flag-off municipal accessibility leak guard.
 *
 * Runs on the default Playwright Vite (municipal discovery OFF). Do not set
 * VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true for this file.
 */

test.describe('WEB-MUNI-09 flag-off accessibility safety', () => {
  test.beforeEach(() => {
    expect(
      process.env.VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED ?? 'false',
      'Flag-off safety must run against a build without VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true',
    ).not.toBe('true');
  });

  test('map shell has no municipal layer or facility accessibility surface', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('parkio.locale', 'en');
    });

    await page.route(/openstreetmap\.org|api\.maptiler\.com|fonts\.(googleapis|gstatic)\.com/, (route) =>
      route.abort(),
    );
    await page.route('**/api/v1/parking/spots/nearby**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });
    await page.route('**/api/v1/parking/facilities/nearby**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });

    await page.goto('/map');
    await expect(page).toHaveURL(/\/(map|login)/);

    await expect(page.getByTestId('map-layer-visibility-controls')).toHaveCount(0);
    await expect(page.getByTestId('map-layer-municipal')).toHaveCount(0);
    await expect(page.getByTestId('municipal-facility-marker')).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: /Municipal parking facility:/i }),
    ).toHaveCount(0);
    await expect(
      page.getByRole('region', { name: /Municipal parking facilities/i }),
    ).toHaveCount(0);
  });
});
