import { expect, test, type Page } from '@playwright/test';

/**
 * WEB-MUNI-07 — URL state persistence smoke.
 *
 * Default Playwright builds keep municipal discovery OFF. This smoke proves that
 * municipal query params are ignored/canonicalized safely, `smartReturn=1`
 * survives, and no municipal UI leaks into the community-only build.
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

  await page.route('**/api/v1/notifications/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    });
  });

  await page.route('**/api/v1/users/me/smart-return', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        smartReturnEnabled: true,
        homeLatitude: 38.4237,
        homeLongitude: 27.1428,
        expectedReturnTimeLocal: '18:30',
        timezone: 'Europe/Istanbul',
        drivingToday: true,
      }),
    });
  });
}

test.describe('WEB-MUNI-07 URL persistence flag default', () => {
  test('municipal URL params do not leak municipal UI when the flag is off', async ({ page }) => {
    await installMapMocks(page);

    await page.goto(
      '/map?smartReturn=1&communityLayer=0&municipalLayer=0&municipalAvailability=available&municipalSources=IZUM',
    );

    await expect(page).toHaveURL(/\/(map|login)/);
    await expect(page.getByTestId('map-layer-visibility-controls')).toHaveCount(0);
    await expect(page.getByTestId('municipal-facility-results')).toHaveCount(0);
    await expect(page.getByTestId('municipal-facility-filters')).toHaveCount(0);
    await expect(page.getByText(/municipal facilit/i)).toHaveCount(0);
  });

  test('mobile width also keeps municipal URL state from leaking', async ({ page }) => {
    await installMapMocks(page);
    await page.setViewportSize({ width: 390, height: 844 });

    await page.goto('/map?municipalAvailability=available&municipalTypes=OFF_STREET');

    await expect(page).toHaveURL(/\/(map|login)/);
    await expect(page.getByText(/municipal facilit/i)).toHaveCount(0);
    await expect(page.getByTestId('map-sheet-show-results')).toHaveCount(0);
  });
});
