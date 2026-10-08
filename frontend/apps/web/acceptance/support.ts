import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { E2E_PASSWORD } from '../e2e/fixtures/credentials';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type Page, type Route, type TestInfo } from '@playwright/test';

/**
 * Shared helpers for the CX-F11 web acceptance. Every API call is answered here: the persona
 * signed in, a request log for one-request assertions, and fault injection (a dropped connection,
 * a delay, a server error) per "METHOD /path". Requests to any other host are aborted.
 */
export type Locale = 'tr' | 'en';

const LOCALES = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'src', 'i18n', 'locales');
const translations = new Map<string, unknown>();

/** The app's own translation, so the Turkish and English runs use the strings users see. */
export function t(locale: Locale, namespace: string, key: string): string {
  const file = path.join(LOCALES, locale, `${namespace}.json`);
  if (!translations.has(file)) translations.set(file, JSON.parse(readFileSync(file, 'utf8')));
  const value = key
    .split('.')
    .reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], translations.get(file));
  if (typeof value !== 'string') throw new Error(`missing translation ${locale}/${namespace}:${key}`);
  return value;
}

export function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export const PASSWORD = E2E_PASSWORD;

export interface Persona {
  id: string;
  email: string;
  status: string;
  roles: string[];
}

export const member: Persona = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc96411',
  email: 'cxf11-member@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

export const admin: Persona = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc96422',
  email: 'cxf11-admin@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER', 'ADMIN'],
};

export const SPOT_ID = '0b8f6c3a-0000-0000-0000-00000000cf11';
const MEDIA_ID = '0b8f6c3a-0000-0000-0000-0000000cf1a1';

export interface MockApi {
  /** Every API call, in order, as "METHOD /path". */
  requests: string[];
  /** Calls answered 404 because no mock exists for them. */
  unmocked: string[];
  /** Calls whose next attempt fails like a dropped connection. */
  dropOnce: Set<string>;
  /** Calls that keep failing with HTTP 503 until removed. */
  unavailable: Set<string>;
  /** Calls answered after a delay, in milliseconds. */
  delays: Map<string, number>;
  signedIn: Persona | null;
  /** The anonymous Explore discovery answer. */
  exploreDiscovery: unknown;
  count(call: string): number;
}

/** One reviewed municipal facility in Istanbul, as public Explore lists it. */
export const EXPLORE_FACILITY = {
  id: 'cxf11-facility-1',
  displayName: 'CX-F11 Test Car Park',
  operatorName: 'Synthetic Operator',
  facilityType: 'OFF_STREET',
  addressText: '1 Synthetic Street, Istanbul',
  latitude: 41.0082,
  longitude: 28.9784,
  capacityTotal: 120,
  availableSpaces: 37,
  availabilityFreshness: 'LIVE',
  dataUpdatedAt: '2026-10-04T08:00:00Z',
  sourceLabel: 'Synthetic source',
  attribution: 'Synthetic data for acceptance tests',
  accessClassification: 'PUBLIC',
};

function session(persona: Persona) {
  return {
    accessToken: `access-${persona.id}`,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2999-01-01T00:00:00Z',
    refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
    user: persona,
  };
}

const spot = {
  id: SPOT_ID,
  mediaId: MEDIA_ID,
  latitude: 41.01,
  longitude: 28.97,
  addressText: '12 Curb Lane',
  description: 'Shaded street spot',
  manualLocationEdited: true,
  suitableVehicleTypes: ['SEDAN'],
  parkingContext: 'STREET_PARKING',
  legalStatus: 'LEGAL',
  violationReasons: [],
  status: 'ACTIVE',
  expiresAt: '2999-01-01T00:00:00Z',
  createdAt: '2026-10-04T08:00:00Z',
  updatedAt: '2026-10-04T08:00:00Z',
};

export async function installMockApi(
  page: Page,
  locale: Locale,
  options: { signedIn?: Persona | null } = {},
): Promise<MockApi> {
  const api: MockApi = {
    requests: [],
    unmocked: [],
    dropOnce: new Set(),
    unavailable: new Set(),
    delays: new Map(),
    signedIn: options.signedIn ?? null,
    exploreDiscovery: {
      facilities: [EXPLORE_FACILITY],
      municipalTotalInScope: 1,
      municipalHiddenCount: 0,
      communitySpotCountInScope: null,
    },
    count(call) {
      return this.requests.filter((request) => request === call).length;
    },
  };
  await page.addInitScript((value) => {
    localStorage.setItem('parkio.locale', value);
    localStorage.setItem('parkio.marketing.locale', value);
  }, locale);
  // Registered first, so the routes below take precedence: only the API mock answers, everything
  // else that leaves the machine (map tiles, fonts, a built image's real API origin) is aborted.
  await page.route(
    (url) => url.hostname !== 'localhost' && url.hostname !== '127.0.0.1' && !/\/api\/v1\//.test(url.pathname),
    (route) => route.abort('internetdisconnected'),
  );
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const apiPath = new URL(request.url()).pathname.replace(/^.*\/api\/v1/, '');
    const call = `${method} ${apiPath}`;
    api.requests.push(call);
    if (api.dropOnce.delete(call)) return route.abort('internetdisconnected');
    const delay = api.delays.get(call);
    if (delay) await new Promise((resolve) => setTimeout(resolve, delay));
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    if (api.unavailable.has(call)) {
      return json({ code: 'SERVICE_UNAVAILABLE', message: 'Synthetic outage', traceId: 'cxf11' }, 503);
    }
    const persona = api.signedIn;

    if (method === 'POST' && apiPath === '/auth/refresh-token') {
      return persona ? json(session(persona)) : json({ code: 'INVALID_TOKEN', message: 'No session', traceId: 'cxf11' }, 401);
    }
    if (method === 'GET' && apiPath === '/auth/registration-mode') return json({ mode: 'OPEN' });
    if (method === 'POST' && apiPath === '/auth/register') {
      return json({ ...session(member), accessToken: null, user: { ...member, status: 'PENDING_VERIFICATION' } }, 201);
    }
    if (method === 'POST' && apiPath === '/auth/verify-email') return json(member);
    if (method === 'POST' && apiPath === '/auth/resend-verification') return json(null);
    if (method === 'POST' && apiPath === '/auth/login') {
      const body = request.postDataJSON() as { email?: string };
      api.signedIn = body.email === admin.email ? admin : member;
      return json(session(api.signedIn));
    }
    if (method === 'POST' && apiPath === '/auth/logout') {
      api.signedIn = null;
      return json(null);
    }
    if (method === 'GET' && apiPath === '/public/explore/facilities') return json(api.exploreDiscovery);
    if (!persona) return json({ code: 'INVALID_TOKEN', message: 'Sign in first', traceId: 'cxf11' }, 401);

    if (method === 'GET' && apiPath === '/auth/me') return json(persona);
    if (method === 'GET' && apiPath === '/users/me') {
      return json({
        id: persona.id,
        authUserId: persona.id,
        email: persona.email,
        displayName: 'CX F11 Tester',
        phoneNumber: null,
        city: 'Istanbul',
        status: 'ACTIVE',
        createdAt: '2026-10-01T08:00:00Z',
      });
    }
    if (method === 'PATCH' && apiPath === '/users/me') return json(request.postDataJSON());
    if (method === 'GET' && apiPath === '/users/me/stats') {
      return json({ trustScore: 72, trustBand: 'MEDIUM_TRUST', totalPoints: 340, currentLevel: 3 });
    }
    if (method === 'GET' && apiPath === '/users/me/preferences') {
      return json({ preferredRadiusMeters: 800, notificationsEnabled: true, preferredLocale: locale });
    }
    if (method === 'GET' && apiPath === '/users/me/vehicle') return json({ vehicleType: 'SEDAN', plate: '34CXF11' });
    if (method === 'GET' && apiPath === '/notifications/me') return json([]);
    if (method === 'GET' && apiPath === '/parking/sessions/active') return route.fulfill({ status: 204 });
    if (method === 'GET' && apiPath === '/parking/spots/nearby') return json([]);
    if (method === 'GET' && apiPath === '/parking/facilities/nearby') return json([]);
    if (method === 'GET' && apiPath === '/geocoding/search') return json({ results: [] });
    if (method === 'POST' && apiPath === '/media/upload') return json({ mediaId: MEDIA_ID, status: 'STORED' });
    if (method === 'POST' && apiPath === '/parking/spots') {
      return json({ ...spot, ownerUserId: persona.id, confidenceScore: 50, verificationCount: 0, filledReportCount: 0 }, 201);
    }
    if (method === 'GET' && apiPath === `/parking/spots/${SPOT_ID}`) return json(spot);

    api.unmocked.push(call);
    return json({ code: 'NOT_FOUND', message: `Not mocked: ${call}`, traceId: 'cxf11' }, 404);
  });
  return api;
}

/**
 * Waits until the app has rendered a page: an empty #root passes every "is absent" assertion, and a
 * cold Vite dev server can take tens of seconds for the first load. A built image is fast.
 */
export async function waitForApp(page: Page) {
  await expect(page.locator('#root > *').first()).toBeAttached({ timeout: 90_000 });
  await expect(page.locator('main, [role="main"], h1').first()).toBeAttached({ timeout: 30_000 });
}

/** Same-document navigation, as an in-app link does it. */
export async function spaGoto(page: Page, target: string) {
  await page.evaluate((next) => {
    window.history.pushState({}, '', next);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, target);
}

/** Saves a screenshot as evidence (attachment and test-results/cxf11/). */
export async function snap(page: Page, testInfo: TestInfo, name: string) {
  const body = await page.screenshot({ fullPage: true });
  const dir = path.join(testInfo.config.rootDir, '..', 'test-results', 'cxf11');
  mkdirSync(dir, { recursive: true });
  const file = `${testInfo.titlePath.slice(1).join(' ').replace(/[^\w.-]+/g, '-')}-${name}.png`;
  writeFileSync(path.join(dir, file), body);
  await testInfo.attach(name, { body, contentType: 'image/png' });
}

export const PHOTO_BYTES = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46]);
