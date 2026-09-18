import { expect, test } from '@playwright/test';

/**
 * WEB-MUNI-06 — dual-inventory empty chrome / sheet CTA smoke.
 * Default Playwright builds keep municipal discovery OFF; assert no municipal-aware
 * copy leak. Full municipal-on coverage is in Vitest with `municipalDiscoveryEnabled`.
 */
test.describe('WEB-MUNI-06 dual-inventory empty chrome flag default', () => {
  test('flag-off map empty chrome stays community-only', async ({ page }) => {
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
        body: JSON.stringify([{ id: 'should-not-render' }]),
      });
    });

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/map');
    await expect(page).toHaveURL(/\/(map|login)/);

    await expect(page.getByText(/municipal facilit/i)).toHaveCount(0);
    await expect(page.getByText(/No community spots nearby/i)).toHaveCount(0);
    await expect(page.getByText(/community spots and .* municipal/i)).toHaveCount(0);
    await expect(page.getByTestId('map-sheet-show-results')).toHaveCount(0);
  });

  test('desktop flag-off smoke has no municipal chrome leak', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('parkio.locale', 'en');
    });
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/map');
    await expect(page).toHaveURL(/\/(map|login)/);
    await expect(page.getByText(/municipal facilit/i)).toHaveCount(0);
  });
});
