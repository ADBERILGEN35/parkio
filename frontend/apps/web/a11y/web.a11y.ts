import { expect, test, type Page, type Route } from '@playwright/test';
import { keyboardWalk, measurePage, measureToastPalette, type Locale } from './helpers';

/**
 * CL-F30: the web app's public pages and its signed-in pages, measured in Turkish and English.
 * Every network call is mocked; map tiles and web fonts are blocked, so contrast is measured on the
 * app's own colours. Unmocked API calls are answered 404 and reported, never sent anywhere.
 */
const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';
const user = { id: USER_ID, email: 'a11y@parkio.dev', status: 'ACTIVE', roles: ['USER'] };
const session = {
  accessToken: 'access-a11y',
  tokenType: 'Bearer',
  accessTokenExpiresAt: '2999-01-01T00:00:00Z',
  refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
  user,
};

interface WebPage {
  name: string;
  path: string;
  signedIn: boolean;
  /**
   * Reached by in-app navigation. The web image's nginx redirects a direct /privacy or /terms request
   * to the marketing site (measured by a11y-marketing); the app's own legal pages open from its links.
   */
  inApp?: boolean;
}

const PAGES: WebPage[] = [
  { name: 'login', path: '/login', signedIn: false },
  { name: 'register', path: '/register', signedIn: false },
  { name: 'forgot-password', path: '/forgot-password', signedIn: false },
  { name: 'reset-password', path: '/reset-password?token=a11y-reset-token', signedIn: false },
  { name: 'check-email', path: '/check-email', signedIn: false },
  { name: 'verify-email', path: '/verify-email?token=a11y-verify-token', signedIn: false },
  { name: 'terms', path: '/terms', signedIn: false, inApp: true },
  { name: 'privacy', path: '/privacy', signedIn: false, inApp: true },
  { name: 'explore', path: '/explore', signedIn: false },
  { name: 'map', path: '/map', signedIn: true },
  { name: 'upload', path: '/upload', signedIn: true },
  { name: 'profile', path: '/profile', signedIn: true },
  { name: 'my-spots', path: '/my-spots', signedIn: true },
  { name: 'notifications', path: '/notifications', signedIn: true },
  { name: 'gamification', path: '/gamification', signedIn: true },
  { name: 'leaderboard', path: '/leaderboard', signedIn: true },
  { name: 'reports', path: '/reports', signedIn: true },
];

async function installMocks(page: Page, locale: Locale, signedIn: boolean, unmocked: string[]) {
  await page.addInitScript((value) => localStorage.setItem('parkio.locale', value), locale);
  // Nothing leaves the machine: every other host is aborted. Later routes take precedence, so the API
  // mock below still answers a built image's https://api.parkio.dev/api/v1 calls.
  await page.route(
    (url) => url.hostname !== 'localhost' && url.hostname !== '127.0.0.1',
    (route) => route.abort(),
  );
  await page.route(/openstreetmap\.org|api\.maptiler\.com|fonts\.(googleapis|gstatic)\.com/, (route) => route.abort());
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    if (method === 'POST' && path === '/auth/refresh-token') {
      return signedIn ? json(session) : json({ code: 'INVALID_TOKEN', message: 'No session', traceId: 'a11y' }, 401);
    }
    if (method === 'GET' && path === '/auth/registration-mode') return json({ mode: 'OPEN' });
    if (method === 'POST' && path === '/auth/verify-email') return json(user);
    if (method === 'GET' && path === '/auth/me') return json(user);
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/users/me/vehicle') return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    if (method === 'GET' && path === '/parking/spots/nearby') return json([]);
    if (method === 'GET' && path === '/parking/facilities/nearby') return json([]);
    if (method === 'GET' && path === '/geocoding/search') return json({ results: [] });
    unmocked.push(`${method} ${path}`);
    return json({ code: 'NOT_FOUND', message: `Not mocked: ${method} ${path}`, traceId: 'a11y' }, 404);
  });
}

for (const locale of ['tr', 'en'] as const) {
  test.describe(`web (${locale})`, () => {
    for (const target of PAGES) {
      test(`${target.name} (${locale})`, async ({ page }, testInfo) => {
        const unmocked: string[] = [];
        await installMocks(page, locale, target.signedIn, unmocked);
        if (target.inApp) {
          await page.goto('/login');
          await page.waitForLoadState('networkidle');
          await page.evaluate((path) => {
            window.history.pushState({}, '', path);
            window.dispatchEvent(new PopStateEvent('popstate'));
          }, target.path);
          await expect(page).toHaveURL(new RegExp(`${target.path}$`));
        } else {
          await page.goto(target.path);
        }
        await page.waitForLoadState('networkidle');
        // Pages have an h1 (some only for screen readers) or a main landmark; not always both.
        await expect(page.locator('h1, main, [role="main"]').first()).toBeAttached();
        const landedOn = new URL(page.url()).pathname;
        testInfo.annotations.push({ type: 'landed', description: landedOn });
        if (unmocked.length) testInfo.annotations.push({ type: 'unmocked', description: unmocked.join(', ') });
        // Verifying the address shows a success toast: measure all four rich toast colours first.
        if (target.name === 'verify-email') await measureToastPalette(page, testInfo, locale);
        await measurePage(page, testInfo, target.name, locale);
        await keyboardWalk(page, testInfo, target.name, locale, 150);
      });
    }
  });
}
