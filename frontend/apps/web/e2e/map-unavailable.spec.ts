import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * N-11: sustained map style / tile failures show a visible accessible alert on /map and /explore.
 * Runs on a dedicated Vite server with a synthetic MapTiler key and public explore on
 * (`playwright.map-unavailable.config.ts`), so style.json and tile URLs are real network requests
 * the test can mock. All external hosts are blocked.
 */

const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';
const user = { id: USER_ID, email: 'tester@parkio.dev', status: 'ACTIVE', roles: ['USER'] };

const VIEWPORTS = [
  { width: 1280, height: 720 },
  { width: 375, height: 812 },
] as const;

/** Style with a single raster source so tile 403s fire MapLibre source/tile errors after the style loads. */
const STYLE_WITH_TILES = {
  version: 8,
  sources: {
    osm: {
      type: 'raster',
      tiles: ['https://api.maptiler.com/tiles/n11-synthetic/{z}/{x}/{y}.png'],
      tileSize: 256,
    },
  },
  layers: [{ id: 'osm', type: 'raster', source: 'osm' }],
};

/** Style with no tile sources — successful basemap chrome without network tiles. */
const STYLE_NO_TILES = {
  version: 8,
  sources: {},
  layers: [{ id: 'background', type: 'background', paint: { 'background-color': '#eef1f5' } }],
};

const EXPLORE_FACILITIES = {
  facilities: [
    {
      id: 'fac-n11-1',
      displayName: 'N11 Synthetic Garage',
      addressText: '1 Test Street',
      latitude: 41.015,
      longitude: 28.98,
      sourceLabel: 'Test',
      facilityType: 'GARAGE',
      totalCapacity: 100,
      availableCapacity: 40,
      occupancyStatus: 'AVAILABLE',
      distanceMeters: 120,
    },
  ],
  truncated: false,
};

async function blockExternal(page: Page) {
  await page.route(
    (url) => url.hostname !== 'localhost' && url.hostname !== '127.0.0.1',
    (route) => route.abort(),
  );
}

async function mockApi(page: Page, { signedIn }: { signedIn: boolean }) {
  await page.addInitScript(() => {
    localStorage.setItem('parkio.locale', 'en');
  });
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });

    if (method === 'POST' && path === '/auth/refresh-token') {
      return signedIn
        ? json({
            accessToken: 'access-user',
            tokenType: 'Bearer',
            accessTokenExpiresAt: '2999-01-01T00:00:00Z',
            refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
            user,
          })
        : json({ code: 'INVALID_TOKEN', message: 'No session', traceId: 'n11' }, 401);
    }
    if (method === 'GET' && path === '/auth/registration-mode') return json({ mode: 'OPEN' });
    if (method === 'GET' && path === '/auth/me') return json(user);
    if (method === 'GET' && path === '/users/me') {
      return json({
        id: USER_ID,
        authUserId: USER_ID,
        email: user.email,
        displayName: 'Test Driver',
        phoneNumber: null,
        city: 'Izmir',
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
    if (method === 'GET' && path === '/parking/sessions/active') return route.fulfill({ status: 204, body: '' });
    if (method === 'GET' && path === '/parking/sessions/lifecycle-config') {
      return json({ sessionEnabled: true, allowManualStart: true });
    }
    if (method === 'GET' && path.endsWith('/nearby')) return json([]);
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/public/explore/facilities') return json(EXPLORE_FACILITIES);
    return json({});
  });
}

async function fulfillStyle(page: Page, body: unknown, status = 200) {
  await page.route(/^https:\/\/api\.maptiler\.com\/maps\/[^/]+\/style\.json/, (route) =>
    route.fulfill({
      status,
      contentType: 'application/json',
      body: status === 200 ? JSON.stringify(body) : 'forbidden',
    }),
  );
}

async function fulfillTiles(page: Page, status: number) {
  await page.route(/^https:\/\/api\.maptiler\.com\/tiles\/n11-synthetic\//, (route) =>
    route.fulfill({ status, contentType: 'text/plain', body: status === 200 ? '' : 'forbidden' }),
  );
}

test.describe('map unavailable alerts (N-11)', () => {
  for (const viewport of VIEWPORTS) {
    const size = `${viewport.width}x${viewport.height}`;

    test(`/map style 403 shows a visible alert at ${size}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await blockExternal(page);
      await mockApi(page, { signedIn: true });
      await fulfillStyle(page, null, 403);
      await page.goto('/map');
      await expect(page).toHaveURL(/\/map$/);
      const alert = page.getByTestId('map-page-map-unavailable');
      await expect(alert).toBeVisible({ timeout: 10_000 });
      await expect(alert).toHaveAttribute('role', 'alert');
      await expect(alert).toContainText(/couldn't load/i);
    });

    test(`/explore tile 403 shows a visible alert at ${size}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await blockExternal(page);
      await mockApi(page, { signedIn: false });
      await fulfillStyle(page, STYLE_WITH_TILES, 200);
      await fulfillTiles(page, 403);
      await page.goto('/explore');
      await expect(page).toHaveURL(/\/explore$/);
      const alert = page.getByTestId('public-explore-map-unavailable');
      await expect(alert).toBeVisible({ timeout: 10_000 });
      await expect(alert).toHaveAttribute('role', 'alert');
      await expect(page.getByTestId('public-explore-list-fallback')).toBeVisible();
    });

    test(`successful map style load does not false-alert at ${size}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await blockExternal(page);
      await mockApi(page, { signedIn: true });
      await fulfillStyle(page, STYLE_NO_TILES, 200);
      await page.goto('/map');
      await expect(page).toHaveURL(/\/map$/);
      await expect(page.getByRole('region', { name: /Interactive parking discovery map/i })).toBeVisible();
      await expect(page.getByTestId('map-page-map-unavailable')).toHaveCount(0);
      await page.waitForTimeout(1_500);
      await expect(page.getByTestId('map-page-map-unavailable')).toHaveCount(0);
    });

    test(`successful explore style load does not false-alert at ${size}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await blockExternal(page);
      await mockApi(page, { signedIn: false });
      await fulfillStyle(page, STYLE_NO_TILES, 200);
      await page.goto('/explore');
      await expect(page).toHaveURL(/\/explore$/);
      await expect(page.getByRole('region', { name: /public parking map/i })).toBeVisible();
      await expect(page.getByTestId('public-explore-map-unavailable')).toHaveCount(0);
      await page.waitForTimeout(1_500);
      await expect(page.getByTestId('public-explore-map-unavailable')).toHaveCount(0);
    });

    test(`isolated transient tile error does not false-alert explore at ${size}`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await blockExternal(page);
      await mockApi(page, { signedIn: false });
      await fulfillStyle(page, STYLE_WITH_TILES, 200);
      let tileHits = 0;
      await page.route(/^https:\/\/api\.maptiler\.com\/tiles\/n11-synthetic\//, async (route) => {
        tileHits += 1;
        if (tileHits <= 1) {
          return route.fulfill({ status: 403, contentType: 'text/plain', body: 'forbidden' });
        }
        // 1x1 PNG
        const png = Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
          'base64',
        );
        return route.fulfill({ status: 200, contentType: 'image/png', body: png });
      });
      await page.goto('/explore');
      await expect(page).toHaveURL(/\/explore$/);
      await expect(page.getByRole('region', { name: /public parking map/i })).toBeVisible();
      await page.waitForTimeout(2_000);
      await expect(page.getByTestId('public-explore-map-unavailable')).toHaveCount(0);
    });
  }
});
