import { expect, test, type Page } from '@playwright/test';

const user = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
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

/** Mocks the minimal API surface the entry routes touch. */
async function installMockApi(page: Page, { hasSession }: { hasSession: boolean }) {
  await page.addInitScript(() => {
    localStorage.setItem('parkio.locale', 'en');
  });
  await page.route(/openstreetmap\.org|api\.maptiler\.com|fonts\.(googleapis|gstatic)\.com/, (route) =>
    route.abort(),
  );

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });

    if (method === 'POST' && path === '/auth/refresh-token') {
      if (hasSession) return json(authResponse());
      return json({ code: 'INVALID_TOKEN', message: 'No session', traceId: 'e2e-home' }, 401);
    }
    if (method === 'POST' && path === '/auth/login') return json(authResponse());
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/users/me/vehicle') {
      return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    }
    if (method === 'GET' && path === '/parking/spots/nearby') return json([]);
    if (method === 'GET' && path === '/geocoding/search') return json({ results: [] });

    return json({ code: 'NOT_MOCKED', message: `Unmocked ${method} ${path}` }, 500);
  });
}

test.describe('default route (/)', () => {
  test('sends unauthenticated visitors to public Explore instead of a login wall', async ({
    page,
  }) => {
    await installMockApi(page, { hasSession: false });
    await page.route('**/api/v1/public/explore/facilities**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    );

    await page.goto('/');

    await expect(page).toHaveURL(/\/explore$/);
    await expect(page.getByTestId('public-explore-product')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Live public parking explore' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Sign in' })).toBeVisible();
  });

  test('restores a returning session at / and lands on the map product home', async ({ page }) => {
    await installMockApi(page, { hasSession: true });

    await page.goto('/');

    await expect(page).toHaveURL(/\/map$/);
    await expect(page.getByLabel('Search location')).toBeVisible();
  });

  test('keeps login available and reaches the product home after sign-in', async ({ page }) => {
    await installMockApi(page, { hasSession: false });

    await page.goto('/login');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('link', { name: 'Explore without an account' })).toBeVisible();

    await page.getByLabel('Email').fill(user.email);
    await page.getByLabel('Password').fill('StrongParkio123');
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page).toHaveURL(/\/map$/);
  });

  test('keeps public legal pages reachable while /map stays protected', async ({ page }) => {
    await installMockApi(page, { hasSession: false });

    await page.goto('/terms');
    await expect(page.getByRole('heading', { name: 'Terms of Service' })).toBeVisible();

    await page.goto('/privacy');
    await expect(page.getByRole('heading', { name: 'Privacy Policy' })).toBeVisible();

    await page.goto('/map');
    await expect(page).toHaveURL(/\/login/);
  });
});
