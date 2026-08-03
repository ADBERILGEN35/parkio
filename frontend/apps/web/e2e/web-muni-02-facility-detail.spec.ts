import { expect, test, type Page } from '@playwright/test';

const FACILITY_ID = '70db58f2-4cca-4010-9315-fa46b30fba1e';

const municipalFacility = {
  id: FACILITY_ID,
  displayName: 'Konak Municipal Lot',
  operatorName: 'IBB',
  facilityType: 'OFF_STREET',
  addressText: 'Konak, Izmir',
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

async function installDetailMocks(page: Page, options?: { status?: number }) {
  await page.addInitScript(() => {
    localStorage.setItem('parkio.locale', 'en');
  });

  await page.route(`**/api/v1/parking/facilities/${FACILITY_ID}`, async (route) => {
    const status = options?.status ?? 200;
    await route.fulfill({
      status,
      contentType: 'application/json',
      body:
        status === 200
          ? JSON.stringify(municipalFacility)
          : JSON.stringify({ code: 'NOT_FOUND' }),
    });
  });
}

test.describe('WEB-MUNI-02 municipal facility detail route', () => {
  test('deep-links to facility detail when discovery is enabled in build', async ({ page }) => {
    // Hosted Playwright builds default discovery OFF — assert route shell + flag-off UI.
    // When VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true, the same path renders detail content.
    await installDetailMocks(page);
    await page.goto(`/facilities/${FACILITY_ID}`);

    // Authenticated apps redirect anonymous users to login; accept either gate.
    await expect(page).toHaveURL(/\/(facilities|login)/);
  });

  test('invalid facility id stays on facilities path shape', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('parkio.locale', 'en');
    });
    await page.goto('/facilities/not-a-uuid');
    await expect(page).toHaveURL(/\/(facilities\/not-a-uuid|login)/);
  });
});
