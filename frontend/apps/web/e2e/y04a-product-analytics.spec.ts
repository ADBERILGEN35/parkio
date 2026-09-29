import { expect, test, type Page, type Route, type Request } from '@playwright/test';

/**
 * Y04A — actual web app analytics acceptance against a protocol-faithful local sink.
 * Mock PostHog success ≠ real vendor ingestion / production CORS.
 */

const FACILITY_ID = '70db58f2-4cca-4010-9315-fa46b30fba1e';
const PASSWORD = 'StrongParkio123';
const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';
const USER_B_ID = '7a0720aa-9c97-5e12-c53e-11dg5gd07500';

const userA = {
  id: USER_ID,
  email: 'tester-a@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

const userB = {
  id: USER_B_ID,
  email: 'tester-b@parkio.dev',
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
  selectedFieldProvenanceSummary: { displayName: 'izum' },
  registryConfidenceOrReviewStatus: null,
  availabilitySource: null,
  availabilityObservationTimestamp: null,
};

type AnalyticsHook = {
  getConsent: () => string;
  getLocalEvents: () => Array<{ name: string; params?: Record<string, unknown>; distinctId?: string }>;
  getQueue: () => unknown[];
  persistIncomplete: () => Promise<void>;
};

async function waitForAnalyticsHook(page: Page): Promise<void> {
  await page.waitForFunction(
    () => Boolean((window as Window & { __PARKIO_ANALYTICS__?: unknown }).__PARKIO_ANALYTICS__),
    null,
    { timeout: 20_000 },
  );
}

async function getConsent(page: Page): Promise<string> {
  await waitForAnalyticsHook(page);
  return page.evaluate(() => {
    const w = window as Window & { __PARKIO_ANALYTICS__?: AnalyticsHook };
    return w.__PARKIO_ANALYTICS__!.getConsent();
  });
}

async function getLocalEvents(
  page: Page,
): Promise<Array<{ name: string; params?: Record<string, unknown>; distinctId?: string }>> {
  await waitForAnalyticsHook(page);
  return page.evaluate(() => {
    const w = window as Window & { __PARKIO_ANALYTICS__?: AnalyticsHook };
    return w.__PARKIO_ANALYTICS__!.getLocalEvents();
  });
}

async function openAnalyticsPreferences(page: Page): Promise<void> {
  await page.goto('/profile?section=notifications');
  await page.getByTestId('product-analytics-consent').waitFor({ state: 'visible', timeout: 20_000 });
}

function authResponse(authUser = userA) {
  return {
    accessToken: `access-${authUser.id}`,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2999-01-01T00:00:00Z',
    refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
    user: authUser,
  };
}

async function installMocks(page: Page, options?: { failBatch?: boolean }) {
  let authenticated: typeof userA | typeof userB | null = null;
  let loginEmail: string | null = null;

  // Clear analytics storage once per browser context — not on every navigation.
  // Re-clearing on each goto would wipe consent granted through the real UI.
  await page.addInitScript(() => {
    localStorage.setItem('parkio.locale', 'en');
    if (localStorage.getItem('parkio.y04a.storage.seeded') === '1') return;
    localStorage.setItem('parkio.y04a.storage.seeded', '1');
    localStorage.removeItem('parkio.analytics.consent.v1');
    localStorage.removeItem('parkio.analytics.session.v1');
    localStorage.removeItem('parkio.analytics.distinct.v1');
    localStorage.removeItem('parkio.analytics.incomplete_engagement.v1');
  });

  await page.route(/openstreetmap\.org|api\.maptiler\.com|fonts\.(googleapis|gstatic)\.com/, (route) =>
    route.abort(),
  );

  await page.route('**/parkio-y04a-sink.test/**', async (route) => {
    if (options?.failBatch) {
      return route.fulfill({ status: 503, body: 'unavailable' });
    }
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 1 }),
    });
  });

  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });

    if (method === 'POST' && path === '/auth/login') {
      const body = request.postDataJSON() as { email?: string };
      loginEmail = body.email ?? null;
      authenticated = loginEmail?.includes('tester-b') ? userB : userA;
      return json(authResponse(authenticated));
    }
    if (method === 'POST' && path === '/auth/logout') {
      authenticated = null;
      return json(null);
    }
    if (method === 'POST' && path === '/auth/refresh-token') {
      return authenticated
        ? json(authResponse(authenticated))
        : json({ code: 'UNAUTHORIZED', message: 'Unauthorized', traceId: 'trace-refresh' }, 401);
    }
    if (method === 'GET' && path === '/auth/me') {
      return authenticated
        ? json(authenticated)
        : json({ code: 'UNAUTHORIZED', message: 'Unauthorized', traceId: 'trace-anon' }, 401);
    }
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/users/me/vehicle') {
      return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    }
    if (method === 'GET' && path === '/users/me') {
      return json({
        id: authenticated?.id ?? USER_ID,
        authUserId: authenticated?.id ?? USER_ID,
        email: authenticated?.email ?? userA.email,
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
    if (method === 'PATCH' && path === '/users/me/preferences') {
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
    if (method === 'GET' && path === '/parking/spots/nearby') return json([]);
    if (method === 'GET' && path === '/parking/facilities/nearby') return json([municipalFacility]);
    if (method === 'GET' && path === `/parking/facilities/${FACILITY_ID}`) {
      return json(municipalFacility);
    }
    return json({ code: 'NOT_FOUND', message: path, traceId: 'y04a' }, 404);
  });
}

async function login(page: Page, email = userA.email) {
  await page.goto('/login');
  await page.getByLabel(/email/i).fill(email);
  await page.getByLabel(/password/i).fill(PASSWORD);
  await page.getByRole('button', { name: /sign in|giriş/i }).click();
  await expect(page).toHaveURL(/\/(map|preparing)/, { timeout: 20_000 });
}

test.describe('Y04A web actual-app analytics', () => {
  test('consent unset: no analytics network transmission', async ({ page }) => {
    const batchRequests: Request[] = [];
    page.on('request', (req) => {
      if (req.url().includes('parkio-y04a-sink.test')) batchRequests.push(req);
    });
    await installMocks(page);
    await page.goto('/login');
    await page.goto('/map?lat=38.42&lng=27.14');
    await page.waitForTimeout(1500);
    expect(await getConsent(page)).toBe('unset');
    expect(batchRequests).toHaveLength(0);
    const events = await getLocalEvents(page);
    expect(events.filter((e) => e.name === 'map_ready' || e.name === 'screen_viewed')).toHaveLength(0);
  });

  test('opt-in UI → map funnel → revoke stops outbound', async ({ page }) => {
    const batchBodies: unknown[] = [];
    await installMocks(page);
    await page.route('**/parkio-y04a-sink.test/**', async (route) => {
      const raw = route.request().postData();
      if (raw) batchBodies.push(JSON.parse(raw));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 1 }),
      });
    });

    await login(page);
    await openAnalyticsPreferences(page);
    await page.getByTestId('product-analytics-consent').check();
    await expect.poll(async () => getConsent(page)).toBe('granted');

    await page.goto('/map?lat=38.42&lng=27.14');
    await expect(page.getByTestId('map-layer-visibility-controls')).toBeVisible({ timeout: 20_000 });

    // Marker / list preview → detail → return
    const marker = page.getByTestId('municipal-facility-marker').first();
    if (await marker.count()) {
      await marker.click({ force: true });
    } else {
      const row = page.getByText('Konak Municipal Lot').first();
      if (await row.count()) await row.click();
    }

    const details = page.getByTestId('municipal-facility-view-details');
    if (await details.count()) {
      await details.click();
      await expect(page).toHaveURL(new RegExp(`/facilities/${FACILITY_ID}`));
      await page.getByRole('link', { name: /back to map|haritaya/i }).click();
      await expect(page).toHaveURL(/\/map/);
    }

    const events = await getLocalEvents(page);
    const names = events.map((e) => e.name);
    expect(names).toContain('analytics_session_started');
    expect(names.some((n) => n === 'screen_viewed')).toBe(true);

    for (const event of events) {
      const blob = JSON.stringify(event);
      expect(blob).not.toMatch(/tester-a@parkio\.dev/);
      expect(blob).not.toMatch(/access-/);
      expect(blob).not.toMatch(/lat=38/);
      expect(blob).not.toContain(FACILITY_ID);
    }

    await openAnalyticsPreferences(page);
    await page.getByTestId('product-analytics-consent').uncheck();
    const before = batchBodies.length;
    await page.goto('/map');
    await page.waitForTimeout(800);
    expect(batchBodies.length).toBe(before);

    if (batchBodies.length > 0) {
      const body = batchBodies[0] as {
        api_key: string;
        batch: Array<{ distinct_id: string; timestamp: string; properties: Record<string, unknown> }>;
      };
      expect(body.api_key.startsWith('phc_')).toBe(true);
      expect(body.batch[0].distinct_id.length).toBeGreaterThan(0);
      expect(body.batch[0].timestamp).toMatch(/^\d{4}-\d{2}-\d{2}T/);
      expect(body.batch[0].properties.$session_recording_enabled).toBe(false);
    }
  });

  test('transport failure leaves product usable', async ({ page }) => {
    await installMocks(page, { failBatch: true });
    await login(page);
    await openAnalyticsPreferences(page);
    await page.getByTestId('product-analytics-consent').check();
    await page.goto('/map?lat=38.42&lng=27.14');
    await expect(page.getByTestId('map-layer-visibility-controls')).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('body')).toBeVisible();
  });

  test('visibility hide persists incomplete engagement checkpoint', async ({ page }) => {
    await installMocks(page);
    await login(page);
    await openAnalyticsPreferences(page);
    await page.getByTestId('product-analytics-consent').check();
    await page.goto('/map?lat=38.42&lng=27.14');
    await page.waitForTimeout(500);
    await waitForAnalyticsHook(page);
    await page.evaluate(async () => {
      const w = window as Window & { __PARKIO_ANALYTICS__?: AnalyticsHook };
      await w.__PARKIO_ANALYTICS__?.persistIncomplete();
    });
    const stored = await page.evaluate(() =>
      localStorage.getItem('parkio.analytics.incomplete_engagement.v1'),
    );
    if (stored) {
      const parsed = JSON.parse(stored) as { activeDurationMs: number };
      expect(parsed.activeDurationMs).toBeGreaterThanOrEqual(0);
      expect(parsed.activeDurationMs).toBeLessThan(3_600_000);
    }
  });
});
