import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * WEB-MUNI-09 — municipal discovery accessibility smoke (flag-on only).
 *
 * Server startup (must be a dedicated municipal-enabled Vite, not the flag-off
 * WEB-MUNI-01–08 server):
 *
 *   $env:VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED='true'
 *   $env:VITE_API_BASE_URL='http://localhost:5193/api/v1'
 *   corepack pnpm exec vite --port 5193 --strictPort
 *
 * Then:
 *   $env:VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED='true'
 *   corepack pnpm exec playwright test e2e/web-muni-09-accessibility.spec.ts
 *
 * Tests fail (do not skip) when the municipal build flag is missing.
 */

const FACILITY_ID = '70db58f2-4cca-4010-9315-fa46b30fba1e';
const PASSWORD = 'StrongParkio123';
const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';

const user = {
  id: USER_ID,
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

const municipalFacility = {
  id: FACILITY_ID,
  externalId: 'fac-konak',
  displayName: 'Konak Municipal Lot',
  addressText: 'Konak',
  sourceLabel: 'IZUM',
  latitude: 38.42,
  longitude: 27.14,
  facilityType: 'OFF_STREET',
  availableSpaces: 5,
  occupiedSpaces: 15,
  totalSpaces: 20,
  capacityTotal: 20,
  freshness: 'LIVE',
  availabilityFreshness: 'LIVE',
  operatorName: 'IZUM',
  lastUpdatedAt: '2026-06-11T09:00:00Z',
  provenance: null,
  attribution: 'IZUM',
  contributingSourceKeys: ['izum'],
  selectedFieldProvenanceSummary: null,
  registryConfidenceOrReviewStatus: null,
  availabilitySource: null,
  availabilityObservationTimestamp: null,
};

function authResponse() {
  return {
    accessToken: 'access-user',
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2999-01-01T00:00:00Z',
    refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
    user,
  };
}

function assertMunicipalBuildEnabled() {
  expect(
    process.env.VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED,
    'WEB-MUNI-09 requires a dedicated Vite with VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true (do not reuse the flag-off WEB-MUNI-01–08 server)',
  ).toBe('true');
}

async function installMocks(page: Page) {
  let authenticated = false;

  await page.addInitScript(() => {
    localStorage.setItem('parkio.locale', 'en');
  });

  // Tile/font failures must not block DOM accessibility assertions.
  await page.route(/openstreetmap\.org|api\.maptiler\.com|fonts\.(googleapis|gstatic)\.com/, (route) =>
    route.abort(),
  );

  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api\/v1/, '');
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });

    if (method === 'POST' && path === '/auth/login') {
      authenticated = true;
      return json(authResponse());
    }
    if (method === 'POST' && path === '/auth/refresh-token') {
      return authenticated
        ? json(authResponse())
        : json({ code: 'UNAUTHORIZED', message: 'Unauthorized', traceId: 'trace-refresh' }, 401);
    }
    if (method === 'GET' && path === '/auth/me') {
      return authenticated
        ? json(user)
        : json({ code: 'UNAUTHORIZED', message: 'Unauthorized', traceId: 'trace-anon' }, 401);
    }
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/users/me/vehicle') {
      return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    }
    if (method === 'GET' && path === '/users/me') {
      return json({
        id: USER_ID,
        authUserId: USER_ID,
        email: user.email,
        displayName: 'Test Driver',
        phoneNumber: null,
        city: 'Istanbul',
        status: 'ACTIVE',
        createdAt: '2026-01-01T09:00:00Z',
      });
    }
    if (method === 'GET' && path === '/users/me/stats') {
      return json({ trustScore: 72, trustBand: 'HIGH_TRUST', totalPoints: 10, currentLevel: 1 });
    }
    if (method === 'GET' && path === '/users/me/preferences') {
      return json({ preferredRadiusMeters: 1500, notificationsEnabled: true, preferredLocale: null });
    }
    if (method === 'GET' && path === '/users/me/smart-return') {
      return json({
        smartReturnEnabled: true,
        homeLatitude: 38.4237,
        homeLongitude: 27.1428,
        expectedReturnTimeLocal: '18:30',
        timezone: 'Europe/Istanbul',
        drivingToday: true,
      });
    }
    if (method === 'GET' && path === '/parking/sessions/active') {
      return route.fulfill({ status: 204, body: '' });
    }
    if (method === 'GET' && path === '/parking/sessions/lifecycle-config') {
      return json({ sessionEnabled: true, allowManualStart: true });
    }
    if (method === 'GET' && path === '/geocoding/search') return json([]);
    if (method === 'GET' && path === '/parking/spots/nearby') {
      return json([
        {
          id: 'spot-1',
          mediaId: 'media-1',
          latitude: 38.428,
          longitude: 27.148,
          addressText: 'Stub Address 7',
          description: null,
          manualLocationEdited: false,
          suitableVehicleTypes: ['SEDAN'],
          parkingContext: 'STREET_PARKING',
          legalStatus: 'LEGAL',
          violationReasons: [],
          status: 'ACTIVE',
          expiresAt: '2026-06-11T12:00:00Z',
          createdAt: '2026-06-11T09:00:00Z',
          updatedAt: '2026-06-11T09:00:00Z',
        },
      ]);
    }
    if (method === 'GET' && path === `/parking/facilities/${FACILITY_ID}`) {
      return json(municipalFacility);
    }
    if (method === 'GET' && path === '/parking/facilities/nearby') {
      return json([municipalFacility]);
    }

    return json({});
  });
}

async function login(page: Page) {
  await page.locator('input[autocomplete="email"]').fill(user.email);
  await page.locator('input[autocomplete="current-password"]').fill(PASSWORD);
  await page.getByRole('button', { name: /Giriş yap|Sign in/i }).click();
  await expect(page).toHaveURL(/\/map$/);
}

/** Drive nearby search like smoke.spec.ts so RHF receives filled coords (no geolocation). */
async function searchNearby(page: Page) {
  const mobile = (page.viewportSize()?.width ?? 1024) < 768;
  if (mobile) {
    await page
      .getByRole('button', {
        name: /Filters and search options|Filtreler ve arama seçenekleri/i,
      })
      .click();
  } else {
    await page.getByText(/Advanced coordinates|Gelişmiş koordinatlar/i).click();
  }

  await page.getByLabel(/Latitude|Enlem/i).fill('38.42');
  await page.getByLabel(/Longitude|Boylam/i).fill('27.14');
  await page.getByRole('button', { name: /Search nearby|Yakındakileri ara/i }).click();

  // Deterministic municipal fixture must surface — proves flag-on build + fixtures.
  await expect(page.getByTestId('map-layer-visibility-controls')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('Konak Municipal Lot')).toBeVisible();
}

async function ensureResultsReachable(page: Page) {
  const mobile = (page.viewportSize()?.width ?? 1024) < 768;
  if (!mobile) return;

  const collapsed = page.getByRole('button', { name: /Search results, collapsed/i });
  if (await collapsed.isVisible()) {
    await collapsed.click();
  }
  await expect(page.getByRole('button', { name: /Search results, (half open|expanded)/i })).toBeVisible();
}

async function activateMunicipalMarker(page: Page) {
  const municipalMarker = page.getByRole('button', {
    name: /Municipal parking facility: Konak Municipal Lot/i,
  });
  await municipalMarker.focus();
  await municipalMarker.press('Enter');
  return municipalMarker;
}

async function collapseSheet(page: Page) {
  const handle = page.getByRole('button', { name: /Search results,/i });
  await handle.focus();
  await handle.press('End');
  await expect(page.getByRole('button', { name: /Search results, collapsed/i })).toBeVisible();
}

test.describe('WEB-MUNI-09 accessibility hardening', () => {
  test.beforeEach(() => {
    assertMunicipalBuildEnabled();
  });

  test('map region, instructions, markers, and decorative center stay accessible', async ({
    page,
  }) => {
    await installMocks(page);
    await page.goto('/map');
    await login(page);
    await searchNearby(page);
    await ensureResultsReachable(page);

    const mapRegion = page.getByRole('region', { name: /Interactive parking discovery map/i });
    await expect(mapRegion).toBeVisible();
    await expect(mapRegion).toHaveAccessibleDescription(/Use Tab to move to controls and markers/i);

    // Decorative center pin stays out of the accessibility tree (no named control for coords).
    await expect(page.getByRole('button', { name: /38\.42|27\.14/ })).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: /Municipal parking facility: Konak Municipal Lot/i }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: /Community parking spot near Stub Address 7/i }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: /Municipal parking: Konak Municipal Lot/i }),
    ).toBeVisible();
  });

  test('marker selection announces once and surfaces the matching result', async ({ page }) => {
    await installMocks(page);
    await page.goto('/map');
    await login(page);
    await searchNearby(page);
    await ensureResultsReachable(page);

    const municipalMarker = await activateMunicipalMarker(page);

    await expect(municipalMarker).toHaveAttribute('aria-pressed', 'true');
    await expect(
      page.getByRole('status').filter({ hasText: /Selected municipal facility: Konak Municipal Lot/i }),
    ).toHaveCount(1);

    // Mobile map selection collapses the sheet and shows the preview; reopen for list proof.
    const mobile = (page.viewportSize()?.width ?? 1024) < 768;
    if (mobile) {
      await expect(page.getByTestId('municipal-facility-view-details')).toBeVisible();
      await page.getByRole('button', { name: /Search results, collapsed/i }).click();
      await expect(
        page.getByRole('button', { name: /Search results, half open/i }),
      ).toBeVisible();
    }

    const result = page.getByRole('button', {
      name: /Municipal parking: Konak Municipal Lot/i,
    });
    await expect(result).toBeVisible();
    await expect(result).toBeInViewport();
    await expect(result).toHaveAttribute('aria-pressed', 'true');

    // Re-activating the same marker must not duplicate the live-region node.
    await municipalMarker.focus();
    await municipalMarker.press('Enter');
    await expect(
      page.getByRole('status').filter({ hasText: /Selected municipal facility: Konak Municipal Lot/i }),
    ).toHaveCount(1);
  });

  test('layer toggles are keyboard operable and clear municipal selection', async ({ page }) => {
    await installMocks(page);
    await page.goto('/map');
    await login(page);
    await searchNearby(page);
    await ensureResultsReachable(page);

    await activateMunicipalMarker(page);
    await expect(
      page.getByRole('button', { name: /Municipal parking facility: Konak Municipal Lot/i }),
    ).toHaveAttribute('aria-pressed', 'true');

    // Mobile selection collapses the sheet; layer controls live in sheet content.
    await ensureResultsReachable(page);

    const municipalToggle = page.getByTestId('map-layer-municipal');
    await expect(municipalToggle).toHaveAttribute('aria-pressed', 'true');
    await municipalToggle.focus();
    await municipalToggle.press('Enter');

    await expect(municipalToggle).toBeFocused();
    await expect(municipalToggle).toHaveAttribute('aria-pressed', 'false');
    await expect(
      page.getByRole('button', { name: /Municipal parking facility: Konak Municipal Lot/i }),
    ).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: /Municipal parking: Konak Municipal Lot/i }),
    ).toHaveCount(0);
    await expect(
      page.getByRole('status').filter({ hasText: /Selected municipal facility: Konak Municipal Lot/i }),
    ).toHaveCount(0);

    // Both-hidden remains understandable.
    const communityToggle = page.getByTestId('map-layer-community');
    await communityToggle.focus();
    await communityToggle.press('Enter');
    await expect(communityToggle).toBeFocused();
    await expect(communityToggle).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByTestId('map-layers-both-hidden')).toBeVisible();
    await expect(
      page.getByText(/No map layers are currently visible|Turn on Community spots/i).first(),
    ).toBeVisible();
  });

  test('municipal filter keyboard flow reaches filtered-empty and clear', async ({ page }) => {
    await installMocks(page);
    await page.goto('/map');
    await login(page);
    await searchNearby(page);
    await ensureResultsReachable(page);

    const unavailable = page.getByRole('button', { name: /^Unavailable$/i });
    await unavailable.focus();
    await unavailable.press('Enter');
    await expect(unavailable).toBeFocused();
    await expect(unavailable).toHaveAttribute('aria-pressed', 'true');

    await expect(page.getByText(/No facilities match these filters/i)).toBeVisible();
    await expect(
      page.getByRole('button', { name: /Municipal parking: Konak Municipal Lot/i }),
    ).toHaveCount(0);

    const clear = page.getByRole('button', { name: /^Clear$/i }).first();
    await clear.focus();
    await clear.press('Enter');
    await expect(
      page.getByRole('button', { name: /Municipal parking: Konak Municipal Lot/i }),
    ).toBeVisible();
  });

  test('mobile sheet collapse hides content and restores focus to the handle', async ({
    page,
  }, testInfo) => {
    // Layout contract: sheet semantics are mobile-only; force a mobile viewport on every project.
    testInfo.annotations.push({ type: 'layout', description: 'mobile-sheet' });
    await page.setViewportSize({ width: 390, height: 844 });

    await installMocks(page);
    await page.goto('/map');
    await login(page);
    await searchNearby(page);

    // Search opens half; open results fully reachable then collapse with focus inside.
    await ensureResultsReachable(page);
    const municipalResult = page.getByRole('button', {
      name: /Municipal parking: Konak Municipal Lot/i,
    });
    await expect(municipalResult).toBeVisible();
    await municipalResult.focus();
    await expect(municipalResult).toBeFocused();

    await collapseSheet(page);

    const collapsedHandle = page.getByRole('button', { name: /Search results, collapsed/i });
    await expect(collapsedHandle).toBeVisible();
    await expect(collapsedHandle).toBeFocused();
    await expect(page.getByRole('button', { name: /^Clear$/i })).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: /Municipal parking: Konak Municipal Lot/i }),
    ).toHaveCount(0);

    await collapsedHandle.click();
    await expect(
      page.getByRole('button', { name: /Search results, half open/i }),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: /Municipal parking: Konak Municipal Lot/i }),
    ).toBeVisible();
  });

  test('keyboard detail navigation returns to an accessible map state', async ({ page }) => {
    await installMocks(page);
    await page.goto('/map');
    await login(page);
    await searchNearby(page);
    await ensureResultsReachable(page);

    await activateMunicipalMarker(page);

    const details = page.getByTestId('municipal-facility-view-details');
    await expect(details).toBeVisible();
    await details.focus();
    await details.press('Enter');

    await expect(page).toHaveURL(new RegExp(`/facilities/${FACILITY_ID}`));
    await expect(page.getByRole('heading', { name: /Konak Municipal Lot/i })).toBeVisible();

    const back = page.getByRole('link', { name: /Back to map/i });
    await back.focus();
    await back.press('Enter');
    await expect(page).toHaveURL(/\/map/);

    await expect(
      page.getByRole('region', { name: /Interactive parking discovery map/i }),
    ).toBeVisible();
    await expect(page.getByTestId('map-layer-visibility-controls')).toBeVisible();
  });
});
