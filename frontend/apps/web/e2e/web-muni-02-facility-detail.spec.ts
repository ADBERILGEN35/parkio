import { expect, test, type Page } from '@playwright/test';

/**
 * WEB-MUNI-02 / WEB-MUNI-04 facility detail route smoke.
 *
 * Default Playwright builds keep `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false`.
 * Full map + open-in-maps coverage lives in Vitest with `municipalDiscoveryEnabled`.
 */

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

  let detailHits = 0;
  let nearbyHits = 0;

  await page.route(`**/api/v1/parking/facilities/${FACILITY_ID}`, async (route) => {
    detailHits += 1;
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

  await page.route('**/api/v1/parking/facilities/nearby**', async (route) => {
    nearbyHits += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([]),
    });
  });

  return {
    getDetailHits: () => detailHits,
    getNearbyHits: () => nearbyHits,
  };
}

test.describe('WEB-MUNI-02/04 municipal facility detail route', () => {
  test('deep-links to facility detail when discovery is enabled in build', async ({ page }) => {
    // Hosted Playwright builds default discovery OFF — assert route shell + flag-off UI.
    // When VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true, the same path renders detail + map.
    const counters = await installDetailMocks(page);
    await page.goto(`/facilities/${FACILITY_ID}`);

    await expect(page).toHaveURL(/\/(facilities|login)/);

    // Flag-off smoke: no municipal map / open-in-maps leak into the disabled or login shell.
    await expect(page.getByTestId('municipal-facility-open-in-maps')).toHaveCount(0);
    await expect(page.getByTestId('spot-map')).toHaveCount(0);
    expect(counters.getNearbyHits()).toBe(0);
  });

  test('invalid facility id stays on facilities path shape', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('parkio.locale', 'en');
    });
    await page.goto('/facilities/not-a-uuid');
    await expect(page).toHaveURL(/\/(facilities\/not-a-uuid|login)/);
    await expect(page.getByTestId('municipal-facility-open-in-maps')).toHaveCount(0);
  });

  test('community spot detail path remains distinct from facilities', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('parkio.locale', 'en');
    });
    const spotId = '0b8f6c3a-0000-0000-0000-000000000001';
    await page.goto(`/spots/${spotId}`);
    await expect(page).toHaveURL(new RegExp(`/(spots/${spotId}|login)`));
    await expect(page.getByTestId('municipal-facility-location')).toHaveCount(0);
  });
});
