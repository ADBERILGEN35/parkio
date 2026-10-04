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

const NOW = '2026-10-04T08:00:00Z';
const spot = (n: number, status: string) => ({
  id: `0b8f6c3a-0000-0000-0000-00000000a11${n}`,
  mediaId: `0b8f6c3a-0000-0000-0000-0000000a11a${n}`,
  latitude: 41.008 + n / 1000,
  longitude: 28.978 + n / 1000,
  addressText: `${n} Synthetic Street, Istanbul`,
  description: 'Shaded street spot near the ferry',
  manualLocationEdited: false,
  suitableVehicleTypes: ['SEDAN', 'SMALL_CAR'],
  parkingContext: 'STREET_PARKING',
  legalStatus: 'LEGAL',
  violationReasons: [],
  status,
  expiresAt: '2999-01-01T00:00:00Z',
  createdAt: NOW,
  updatedAt: NOW,
  ownerUserId: USER_ID,
  confidenceScore: 70 + n,
  verificationCount: n,
  filledReportCount: 0,
});
const notification = (n: number, type: string, read: boolean) => ({
  id: `0b8f6c3a-0000-0000-0000-00000000b11${n}`,
  type,
  channel: 'IN_APP',
  title: `Synthetic notification ${n}`,
  body: 'A spot you verified was confirmed by two other drivers.',
  metadata: {},
  status: read ? 'READ' : 'SENT',
  createdAt: NOW,
  readAt: read ? NOW : null,
});
/** Populated answers for every signed-in page, so their filled state is what gets measured. */
const populated: Record<string, unknown> = {
  'GET /users/me': {
    id: USER_ID, authUserId: USER_ID, email: user.email, displayName: 'Ayşe Yılmaz', phoneNumber: null,
    city: 'Istanbul', status: 'ACTIVE', createdAt: '2026-09-01T08:00:00Z',
  },
  'GET /users/me/stats': { trustScore: 72, trustBand: 'MEDIUM_TRUST', totalPoints: 340, currentLevel: 3 },
  'GET /users/me/preferences': { preferredRadiusMeters: 800, notificationsEnabled: true, preferredLocale: 'tr' },
  'GET /notifications/me': [notification(1, 'POINT_EARNED', false), notification(2, 'LEVEL_UP', true), notification(3, 'SYSTEM', true)],
  'GET /parking/spots/nearby': [spot(1, 'ACTIVE'), spot(2, 'ACTIVE')],
  'GET /parking/my-spots': [spot(1, 'ACTIVE'), spot(3, 'FILLED'), spot(4, 'EXPIRED')],
  'GET /parking/sessions/lifecycle-config': {
    confirmAfterMs: 3_600_000, reminder2AfterMs: 7_200_000, autoCompleteAfterMs: 14_400_000,
    confirmAfter: 'PT1H', reminder2After: 'PT2H', autoCompleteAfter: 'PT4H',
    remindersEnabled: true, autoCompleteEnabled: true,
  },
  'GET /gamification/me/progress': { userId: USER_ID, totalPoints: 340, currentLevel: 3, updatedAt: NOW },
  'GET /gamification/me/points': {
    userId: USER_ID,
    totalPoints: 340,
    recentTransactions: [
      { sourceType: 'PARKING_VERIFIED', direction: 'EARNED', points: 15, relatedSpotId: spot(1, 'ACTIVE').id, createdAt: NOW },
      { sourceType: 'PARKING_UPLOAD', direction: 'EARNED', points: 25, relatedSpotId: spot(3, 'FILLED').id, createdAt: NOW },
      { sourceType: 'PENALTY_SPAM', direction: 'DEDUCTED', points: 10, relatedSpotId: null, createdAt: NOW },
    ],
  },
  'GET /gamification/me/level': {
    userId: USER_ID, currentLevel: 3, totalPoints: 340, currentLevelMinPoints: 250, nextLevelMinPoints: 500, pointsToNextLevel: 160,
  },
  'GET /gamification/me/access-policy': {
    userId: USER_ID, currentLevel: 3, searchRadiusMeters: 1500, resultLimit: 30, dailyViewLimit: 60,
    verifiedSpotPriority: true, notificationPriority: false,
  },
  'GET /gamification/levels': [1, 2, 3, 4].map((level) => ({
    level, minPoints: [0, 100, 250, 500][level - 1], maxPoints: [99, 249, 499, null][level - 1],
    searchRadiusMeters: 500 * level, resultLimit: 10 * level, dailyViewLimit: 20 * level,
    verifiedSpotPriority: level >= 3, notificationPriority: level >= 4,
  })),
  'GET /gamification/leaderboard': [1, 2, 3, 4, 5].map((rank) => ({
    rank, userId: rank === 3 ? USER_ID : `6f9619ff-8b86-4d01-b42d-00cf4fc9600${rank}`, totalPoints: 900 - rank * 110, currentLevel: 5 - Math.min(rank, 3),
  })),
  'GET /moderation/reports/me': [
    { id: '0b8f6c3a-0000-0000-0000-00000000c111', reporterUserId: USER_ID, targetType: 'PARKING_SPOT', targetId: spot(1, 'ACTIVE').id,
      reason: 'FAKE_PHOTO', description: 'The photo shows a different street.', caseId: null, createdAt: NOW },
    { id: '0b8f6c3a-0000-0000-0000-00000000c112', reporterUserId: USER_ID, targetType: 'PARKING_SPOT', targetId: spot(2, 'ACTIVE').id,
      reason: 'DUPLICATE_PHOTO', description: null, caseId: '0b8f6c3a-0000-0000-0000-00000000d111', createdAt: NOW },
  ],
  'GET /public/explore/facilities': {
    facilities: [1, 2].map((n) => ({
      id: `a11y-facility-${n}`, displayName: `Synthetic Car Park ${n}`, operatorName: 'Synthetic Operator', facilityType: 'OFF_STREET',
      addressText: `${n} Synthetic Square, Istanbul`, latitude: 41.01 + n / 1000, longitude: 28.97 + n / 1000, capacityTotal: 120,
      availableSpaces: 30 * n, availabilityFreshness: 'LIVE', dataUpdatedAt: NOW, sourceLabel: 'Synthetic source',
      attribution: 'Synthetic data for accessibility tests', accessClassification: 'PUBLIC',
    })),
    municipalTotalInScope: 2,
    municipalHiddenCount: 0,
    communitySpotCountInScope: null,
  },
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
    if (method === 'GET' && path === '/users/me/vehicle') return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    if (method === 'GET' && path === '/parking/facilities/nearby') return json([]);
    if (method === 'GET' && path === '/geocoding/search') return json({ results: [] });
    // No active parking session: the map shows its default state.
    if (method === 'GET' && path === '/parking/sessions/active') return route.fulfill({ status: 204 });
    const key = `${method} ${path}`;
    // The stored preference follows the run's locale, so it never switches the page language.
    if (key === 'GET /users/me/preferences') {
      return json({ ...(populated[key] as Record<string, unknown>), preferredLocale: locale });
    }
    if (key in populated) return json(populated[key]);
    const publicProfile = /^GET \/users\/([^/]+)\/public-profile$/.exec(key);
    if (publicProfile) {
      return json({
        userId: publicProfile[1], displayName: `Driver ${publicProfile[1].slice(-2)}`, city: 'Istanbul',
        trustBand: 'HIGH_TRUST', currentLevel: 4, status: 'ACTIVE', memberSince: '2026-08-01T08:00:00Z',
      });
    }
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
        // A 404 from an unmocked call would put the page in an empty or error state: the measurement
        // must be of the populated page.
        expect(unmocked, `${target.name} (${locale}): API calls without a populated mock`).toEqual([]);
      });
    }
  });
}
