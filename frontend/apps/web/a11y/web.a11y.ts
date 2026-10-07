import { expect, test, type Page, type Route } from '@playwright/test';
import {
  keyboardWalk,
  matchesKnownIssue,
  measurePage,
  measureToastPalette,
  reportKnownIssueSightings,
  writeReport,
  type AxeNode,
  type Locale,
} from './helpers';
import { KNOWN_ISSUES, type KnownIssue } from './known-issues';

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
    userId: USER_ID, currentLevel: 3, searchRadiusMeters: 1600, resultLimit: 30, dailyViewLimit: 60,
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
  // Within the explore query's 5 km of its default origin (İzmir centre, 38.4237, 27.1428), as the API
  // returns them, and about 3 km apart. They used to sit in Istanbul, 330 km away, so the map framed
  // both cities and the two markers covered each other (WCAG 2.5.8, Asana 1219147334320125).
  'GET /public/explore/facilities': {
    facilities: [1, 2].map((n) => ({
      id: `a11y-facility-${n}`, displayName: `Synthetic Car Park ${n}`, operatorName: 'Synthetic Operator', facilityType: 'OFF_STREET',
      addressText: `${n} Synthetic Square, Izmir`, latitude: 38.4237 + (n === 1 ? 0.012 : -0.012),
      longitude: 27.1428 + (n === 1 ? 0.01 : -0.01), capacityTotal: 120,
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
  /** Synthetic text from the populated mocks that the page must show, so its filled state is measured. */
  shows?: string;
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
  { name: 'explore', path: '/explore', signedIn: false, shows: 'Synthetic Car Park 1' },
  // /map lists spots only after a location search, which this run does not make; /upload has no list.
  { name: 'map', path: '/map', signedIn: true },
  { name: 'upload', path: '/upload', signedIn: true },
  { name: 'profile', path: '/profile', signedIn: true, shows: 'Ayşe Yılmaz' },
  { name: 'my-spots', path: '/my-spots', signedIn: true, shows: '1 Synthetic Street, Istanbul' },
  { name: 'notifications', path: '/notifications', signedIn: true, shows: 'Synthetic notification 1' },
  // The access policy's search radius. No level has 1600 (they are 500 × level, shown as "1500 m radius" in the
  // levels roadmap), so only that call can show it (#247 review N3).
  { name: 'gamification', path: '/gamification', signedIn: true, shows: '1600 m' },
  { name: 'leaderboard', path: '/leaderboard', signedIn: true, shows: '790' },
  { name: 'reports', path: '/reports', signedIn: true, shows: 'The photo shows a different street.' },
];

/**
 * Pages whose MapTiler style request was aborted. A built image bakes a MapTiler key, so its map style comes
 * from api.maptiler.com, which these tests never reach. The dev server has no key and uses the inline
 * OpenStreetMap fallback style instead.
 */
const mapTilerStyleAborted = new WeakSet<Page>();

/**
 * /explore keeps MapLibre's own attribution control on its canvas, with the style's OpenStreetMap credit
 * and MapLibre's credit (#258 review B1: /map's detached credits must not remove it here). This server
 * runs with public explore on, so /explore shows its map; the default e2e server does not.
 * The OpenStreetMap credit comes from the style. Against a built image (A11Y_WEB_URL) the MapTiler style is
 * aborted, so its credits never load there: only the control and MapLibre's credit are checked, and the style
 * must have been the aborted MapTiler one. Against the dev server it must be the fallback, with all three.
 */
async function expectMapAttributionOnCanvas(page: Page) {
  const builtImage = Boolean(process.env.A11Y_WEB_URL);
  await expect
    .poll(() => mapTilerStyleAborted.has(page), { message: 'explore: map style source (MapTiler only in a built image)' })
    .toBe(builtImage);
  const attribution = page.locator('.maplibregl-canvas-container ~ .maplibregl-control-container .maplibregl-ctrl-attrib');
  await expect(attribution, 'explore: one attribution control on the map canvas').toHaveCount(1);
  if (!builtImage) {
    await expect(attribution.locator('a[href*="openstreetmap.org/copyright"]'), 'explore: OpenStreetMap credit').toHaveCount(1);
  }
  await expect(attribution.locator('a[href^="https://maplibre.org"]'), 'explore: MapLibre credit').toHaveCount(1);
}

/** `overrides` answers some calls with other data than `populated`, keyed the same way. */
async function installMocks(
  page: Page,
  locale: Locale,
  signedIn: boolean,
  unmocked: string[],
  overrides: Record<string, unknown> = {},
) {
  await page.addInitScript((value) => localStorage.setItem('parkio.locale', value), locale);
  // Nothing leaves the machine: every other host is aborted. Later routes take precedence, so the API
  // mock below still answers a built image's https://api.parkio.dev/api/v1 calls.
  await page.route(
    (url) => url.hostname !== 'localhost' && url.hostname !== '127.0.0.1',
    (route) => route.abort(),
  );
  await page.route(/openstreetmap\.org|api\.maptiler\.com|fonts\.(googleapis|gstatic)\.com/, (route) => {
    if (/^https:\/\/api\.maptiler\.com\/maps\/[^/]+\/style\.json/.test(route.request().url())) mapTilerStyleAborted.add(page);
    return route.abort();
  });
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
    if (key in overrides) return json(overrides[key]);
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
        if (target.shows) {
          // Visible text, or an accessible name such as a map marker's label.
          const populatedContent = page.getByText(target.shows).or(page.getByLabel(target.shows));
          await expect(populatedContent.first(), `${target.name} (${locale}): populated content`).toBeVisible();
        }
        const landedOn = new URL(page.url()).pathname;
        testInfo.annotations.push({ type: 'landed', description: landedOn });
        if (unmocked.length) testInfo.annotations.push({ type: 'unmocked', description: unmocked.join(', ') });
        // Verifying the address shows a success toast: measure all four rich toast colours first.
        if (target.name === 'verify-email') await measureToastPalette(page, testInfo, locale);
        if (target.name === 'explore') await expectMapAttributionOnCanvas(page);
        await measurePage(page, testInfo, target.name, locale);
        await keyboardWalk(page, testInfo, target.name, locale, 150);
        // A 404 from an unmocked call would put the page in an empty or error state: the measurement
        // must be of the populated page.
        expect(unmocked, `${target.name} (${locale}): API calls without a populated mock`).toEqual([]);
      });
    }
  });
}

/**
 * Asana 1219147334320125 (b): dense and coincident car parks on /explore, at a laptop and a phone width.
 * The explore API answers with up to 6 car parks within 5 km of the origin, nearest first. Each answer
 * here is one such discovery; the names are synthetic.
 */
const exploreFacility = (n: number, latitude: number, longitude: number) => ({
  id: `a11y-explore-${n}`, displayName: `Synthetic Explore Car Park ${n}`, operatorName: 'Synthetic Operator',
  facilityType: 'OFF_STREET', addressText: `${n} Synthetic Avenue, Izmir`, latitude, longitude, capacityTotal: 80,
  availableSpaces: 10 + n, availabilityFreshness: 'LIVE', dataUpdatedAt: NOW, sourceLabel: 'Synthetic source',
  attribution: 'Synthetic data for accessibility tests', accessClassification: 'PUBLIC',
});
const exploreAnswer = (facilities: ReturnType<typeof exploreFacility>[]) => ({
  facilities, municipalTotalInScope: facilities.length, municipalHiddenCount: 0, communitySpotCountInScope: null,
});

/**
 * Six car parks over 3.6 km, so the map frames them at about zoom 12–13:
 * - 1–4 at the coordinates of real İzmir car parks of the İZUM test fixture
 *   (services/parking-service/src/test/resources/fixtures/municipal/izum/otoparklar-sample.json),
 *   80–230 m apart;
 * - 5 and 6 at one point, as two entries for one building do. Zooming never separates them.
 */
const DENSE_EXPLORE = exploreAnswer([
  exploreFacility(1, 38.432585, 27.14668),
  exploreFacility(2, 38.432968, 27.145272),
  exploreFacility(3, 38.433614, 27.144863),
  exploreFacility(4, 38.433452, 27.1475),
  exploreFacility(5, 38.403563, 27.11006),
  exploreFacility(6, 38.403563, 27.11006),
]);
/** Car parks to the south and the north: at 360 px the map frames the northern one at its top edge. */
const NORTH_SOUTH_EXPLORE = exploreAnswer([exploreFacility(7, 38.4037, 27.1458), exploreFacility(8, 38.4537, 27.1398)]);
/** One car park to the north-west: at 1280 px the map frames it near its top-left corner. */
const NORTH_WEST_EXPLORE = exploreAnswer([exploreFacility(9, 38.4357, 27.1028)]);

const FACILITY_MARKER = '[data-testid="municipal-facility-marker"]';

/** Waits until the facility markers stop moving: the map frames them with an animation. */
async function waitForStillMarkers(page: Page) {
  let previous = '';
  let stillPolls = 0;
  await expect
    .poll(
      async () => {
        const now = JSON.stringify(
          await page.locator(FACILITY_MARKER).evaluateAll((els) =>
            els.map((el) => {
              const box = el.getBoundingClientRect();
              return [Math.round(box.left), Math.round(box.top)];
            }),
          ),
        );
        stillPolls = now === previous ? stillPolls + 1 : 0;
        previous = now;
        return stillPolls;
      },
      { message: 'explore: the markers settle after framing', intervals: [400], timeout: 20_000 },
    )
    .toBeGreaterThanOrEqual(3);
}

interface MarkerTarget {
  id: string;
  name: string | null;
  left: number;
  top: number;
  width: number;
  height: number;
  /** The share of a 9×9 grid of points over the marker where the marker is the topmost element. */
  onTopShare: number;
  /** What is on top of the marker elsewhere: another marker, or a page element by its test id. */
  coveredBy: string[];
}

/** Hit-tests each facility marker on a grid, so a covered marker and what covers it are named. */
async function measureMarkerTargets(page: Page): Promise<MarkerTarget[]> {
  return page.locator(FACILITY_MARKER).evaluateAll((els) =>
    els.map((el) => {
      const box = el.getBoundingClientRect();
      const steps = 9;
      let onTop = 0;
      const coveredBy = new Set<string>();
      for (let i = 0; i < steps; i++) {
        for (let j = 0; j < steps; j++) {
          const x = box.left + 1 + ((box.width - 2) * i) / (steps - 1);
          const y = box.top + 1 + ((box.height - 2) * j) / (steps - 1);
          const hit = document.elementFromPoint(x, y);
          if (hit && (hit === el || el.contains(hit) || hit === el.parentElement)) {
            onTop++;
            continue;
          }
          // A hit on another marker's MapLibre wrapper (outside its rounded corners) is that marker.
          const owner =
            hit?.closest('.maplibregl-marker')?.querySelector<HTMLElement>('[data-facility-id]') ??
            hit?.closest<HTMLElement>('[data-facility-id], [data-testid]');
          coveredBy.add(
            owner?.dataset.facilityId
              ? `marker ${owner.dataset.facilityId}`
              : (owner?.dataset.testid ?? hit?.tagName.toLowerCase() ?? 'nothing'),
          );
        }
      }
      return {
        id: el.getAttribute('data-facility-id') ?? '',
        name: el.getAttribute('aria-label'),
        left: Math.round(box.left),
        top: Math.round(box.top),
        width: Math.round(box.width),
        height: Math.round(box.height),
        onTopShare: onTop / (steps * steps),
        coveredBy: [...coveredBy],
      };
    }),
  );
}

interface FacilityFocusStop {
  id: string;
  name: string | null;
  /** The focused marker is the topmost element at its centre and at four inner points. */
  onTop: boolean;
}

/** Tabs through the page and records each facility marker that takes the focus, in order. */
async function walkFacilityMarkers(page: Page, limit = 80): Promise<FacilityFocusStop[]> {
  await page.locator('body').click({ position: { x: 1, y: 1 } });
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  const stops: FacilityFocusStop[] = [];
  for (let index = 0; index < limit; index++) {
    await page.keyboard.press('Tab');
    const stop = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body || el === document.documentElement) return 'left';
      const id = el.getAttribute('data-facility-id');
      if (!id) return 'other';
      const box = el.getBoundingClientRect();
      const points = [[0.5, 0.5], [0.25, 0.25], [0.75, 0.25], [0.25, 0.75], [0.75, 0.75]];
      const onTop = points.every(([fx, fy]) => {
        const hit = document.elementFromPoint(box.left + box.width * fx, box.top + box.height * fy);
        return Boolean(hit) && (hit === el || el.contains(hit));
      });
      return { id, name: el.getAttribute('aria-label'), onTop };
    });
    if (stop === 'left') break;
    if (stop !== 'other') {
      if (stops.some((seen) => seen.id === stop.id)) break;
      stops.push(stop);
    }
  }
  return stops;
}

/** The bottom of each element over the top of the map, measured from the top of the map region. */
async function measureTopOverlay(page: Page) {
  return page.evaluate(() => {
    const map = document.querySelector('.maplibregl-map')?.getBoundingClientRect();
    const ids = ['public-explore-destination-search', 'public-explore-discovery-summary', 'public-explore-contribute-cta'];
    return {
      mapTop: map ? Math.round(map.top) : null,
      elements: ids.map((id) => {
        const box = document.querySelector(`[data-testid="${id}"]`)?.getBoundingClientRect();
        return box && map
          ? { id, left: Math.round(box.left), right: Math.round(box.right), bottomBelowMapTop: Math.round(box.bottom - map.top) }
          : { id, missing: true };
      }),
    };
  });
}

/**
 * A minimal map style with no tiles (the same stub the CX-F11 acceptance serves). A built image bakes a
 * MapTiler key, so its map style comes from api.maptiler.com, which these tests never reach; aborting that
 * request makes the map "unavailable" (CL-F20) and the page shows its list fallback instead of the map.
 * The marker tests need the map as production shows it, so they answer the style request with this stub.
 * The dev server uses the inline OpenStreetMap fallback style and never makes the request.
 */
const STUB_MAP_STYLE = {
  version: 8,
  sources: {},
  layers: [{ id: 'background', type: 'background', paint: { 'background-color': '#eef1f5' } }],
};

/**
 * Opens /explore with `answer` as the facilities. `mapStyle: 'stub'` (the default) serves the built image's
 * MapTiler style request with {@link STUB_MAP_STYLE}; `'abort'` keeps installMocks' abort, so a built image
 * shows the map-unavailable fallback.
 */
async function openExplore(
  page: Page,
  viewport: { width: number; height: number },
  answer: unknown,
  { mapStyle = 'stub' }: { mapStyle?: 'stub' | 'abort' } = {},
) {
  await page.setViewportSize(viewport);
  const unmocked: string[] = [];
  await installMocks(page, 'en', false, unmocked, { 'GET /public/explore/facilities': answer });
  if (mapStyle === 'stub') {
    // Registered after installMocks, so it takes precedence over its abort of api.maptiler.com.
    await page.route(/^https:\/\/api\.maptiler\.com\/maps\/[^/]+\/style\.json/, (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(STUB_MAP_STYLE) }),
    );
  }
  await page.goto('/explore');
  await page.waitForLoadState('networkidle');
  return unmocked;
}

interface ListFocusStop {
  name: string;
  /** The focused entry is the topmost element at its centre and at four inner points. */
  onTop: boolean;
}

/** Tabs through the page and records each entry of the map-unavailable list that takes the focus, in order. */
async function walkFallbackList(page: Page, limit = 80): Promise<ListFocusStop[]> {
  await page.locator('body').click({ position: { x: 1, y: 1 } });
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
  const stops: ListFocusStop[] = [];
  for (let index = 0; index < limit; index++) {
    await page.keyboard.press('Tab');
    const stop = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body || el === document.documentElement) return 'left';
      if (el.getAttribute('data-testid') !== 'public-explore-list-item') return 'other';
      el.scrollIntoView({ block: 'nearest' });
      const box = el.getBoundingClientRect();
      const points = [[0.5, 0.5], [0.25, 0.25], [0.75, 0.25], [0.25, 0.75], [0.75, 0.75]];
      const onTop = points.every(([fx, fy]) => {
        const hit = document.elementFromPoint(box.left + box.width * fx, box.top + box.height * fy);
        return Boolean(hit) && (hit === el || el.contains(hit));
      });
      // The accessible text: decorative icons (aria-hidden ligatures such as "garage") are not part of it.
      const label = el.cloneNode(true) as HTMLElement;
      label.querySelectorAll('[aria-hidden="true"]').forEach((node) => node.remove());
      return { name: (label.textContent ?? '').trim(), onTop };
    });
    if (stop === 'left') break;
    if (stop !== 'other') {
      if (stops.some((seen) => seen.name === stop.name)) break;
      stops.push(stop);
    }
  }
  return stops;
}

test.describe('explore: dense and coincident car parks (Asana 1219147334320125)', () => {
  for (const viewport of [{ width: 1280, height: 720 }, { width: 360, height: 800 }]) {
    const size = `${viewport.width}x${viewport.height}`;
    test(`every car park is a reachable target at ${size}`, async ({ page }, testInfo) => {
      const name = `explore-dense-${size}`;
      const unmocked = await openExplore(page, viewport, DENSE_EXPLORE);
      const markers = page.locator(FACILITY_MARKER);
      await expect(markers).toHaveCount(DENSE_EXPLORE.facilities.length);
      await waitForStillMarkers(page);
      // The accessible names stay the facilities' names.
      expect(await markers.evaluateAll((els) => els.map((el) => el.getAttribute('aria-label')))).toEqual(
        DENSE_EXPLORE.facilities.map((facility) => `Municipal parking facility: ${facility.displayName}`),
      );
      const targets = await measureMarkerTargets(page);
      await page.screenshot({ path: testInfo.outputPath(`${name}.png`) });
      const focusStops = await walkFacilityMarkers(page);
      const overlay = await measureTopOverlay(page);
      writeReport(testInfo, `${testInfo.project.name}-${name}-markers`, { page: name, targets, focusStops, overlay });
      expect
        .soft(
          targets.filter((target) => target.onTopShare < 1).map((target) => `${target.id} covered by ${target.coveredBy.join(', ')}`),
          `${name}: car park markers that something else covers`,
        )
        .toEqual([]);
      expect
        .soft(focusStops.map((stop) => stop.id), `${name}: Tab reaches every car park, in the API's order`)
        .toEqual(DENSE_EXPLORE.facilities.map((facility) => facility.id));
      expect
        .soft(focusStops.filter((stop) => !stop.onTop).map((stop) => stop.id), `${name}: focused car parks that something covers`)
        .toEqual([]);
      await keyboardWalk(page, testInfo, name, 'en', 150);
      await measurePage(page, testInfo, name, 'en', 'en', 'explore');
      expect(unmocked, `${name}: API calls without a populated mock`).toEqual([]);
    });
  }

  for (const viewport of [{ width: 1280, height: 720 }, { width: 360, height: 800 }]) {
    const size = `${viewport.width}x${viewport.height}`;
    test(`fanned car parks follow the zoom, and focus and selection stay, at ${size}`, async ({ page }, testInfo) => {
      const name = `explore-zoom-${size}`;
      const unmocked = await openExplore(page, viewport, DENSE_EXPLORE);
      await expect(page.locator(FACILITY_MARKER)).toHaveCount(DENSE_EXPLORE.facilities.length);
      await waitForStillMarkers(page);
      // A connector created later sits later in the DOM, so they are compared as a set.
      const fannedIds = async () =>
        (
          await page
            .locator('[data-testid="municipal-facility-fan-connector"]')
            .evaluateAll((els) => els.map((el) => el.getAttribute('data-fan-for') ?? ''))
        ).sort();
      const allIds = DENSE_EXPLORE.facilities.map((facility) => facility.id);
      await expect.poll(fannedIds, { message: `${name}: framed, every car park is fanned` }).toEqual(allIds);

      // Select car park 2 with the keyboard.
      const second = page.locator('[data-facility-id="a11y-explore-2"]');
      await second.focus();
      await page.keyboard.press('Enter');
      await expect(second).toHaveAttribute('aria-pressed', 'true');
      await expect(page.getByTestId('selected-municipal-facility-preview')).toContainText('Synthetic Explore Car Park 2');

      // Wheel-zooms over the group, a step at a time, until the fanned car parks are `expected`.
      const zoomUntil = async (direction: 1 | -1, expected: string[], what: string) => {
        for (let step = 0; step < 16; step++) {
          if (JSON.stringify(await fannedIds()) === JSON.stringify(expected)) break;
          await page.mouse.wheel(0, 300 * direction);
          await waitForStillMarkers(page);
        }
        expect(await fannedIds(), `${name}: ${what}`).toEqual(expected);
      };

      // Zoom in over the group until its car parks no longer touch: they go back to their places. The
      // pair at one point stays fanned at every zoom.
      const box = (await second.boundingBox())!;
      await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
      await zoomUntil(-1, ['a11y-explore-5', 'a11y-explore-6'], 'zoomed in, only the pair at one point is fanned');
      const zoomedIn = await measureMarkerTargets(page);
      await page.screenshot({ path: testInfo.outputPath(`${name}-in.png`) });
      await expect(second, `${name}: focus stays on car park 2`).toBeFocused();
      await expect(second).toHaveAttribute('aria-pressed', 'true');
      await expect(page.getByTestId('selected-municipal-facility-preview')).toContainText('Synthetic Explore Car Park 2');

      // Zoom back out until they meet again: they fan out again.
      await zoomUntil(1, allIds, 'zoomed out, the group is fanned again');
      const zoomedOut = await measureMarkerTargets(page);
      await page.screenshot({ path: testInfo.outputPath(`${name}-out.png`) });
      await expect(second, `${name}: focus stays on car park 2`).toBeFocused();
      await expect(second).toHaveAttribute('aria-pressed', 'true');
      await expect(page.getByTestId('selected-municipal-facility-preview')).toContainText('Synthetic Explore Car Park 2');

      writeReport(testInfo, `${testInfo.project.name}-${name}-markers`, { page: name, zoomedIn, zoomedOut });
      const covered = (targets: MarkerTarget[]) =>
        targets
          .filter((target) => target.onTopShare < 1 && target.coveredBy.some((what) => what.startsWith('marker ')))
          .map((target) => `${target.id} covered by ${target.coveredBy.join(', ')}`);
      expect(covered(zoomedIn), `${name}: zoomed in, car parks that another car park covers`).toEqual([]);
      expect(covered(zoomedOut), `${name}: zoomed out, car parks that another car park covers`).toEqual([]);
      expect(unmocked, `${name}: API calls without a populated mock`).toEqual([]);
    });
  }

  for (const [label, answer, viewport] of [
    ['north-south', NORTH_SOUTH_EXPLORE, { width: 360, height: 800 }],
    ['north-west', NORTH_WEST_EXPLORE, { width: 1280, height: 720 }],
  ] as const) {
    const size = `${viewport.width}x${viewport.height}`;
    test(`a car park framed at the top edge stays clear of the controls over the map: ${label} at ${size}`, async ({ page }, testInfo) => {
      const name = `explore-top-${label}-${size}`;
      const unmocked = await openExplore(page, viewport, answer);
      await expect(page.locator(FACILITY_MARKER)).toHaveCount(answer.facilities.length);
      await waitForStillMarkers(page);
      const targets = await measureMarkerTargets(page);
      const overlay = await measureTopOverlay(page);
      await page.screenshot({ path: testInfo.outputPath(`${name}.png`) });
      writeReport(testInfo, `${testInfo.project.name}-${name}-markers`, { page: name, targets, overlay });
      expect
        .soft(
          targets.filter((target) => target.onTopShare < 1).map((target) => `${target.id} covered by ${target.coveredBy.join(', ')}`),
          `${name}: car park markers that something else covers`,
        )
        .toEqual([]);
      await measurePage(page, testInfo, name, 'en', 'en', 'explore');
      expect(unmocked, `${name}: API calls without a populated mock`).toEqual([]);
    });
  }
});

/**
 * The map-unavailable state of a built image (CL-F20, Asana 1219147334320125): the MapTiler style request is
 * aborted, the page shows the list fallback, and the dead map keeps no car park markers that would take the
 * keyboard focus behind that list (WCAG 2.4.11). Only a built image requests the style, so the dev server
 * cannot reach this state.
 */
test.describe('explore: map style unavailable in a built image (CL-F20)', () => {
  for (const viewport of [{ width: 1280, height: 720 }, { width: 360, height: 800 }]) {
    const size = `${viewport.width}x${viewport.height}`;
    test(`the list replaces the map and every car park is a visible keyboard stop at ${size}`, async ({ page }, testInfo) => {
      test.skip(!process.env.A11Y_WEB_URL, 'only a built image requests the MapTiler style; the dev server uses the inline fallback style');
      const name = `explore-unavailable-${size}`;
      const unmocked = await openExplore(page, viewport, DENSE_EXPLORE, { mapStyle: 'abort' });
      await expect(page.getByTestId('public-explore-map-unavailable'), `${name}: the map-unavailable alert`).toBeVisible();
      await expect(page.getByTestId('public-explore-list-item')).toHaveCount(DENSE_EXPLORE.facilities.length);
      await expect(page.locator(FACILITY_MARKER), `${name}: no car park marker on the dead map`).toHaveCount(0);
      const stops = await walkFallbackList(page);
      await page.screenshot({ path: testInfo.outputPath(`${name}.png`) });
      writeReport(testInfo, `${testInfo.project.name}-${name}-list`, { page: name, stops });
      expect
        .soft(stops.map((stop) => stop.name), `${name}: Tab reaches every car park in the list, in the API's order`)
        .toEqual(DENSE_EXPLORE.facilities.map((facility) => facility.displayName));
      expect
        .soft(stops.filter((stop) => !stop.onTop).map((stop) => stop.name), `${name}: focused list entries that something covers`)
        .toEqual([]);
      await keyboardWalk(page, testInfo, name, 'en', 150);
      await measurePage(page, testInfo, name, 'en', 'en', 'explore');
      expect(unmocked, `${name}: API calls without a populated mock`).toEqual([]);
    });
  }
});

/**
 * The focus-indicator rule itself (#229 review N1), on synthetic pages with no app code. A transparent
 * outline, as Tailwind's `focus:outline-none` leaves, is not an indicator. A ring that fades in is one,
 * once its transition has finished (the delay keeps it unchanged right after Tab), and so is a solid outline.
 */
test.describe('focus indicator rule', () => {
  const html = (body: string) => `<!doctype html><html lang="en"><head><style>
    button { all: unset; display: inline-block; padding: 8px; }
    .transparent:focus { outline: 2px solid transparent; outline-offset: 2px; }
    .ring { transition: box-shadow 150ms 300ms; }
    .ring:focus { outline: 2px solid transparent; outline-offset: 2px; box-shadow: 0 0 0 2px rgb(0, 80, 203); }
    .solid:focus { outline: 2px solid rgb(0, 80, 203); }
    .oklch-outline:focus { outline: 2px solid oklch(0.6 0.15 250 / 0); }
    .oklch-ring:focus { outline: 2px solid transparent; box-shadow: 0 0 0 2px oklch(0.6 0.15 250 / 0); }
    .oklch-none:focus { outline: 2px solid oklch(0.6 0.15 250 / none); }
  </style></head><body><main><h1>Focus</h1>${body}</main></body></html>`;

  test('a transparent outline alone is not a focus indicator', async ({ page }, testInfo) => {
    await page.setContent(html('<button class="transparent">Transparent</button>'));
    await expect(keyboardWalk(page, testInfo, 'focus-rule-transparent', 'en')).rejects.toThrow(
      /without a visible indicator/,
    );
  });

  test('transparent oklch outlines and rings are not focus indicators either', async ({ page }, testInfo) => {
    // Tailwind 4's palette is oklch: its transparent colours end in "/ 0" (#242 review N3).
    // A "none" alpha renders as 0 too (#247 review N2).
    for (const kind of ['oklch-outline', 'oklch-ring', 'oklch-none']) {
      await page.setContent(html(`<button class="${kind}">${kind}</button>`));
      await expect(keyboardWalk(page, testInfo, `focus-rule-${kind}`, 'en')).rejects.toThrow(
        /without a visible indicator/,
      );
    }
  });

  test('the alpha parser reads every computed colour form', async ({ page }, testInfo) => {
    await page.setContent(html('<button class="solid">Solid</button>'));
    await keyboardWalk(page, testInfo, 'focus-rule-alpha', 'en');
    const alphas = await page.evaluate(() => {
      const alphaOf = (window as unknown as { __a11yAlphaOf: (colour: string) => number }).__a11yAlphaOf;
      return [
        'transparent', 'rgba(0, 0, 0, 0)', 'rgb(0, 80, 203)', 'rgb(0 80 203 / 50%)', 'oklch(0.6 0.15 250 / 0)',
        'oklch(0.6 0.15 250 / none)', 'oklch(0.6 0.15 250 / 1e-7)', 'color(srgb 0 0 0 / 0%)', 'oklch(0.6 0.15 250)',
      ].map((colour) => alphaOf(colour));
    });
    expect(alphas).toEqual([0, 0, 1, 0.5, 0, 0, 1e-7, 0, 1]);
  });

  test('a ring that fades in and a solid outline are focus indicators', async ({ page }, testInfo) => {
    await page.setContent(html('<button class="ring">Ring</button><button class="solid">Solid</button>'));
    const stops = await keyboardWalk(page, testInfo, 'focus-rule-visible', 'en');
    expect(stops.map((stop) => [stop.name, stop.indicator])).toEqual([
      ['Ring', true],
      ['Solid', true],
    ]);
  });
});

/**
 * Known-issue matching (#242 review N1): only the exact documented node and failure is known. The
 * entries here are shaped like real ones but local to the test, so the real list can change.
 */
test.describe('known issue matching', () => {
  const toggleIssue: KnownIssue = {
    page: 'explore', rule: 'target-size', target: 'summary', html: 'maplibregl-ctrl-attrib-button',
    check: { messageKey: 'partiallyObscured', width: 24, height: 6, relatedHtml: 'data-testid="map-floating-zoom-out"' },
    reason: 'test',
  };
  const markerIssue: KnownIssue = {
    page: 'explore', rule: 'target-size', target: /^button\[data-facility-id="a11y-facility-[12]"\]$/,
    html: 'data-testid="municipal-facility-marker"',
    check: { messageKey: 'partiallyObscured', relatedHtml: 'data-testid="municipal-facility-marker"' },
    reason: 'test',
  };
  const issues = [toggleIssue, markerIssue, ...KNOWN_ISSUES];
  const toggle = (overrides: Partial<{ width: number; height: number; messageKey: string; related: string }> = {}) =>
    ({
      target: ['summary'],
      html: '<summary class="maplibregl-ctrl-attrib-button" title="Toggle attribution"></summary>',
      any: [
        {
          id: 'target-size',
          data: { messageKey: overrides.messageKey ?? 'partiallyObscured', width: overrides.width ?? 24, height: overrides.height ?? 6 },
          relatedNodes: [{ target: ['button[aria-label="Zoom out"]'], html: overrides.related ?? '<button data-testid="map-floating-zoom-out">' }],
        },
      ],
    }) as AxeNode;
  const marker = (id: string, check: { messageKey?: string; related?: string }) =>
    ({
      target: [`button[data-facility-id="${id}"]`],
      html: `<button data-testid="municipal-facility-marker" data-facility-id="${id}">`,
      any: [
        {
          id: 'target-size',
          data: { messageKey: check.messageKey, width: 40, height: 2 },
          relatedNodes: check.related ? [{ target: ['x'], html: check.related }] : [],
        },
      ],
    }) as AxeNode;
  const known = (node: AxeNode) => issues.some((issue) => matchesKnownIssue(issue, 'target-size', node));

  test('the documented nodes are known', () => {
    expect(known(toggle())).toBe(true);
    expect(known(marker('a11y-facility-1', { messageKey: 'partiallyObscured', related: '<button data-testid="municipal-facility-marker">' }))).toBe(true);
    expect(known({
      target: ['a[href$="maplibre.org/"]'],
      html: '<a href="https://maplibre.org/" target="_blank">MapLibre</a>',
      any: [{ id: 'target-size', data: { width: 55, height: 14 } }],
    } as AxeNode)).toBe(true);
  });

  test('other nodes and worse failures of the same nodes are not', () => {
    // Another <summary> on /explore, and a selector that only contains "summary".
    expect(known({ ...toggle(), target: ['summary:nth-child(2)'] })).toBe(false);
    expect(known({ ...toggle(), target: ['.trip-summary button'] })).toBe(false);
    expect(known({ ...toggle(), html: '<summary class="filters">Filters</summary>' })).toBe(false);
    // The toggle more hidden, or covered by something else.
    expect(known(toggle({ height: 0 }))).toBe(false);
    expect(known(toggle({ related: '<div class="results-sheet">' }))).toBe(false);
    // A marker that is too small on its own, or covered by the zoom rail, not by the other car park.
    expect(known(marker('a11y-facility-1', {}))).toBe(false);
    expect(known(marker('a11y-facility-1', { messageKey: 'partiallyObscured', related: '<button data-testid="map-floating-zoom-out">' }))).toBe(false);
    // A marker the entry does not name, and the rule must match too.
    expect(known(marker('real-facility-9', { messageKey: 'partiallyObscured', related: '<button data-testid="municipal-facility-marker">' }))).toBe(false);
    expect(issues.some((issue) => matchesKnownIssue(issue, 'color-contrast', toggle()))).toBe(false);
  });
});

// Playwright passes fixtures first; this hook needs none.
// eslint-disable-next-line no-empty-pattern
test.afterAll(async ({}, testInfo) => {
  // Entries that no page matched in this run (#242 review N1). The marker overlap depends on timing,
  // so an unseen entry is reported here and in test-results/a11y, not failed.
  const unseen = reportKnownIssueSightings(testInfo, testInfo.project.name);
  if (unseen.length) {
    console.log(`Known issues not seen in this run: ${unseen.map((issue) => `${issue.page} ${issue.rule} ${String(issue.target)}`).join('; ')}`);
  }
});
