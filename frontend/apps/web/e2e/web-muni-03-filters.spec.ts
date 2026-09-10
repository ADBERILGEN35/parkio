import { expect, test, type Page } from '@playwright/test';

/**
 * WEB-MUNI-03 — municipal filter smoke.
 *
 * Default Playwright builds leave `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` off.
 * Full filter interaction coverage lives in Vitest (`municipalDiscoveryEnabled`).
 * This smoke asserts the community map path stays usable and municipal filter
 * UI does not leak when the flag is off.
 */
async function installMapMocks(page: Page) {
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

  await page.route('**/api/v1/parking/sessions/active', async (route) => {
    await route.fulfill({ status: 204 });
  });

  await page.route('**/api/v1/notifications/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    });
  });
}

test.describe('WEB-MUNI-03 municipal filters flag default', () => {
  test('map remains usable without municipal filter UI when flag is off', async ({ page }) => {
    await installMapMocks(page);
    await page.goto('/map?lat=38.4237&lng=27.1428');

    await expect(page.getByTestId('municipal-facility-results')).toHaveCount(0);
    await expect(page.getByTestId('municipal-facility-filters')).toHaveCount(0);
    await expect(page.getByRole('complementary').or(page.getByLabel('Search results'))).toBeVisible({
      timeout: 15_000,
    });
  });
});
