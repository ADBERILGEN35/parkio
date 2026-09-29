import { expect, test, type Page, type Route } from '@playwright/test';

const PASSWORD = 'StrongParkio123';
const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';

const user = {
  id: USER_ID,
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
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

async function installMocks(page: Page) {
  let authenticated = false;
  let loginRequests = 0;
  let smartReturnRequests = 0;
  let spotsNearbyRequests = 0;
  let facilitiesNearbyRequests = 0;

  await page.addInitScript(() => {
    localStorage.setItem('parkio.locale', 'en');
  });

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
      loginRequests += 1;
      authenticated = true;
      return json(authResponse());
    }
    if (method === 'POST' && path === '/auth/refresh-token') {
      if (!authenticated) {
        return json(
          { code: 'UNAUTHORIZED', message: 'Unauthorized', traceId: 'trace-refresh' },
          401,
        );
      }
      return json(authResponse());
    }
    if (method === 'GET' && path === '/auth/me') {
      if (!authenticated) {
        return json(
          { code: 'UNAUTHORIZED', message: 'Unauthorized', traceId: 'trace-anon' },
          401,
        );
      }
      return json(user);
    }
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/users/me/smart-return') {
      smartReturnRequests += 1;
      return json({
        smartReturnEnabled: true,
        homeLatitude: 38.4237,
        homeLongitude: 27.1428,
        expectedReturnTimeLocal: '18:30',
        timezone: 'Europe/Istanbul',
        drivingToday: true,
      });
    }
    if (method === 'GET' && path === '/parking/spots/nearby') {
      spotsNearbyRequests += 1;
      return json([]);
    }
    if (method === 'GET' && path === '/parking/facilities/nearby') {
      facilitiesNearbyRequests += 1;
      return json([]);
    }
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
    if (method === 'GET' && path === '/geocoding/search') return json({ results: [] });

    return json({});
  });

  return {
    getCounts: () => ({
      loginRequests,
      smartReturnRequests,
      spotsNearbyRequests,
      facilitiesNearbyRequests,
    }),
  };
}

async function login(page: Page) {
  await page.locator('input[autocomplete="email"]').fill(user.email);
  await page.locator('input[autocomplete="current-password"]').fill(PASSWORD);
  await page.getByRole('button', { name: /Giriş yap|Sign in/i }).click();
}

test.describe('WEB-MUNI-08 auth redirect query preservation', () => {
  test('anonymous municipal map deep-link survives login without restoring hash', async ({ page }) => {
    const api = await installMocks(page);

    await page.goto(
      '/map?smartReturn=1&communityLayer=0&municipalAvailability=available&foo=bar#frag',
    );

    await expect(page).toHaveURL(/\/login\?/);
    const loginUrl = new URL(page.url());
    expect(loginUrl.searchParams.get('return')).toBe(
      '/map?smartReturn=1&communityLayer=0&municipalAvailability=available&foo=bar',
    );

    await login(page);

    await expect(page).toHaveURL(/\/map\?/);
    await expect(page).toHaveURL(/smartReturn=1/);
    await expect(page).toHaveURL(/foo=bar/);
    await expect(page).not.toHaveURL(/municipalAvailability=available/);
    expect(new URL(page.url()).hash).toBe('');

    expect(api.getCounts()).toEqual({
      loginRequests: 1,
      smartReturnRequests: 1,
      spotsNearbyRequests: 0,
      facilitiesNearbyRequests: 0,
    });
  });

  test('unsafe login return targets still fall back to the canonical safe route', async ({
    page,
  }) => {
    await installMocks(page);

    await page.goto('/login?return=%2F%2Fevil.example%2Fsteal');
    await login(page);

    await expect(page).toHaveURL(/\/map$/);
  });

  test('browser back and forward do not re-enter an auth redirect loop', async ({ page }) => {
    await installMocks(page);

    await page.goto('/map?smartReturn=1&foo=bar#frag');
    await expect(page).toHaveURL(/\/login\?/);

    await login(page);
    await expect(page).toHaveURL(/\/map\?smartReturn=1&foo=bar|\/map\?foo=bar&smartReturn=1/);

    await page.goBack();
    await expect(page).toHaveURL(/\/login\?/);
    await expect(page.getByRole('heading', { name: /Welcome back|Tekrar hoş geldiniz/i })).toBeVisible();

    await page.goForward();
    await expect(page).toHaveURL(/\/map\?smartReturn=1&foo=bar|\/map\?foo=bar&smartReturn=1/);
  });

  test('mobile width deep-links preserve safe auth return targets', async ({ page }) => {
    await installMocks(page);
    await page.setViewportSize({ width: 390, height: 844 });

    await page.goto('/map?smartReturn=1&communityLayer=0&foo=bar#frag');
    await expect(page).toHaveURL(/\/login\?/);
    expect(new URL(page.url()).searchParams.get('return')).toBe(
      '/map?smartReturn=1&communityLayer=0&foo=bar',
    );
  });
});
