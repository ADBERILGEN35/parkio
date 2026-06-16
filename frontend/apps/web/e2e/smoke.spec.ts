import { expect, test, type Route } from '@playwright/test';

/**
 * Single browser-level smoke flow:
 *   /login → mocked login → /map → mocked nearby search →
 *   /upload → mocked media upload + create spot → redirect to /spots/:id.
 *
 * Every backend call is fulfilled from fixtures via `page.route` — no real
 * services, fully deterministic, offline (map tiles and fonts are aborted).
 */

const SPOT_ID = '0b8f6c3a-0000-0000-0000-000000000123';
const MEDIA_ID = '0b8f6c3a-0000-0000-0000-0000000000a1';
const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';

const user = { id: USER_ID, email: 'tester@parkio.dev', status: 'ACTIVE', roles: ['USER'] };

const authResponse = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  accessTokenExpiresAt: '2999-01-01T00:00:00Z',
  refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
  user,
};

// PublicSpot returned by nearby search and the spot-detail GET.
const publicSpot = {
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
  createdAt: '2026-06-13T10:00:00Z',
  updatedAt: '2026-06-13T10:00:00Z',
};

// Owner SpotResponse returned by create.
const createdSpot = {
  ...publicSpot,
  ownerUserId: USER_ID,
  confidenceScore: 50,
  verificationCount: 0,
  filledReportCount: 0,
};

// 1x1 transparent PNG-ish bytes — only needs size > 0 and an image mime type.
const PHOTO_BYTES = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46]);

test('happy-path: login, search, upload & create a spot', async ({ page }) => {
  // Keep the run offline & deterministic — drop external tiles/fonts.
  await page.route(/openstreetmap\.org|fonts\.(googleapis|gstatic)\.com/, (route) => route.abort());

  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });

    if (method === 'POST' && path === '/auth/login') return json(authResponse);
    if (method === 'GET' && path === '/auth/me') return json(user);
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/parking/spots/nearby') return json([publicSpot]);
    if (method === 'POST' && path === '/media/upload') {
      return json({ mediaId: MEDIA_ID, status: 'STORED' });
    }
    if (method === 'POST' && path === '/parking/spots') return json(createdSpot, 201);
    if (method === 'GET' && path === `/parking/spots/${SPOT_ID}`) return json(publicSpot);
    if (method === 'GET' && path === `/parking/spots/${SPOT_ID}/media-access-url`) {
      return json({
        spotId: SPOT_ID,
        mediaId: MEDIA_ID,
        accessUrl: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
        expiresAt: '2999-01-01T00:00:00Z',
      });
    }
    // Anything unexpected fails loudly so the test can't silently pass.
    return json({ code: 'NOT_MOCKED', message: `Unmocked ${method} ${path}` }, 500);
  });

  // 1) Login.
  await page.goto('/login');
  await page.getByLabel('Email').fill('tester@parkio.dev');
  await page.getByLabel('Password').fill('password-1');
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/map$/);

  // 2) Search nearby (manual coordinates → one result).
  await page.getByLabel('Latitude').fill('41.01');
  await page.getByLabel('Longitude').fill('28.97');
  await page.getByRole('button', { name: 'Search nearby' }).click();
  await expect(page.getByText('1 spot nearby')).toBeVisible();

  // 3) Open the upload flow from the nav.
  await page.getByRole('link', { name: 'Share a spot' }).click();
  await expect(page).toHaveURL(/\/upload$/);

  // 4) Fill the create-spot form.
  await page.getByLabel('Spot photo').setInputFiles({
    name: 'spot.jpg',
    mimeType: 'image/jpeg',
    buffer: PHOTO_BYTES,
  });
  await page.getByLabel('Latitude').fill('41.01');
  await page.getByLabel('Longitude').fill('28.97');
  await page.getByText('Sedan', { exact: true }).click();
  await page.getByLabel('Parking context').selectOption('STREET_PARKING');
  await page.getByText('Legal', { exact: true }).click();
  await page.getByRole('button', { name: 'Upload & create spot' }).click();

  // 5) Success confirmation, then auto-redirect to the new spot.
  await expect(page.getByRole('heading', { name: 'Spot created' })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/spots/${SPOT_ID}$`));

  // 6) Spot detail renders from its own mocked endpoints.
  await expect(page.getByRole('heading', { name: '12 Curb Lane' })).toBeVisible();
});
