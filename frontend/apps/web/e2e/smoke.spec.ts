import { expect, test, type Page, type Route } from '@playwright/test';

const SPOT_ID = '0b8f6c3a-0000-0000-0000-000000000123';
const MEDIA_ID = '0b8f6c3a-0000-0000-0000-0000000000a1';
const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';
const CASE_ID = '0b8f6c3a-0000-0000-0000-0000000000c1';
const PASSWORD = 'StrongParkio123';
const NEW_PASSWORD = 'NewStrongParkio123';

const user = { id: USER_ID, email: 'tester@parkio.dev', status: 'ACTIVE', roles: ['USER'] };
const moderator = { ...user, email: 'mod@parkio.dev', roles: ['MODERATOR'] };

function authResponse(authUser = user) {
  return {
    accessToken: `access-${authUser.roles[0].toLowerCase()}`,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2999-01-01T00:00:00Z',
    refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
    user: authUser,
  };
}

const registeredResponse = {
  accessToken: null,
  tokenType: 'Bearer',
  accessTokenExpiresAt: null,
  refreshTokenExpiresAt: null,
  user: { ...user, status: 'PENDING_VERIFICATION' },
};

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

const createdSpot = {
  ...publicSpot,
  ownerUserId: USER_ID,
  confidenceScore: 50,
  verificationCount: 0,
  filledReportCount: 0,
};

const openCase = {
  id: CASE_ID,
  targetType: 'PARKING_SPOT',
  targetId: SPOT_ID,
  reason: 'FAKE_PHOTO',
  severity: 'HIGH',
  status: 'OPEN',
  assignedModeratorId: null,
  reportCount: 3,
  resolutionAction: null,
  resolutionNote: null,
  openedAt: '2026-06-11T09:00:00Z',
  updatedAt: '2026-06-11T09:00:00Z',
  resolvedAt: null,
};

const PHOTO_BYTES = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46]);

async function installMockApi(page: Page) {
  let currentUser: typeof user | typeof moderator | null = null;

  await page.route(/openstreetmap\.org|api\.maptiler\.com|fonts\.(googleapis|gstatic)\.com/, (route) =>
    route.abort(),
  );

  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });

    if (method === 'POST' && path === '/auth/refresh-token') {
      if (currentUser) return json(authResponse(currentUser));
      return json({ code: 'INVALID_TOKEN', message: 'No session', traceId: 'e2e-refresh' }, 401);
    }
    if (method === 'POST' && path === '/auth/register') return json(registeredResponse, 201);
    if (method === 'POST' && path === '/auth/verify-email') return json(user);
    if (method === 'POST' && path === '/auth/resend-verification') return json(null);
    if (method === 'POST' && path === '/auth/forgot-password') return json(null);
    if (method === 'POST' && path === '/auth/reset-password') return json(null);
    if (method === 'POST' && path === '/auth/login') {
      const body = request.postDataJSON() as { email?: string };
      currentUser = body.email === moderator.email ? moderator : user;
      return json(authResponse(currentUser));
    }
    if (method === 'POST' && path === '/auth/logout') {
      currentUser = null;
      return json(null);
    }
    if (method === 'GET' && path === '/auth/me') return json(currentUser ?? user);
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/parking/spots/nearby') return json([publicSpot]);
    if (method === 'POST' && path === '/media/upload') {
      return json({ mediaId: MEDIA_ID, status: 'STORED' });
    }
    if (method === 'POST' && path === '/parking/spots') return json(createdSpot, 201);
    if (method === 'GET' && path === `/parking/spots/${SPOT_ID}`) return json(publicSpot);
    if (method === 'POST' && path === `/parking/spots/${SPOT_ID}/claim`) {
      return json({ ...publicSpot, status: 'FILLED' });
    }
    if (method === 'POST' && path === `/parking/spots/${SPOT_ID}/verify`) return json(publicSpot);
    if (method === 'POST' && path === '/moderation/reports') {
      return json({
        id: '0b8f6c3a-0000-0000-0000-0000000000r1',
        targetType: 'PARKING_SPOT',
        targetId: SPOT_ID,
        reason: 'FAKE_PHOTO',
        description: null,
        reporterUserId: USER_ID,
        caseId: CASE_ID,
        createdAt: '2026-06-13T10:00:00Z',
      }, 201);
    }
    if (method === 'GET' && path === `/parking/spots/${SPOT_ID}/media-access-url`) {
      return json({
        spotId: SPOT_ID,
        mediaId: MEDIA_ID,
        accessUrl:
          'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
        expiresAt: '2999-01-01T00:00:00Z',
      });
    }
    if (method === 'GET' && path === '/moderation/cases') return json([openCase]);
    if (method === 'GET' && path === `/moderation/cases/${CASE_ID}`) return json(openCase);
    if (method === 'POST' && path === `/moderation/cases/${CASE_ID}/assign`) {
      return json({ ...openCase, assignedModeratorId: USER_ID, status: 'IN_REVIEW' });
    }
    if (method === 'POST' && path === `/moderation/cases/${CASE_ID}/resolve`) {
      return json({ ...openCase, status: 'RESOLVED', resolutionAction: 'APPROVE' });
    }
    if (method === 'GET' && path === '/moderation/appeals') return json([]);

    return json({ code: 'NOT_MOCKED', message: `Unmocked ${method} ${path}` }, 500);
  });
}

async function login(page: Page, email = user.email) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/map$/);
}

async function spaGoto(page: Page, path: string) {
  await page.evaluate((nextPath) => {
    window.history.pushState({}, '', nextPath);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
  await expect(page).toHaveURL(new RegExp(`${path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`));
}

async function fillCreateSpotWizard(page: Page) {
  await spaGoto(page, '/upload');
  await page.getByLabel('Spot photo').setInputFiles({
    name: 'spot.jpg',
    mimeType: 'image/jpeg',
    buffer: PHOTO_BYTES,
  });
  await page.getByRole('button', { name: /next/i }).click();
  await page.getByText('Advanced coordinates').click();
  await page.getByLabel('Latitude').fill('41.01');
  await page.getByLabel('Longitude').fill('28.97');
  await page.getByRole('button', { name: /next/i }).click();
  await page.getByText('Sedan', { exact: true }).click();
  await page.getByLabel('Parking context').selectOption('STREET_PARKING');
  await page.getByText('Legal', { exact: true }).click();
  await page.getByRole('button', { name: /next/i }).click();
}

test.beforeEach(async ({ page }) => {
  await installMockApi(page);
});

test('auth: register, verify email, then login', async ({ page }) => {
  await page.goto('/register');
  await page.getByLabel('Full name').fill('E2E Tester');
  await page.getByLabel('Email').fill('tester@parkio.dev');
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirm password').fill(PASSWORD);
  await page.getByLabel(/I agree/).check();
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/check-email/);

  await page.goto('/verify-email?token=test-token');
  await expect(page.getByRole('heading', { name: 'Email verified' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByLabel('Email').fill('tester@parkio.dev');
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/map$/);
});

test('auth: forgot password, reset password, then login', async ({ page }) => {
  await page.goto('/forgot-password');
  await page.getByLabel('Email').fill('tester@parkio.dev');
  await page.getByRole('button', { name: 'Send instructions' }).click();
  await expect(
    page.getByRole('main').getByText(/we sent password reset instructions/i),
  ).toBeVisible();

  await page.goto('/reset-password?token=reset-token');
  await page.getByLabel('New password').fill(NEW_PASSWORD);
  await page.getByLabel('Confirm password').fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Reset password' }).click();
  await expect(page).toHaveURL(/\/login/);

  await page.getByLabel('Email').fill('tester@parkio.dev');
  await page.getByLabel('Password').fill(NEW_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/map$/);
});

test('parking: upload image and create spot', async ({ page }) => {
  await login(page);
  await fillCreateSpotWizard(page);
  await page.getByRole('button', { name: 'Upload & create spot' }).click();

  await expect(page.getByRole('heading', { name: 'Spot created' })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/spots/${SPOT_ID}$`));
});

test('parking: open spot details and claim spot', async ({ page }) => {
  await login(page);
  await spaGoto(page, `/spots/${SPOT_ID}`);

  await expect(page.getByRole('heading', { name: '12 Curb Lane' })).toBeVisible();
  await page.getByRole('button', { name: 'Claim this spot' }).click();
  await expect(page.getByRole('main').getByText(/Spot claimed/)).toBeVisible();
});

test('parking: report and verify spot flows', async ({ page }) => {
  await login(page);
  await spaGoto(page, `/spots/${SPOT_ID}`);

  await page.getByLabel('Verify — what did you observe?').selectOption('AVAILABLE');
  await page.getByRole('button', { name: 'Submit verification' }).click();
  await expect(page.getByRole('main').getByText(/Thanks/)).toBeVisible();

  await page.getByLabel('What is wrong with this spot?').selectOption('FAKE_PHOTO');
  await page.getByRole('button', { name: 'Report this spot' }).click();
  await expect(page.getByRole('main').getByText(/Report submitted/)).toBeVisible();
});

test('moderation: moderator opens queue and resolves case', async ({ page }) => {
  await login(page, moderator.email);
  await spaGoto(page, '/moderation');

  await page.getByRole('button', { name: /Fake photo/ }).click();
  await page.getByRole('button', { name: 'Assign to me' }).click();
  await expect(page.getByText(/Case assigned/)).toBeVisible();
  await page.getByLabel('Resolve with action').selectOption('APPROVE');
  await page.getByRole('button', { name: 'Resolve case' }).click();
  await expect(page.getByRole('main').getByText(/Case resolved/)).toBeVisible();
});
