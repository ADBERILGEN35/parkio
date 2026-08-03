import { expect, test, type Page } from '@playwright/test';

const FACILITY_ID = '70db58f2-4cca-4010-9315-fa46b30fba1e';

const municipalFacility = {
  id: FACILITY_ID,
  displayName: 'Konak Municipal Lot',
  operatorName: null,
  facilityType: 'OFF_STREET',
  addressText: 'Konak, İzmir',
  latitude: 38.4237,
  longitude: 27.1428,
  capacityTotal: 120,
  availableSpaces: null,
  freshness: 'UNAVAILABLE',
  attribution: 'OpenStreetMap contributors',
  sourceLabel: 'OSM',
  lastUpdatedAt: '2026-08-03T12:00:00Z',
  contributingSourceKeys: ['osm-geofabrik-turkey'],
  selectedFieldProvenanceSummary: { displayName: 'osm-geofabrik-turkey' },
  registryConfidenceOrReviewStatus: null,
  availabilitySource: null,
  availabilityFreshness: 'UNAVAILABLE',
  availabilityObservationTimestamp: null,
};

async function installMunicipalMocks(page: Page) {
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
      body: JSON.stringify([municipalFacility]),
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

/**
 * WEB-MUNI-01 — municipal discovery is build-flag gated. This smoke only asserts
 * the community map path still works when the flag is off (default Playwright build).
 * Full municipal UI coverage lives in Vitest with `municipalDiscoveryEnabled`.
 */
test.describe('WEB-MUNI-01 municipal discovery flag default', () => {
  test('map search remains usable without municipal UI when flag is off', async ({ page }) => {
    await installMunicipalMocks(page);
    await page.goto('/map?lat=38.4237&lng=27.1428');

    // Default builds leave VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED unset/false.
    await expect(page.getByTestId('municipal-facility-results')).toHaveCount(0);
    await expect(page.getByRole('complementary').or(page.getByLabel('Search results'))).toBeVisible({
      timeout: 15_000,
    });
  });
});
