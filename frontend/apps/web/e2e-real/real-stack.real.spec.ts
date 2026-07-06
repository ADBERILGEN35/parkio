import { expect, test, type Browser, type Page } from '@playwright/test';
// Seeded-account contract: the PARKIO_REAL_{USER,MODERATOR,ADMIN}_{EMAIL,PASSWORD} pairs
// must point at ACTIVE, email-verified accounts with the matching role. Provision them
// Behavior: when a pair is MISSING the related tests skip; when it is PRESENT but login
// fails, login()'s assertions fail the run (a present-but-broken account is never masked).

const enabled = process.env.PARKIO_REAL_E2E === 'true';
const apiBaseUrl = process.env.PARKIO_REAL_API_BASE_URL ?? 'http://localhost:8080/api/v1';
const refreshCookieName = process.env.PARKIO_REAL_REFRESH_COOKIE_NAME ?? 'parkio_refresh';

const userEmail = process.env.PARKIO_REAL_USER_EMAIL;
const userPassword = process.env.PARKIO_REAL_USER_PASSWORD;
const moderatorEmail = process.env.PARKIO_REAL_MODERATOR_EMAIL;
const moderatorPassword = process.env.PARKIO_REAL_MODERATOR_PASSWORD;
const adminEmail = process.env.PARKIO_REAL_ADMIN_EMAIL;
const adminPassword = process.env.PARKIO_REAL_ADMIN_PASSWORD;
const verificationToken = process.env.PARKIO_REAL_E2E_VERIFICATION_TOKEN;
const emailDomain = process.env.PARKIO_REAL_E2E_EMAIL_DOMAIN ?? 'real-e2e.parkio.local';

test.skip(!enabled, 'Set PARKIO_REAL_E2E=true to run real-stack E2E.');
test.describe.configure({ mode: 'serial' });

let uploadedSpotId: string | null = null;
let uploadedSpotAddress: string | null = null;
const uploadedSpot = {
  latitude: 41.0107,
  longitude: 28.9714,
  address: `Q5 Real Stack Test Spot ${Date.now()}`,
};

test('real gateway exposes health-critical public auth routes', async ({ request }) => {
  const jwks = await request.get(`${apiBaseUrl}/auth/.well-known/jwks.json`);
  expect(jwks.ok()).toBe(true);
  const body = await jwks.json();
  expect(Array.isArray(body.keys)).toBe(true);
  expect(body.keys.length).toBeGreaterThan(0);
});

test('protected routes redirect anonymous users to login', async ({ page }) => {
  await page.goto('/map');
  await expect(page).toHaveURL(/\/login$/);
});

test('registers a real pending account through the gateway', async ({ page }) => {
  const email = `q5-${Date.now()}-${Math.random().toString(16).slice(2)}@${emailDomain}`;
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Q5 Real E2E');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password', { exact: true }).fill('StrongParkio123');
  await page.getByLabel('Confirm password').fill('StrongParkio123');
  await page.getByLabel(/I agree/).check();
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/check-email(?:\?.*)?$/);
  await expect(page.getByRole('heading', { name: /check your email/i })).toBeVisible();
});

test('verifies email when a real verification token is supplied', async ({ page }) => {
  test.skip(
    !verificationToken,
    'PARKIO_REAL_E2E_VERIFICATION_TOKEN is required because production does not expose raw email tokens.',
  );

  await page.goto(`/verify-email?token=${encodeURIComponent(verificationToken as string)}`);
  await expect(page.getByRole('heading', { name: 'Email verified' })).toBeVisible();
});

test('logs in, restores from the HttpOnly refresh cookie, and logs out', async ({ page }) => {
  requireUserAccount();

  await login(page, userEmail as string, userPassword as string);
  await expectRefreshCookie(page);
  await expectNoLegacyTokenStorage(page);

  await page.reload();
  await expect(page.getByLabel('Search location')).toBeVisible();
  await expect(page).toHaveURL(/\/map$/);

  await page.goto('/profile');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login$/);

  await page.goto('/map');
  await expect(page).toHaveURL(/\/login$/);
});

test('refresh bootstrap survives a stale in-memory access token after reload', async ({ page }) => {
  requireUserAccount();

  await login(page, userEmail as string, userPassword as string);
  await expectRefreshCookie(page);
  await page.reload();

  await expect(page.getByLabel('Search location')).toBeVisible();
  await expect(page).toHaveURL(/\/map$/);
});

test('logout-all invalidates another browser session', async ({ browser }) => {
  requireUserAccount();

  const first = await browser.newContext();
  const second = await browser.newContext();
  try {
    const firstPage = await first.newPage();
    const secondPage = await second.newPage();
    await login(firstPage, userEmail as string, userPassword as string);
    await login(secondPage, userEmail as string, userPassword as string);

    firstPage.on('dialog', (dialog) => dialog.accept());
    await firstPage.goto('/profile');
    await firstPage.getByRole('button', { name: 'Log out of all devices' }).click();
    await expect(firstPage).toHaveURL(/\/login$/);

    await secondPage.reload();
    await expect(secondPage).toHaveURL(/\/login$/);
  } finally {
    await first.close();
    await second.close();
  }
});

test('real upload creates a scanned READY media file and a spot', async ({ page }) => {
  requireUserAccount();
  await login(page, userEmail as string, userPassword as string);

  let uploadStatus: string | null = null;
  page.on('response', async (response) => {
    if (!response.url().includes('/media/upload') || response.request().method() !== 'POST') return;
    if (!response.ok()) return;
    const body = await response.json().catch(() => null);
    uploadStatus = typeof body?.status === 'string' ? body.status : null;
  });

  await fillCreateSpotWizard(page, uploadedSpot);
  await page.getByRole('button', { name: 'Upload & create spot' }).click();

  await expect.poll(() => uploadStatus, { message: 'media upload reaches READY' }).toBe('READY');
  await expect(page.getByRole('heading', { name: 'Spot created' })).toBeVisible();
  await expect(page).toHaveURL(/\/spots\/[0-9a-f-]+$/i);
  uploadedSpotId = page.url().split('/spots/')[1] ?? null;
  uploadedSpotAddress = uploadedSpot.address;
  expect(uploadedSpotId).toBeTruthy();
});

test('map search finds the real uploaded spot, marker selection works, and detail opens', async ({ page }) => {
  requireUserAccount();
  test.skip(!uploadedSpotId || !uploadedSpotAddress, 'Upload scenario did not create a spot to search for.');

  await login(page, userEmail as string, userPassword as string);
  await page.goto('/map');
  await page.getByText('Advanced coordinates').click();
  await page.getByLabel('Latitude').fill(String(uploadedSpot.latitude));
  await page.getByLabel('Longitude').fill(String(uploadedSpot.longitude));
  await page.getByLabel('Radius (m, default 1000)').fill('500');
  await page.getByRole('button', { name: 'Search nearby' }).click();

  await expect(page.getByRole('complementary', { name: 'Search results' })).toBeVisible();
  await expect(page.getByRole('link', { name: uploadedSpotAddress as string })).toBeVisible();
  await page.getByRole('link', { name: uploadedSpotAddress as string }).click();
  await expect(page).toHaveURL(new RegExp(`/spots/${uploadedSpotId}$`));
  await expect(page.getByRole('heading', { name: uploadedSpotAddress as string })).toBeVisible();
});

test('USER can create spots but cannot access moderator surfaces', async ({ page }) => {
  requireUserAccount();
  await login(page, userEmail as string, userPassword as string);
  await page.goto('/moderation');
  await expect(page.getByText(/requires a moderator or admin role/i)).toBeVisible();
});

test('MODERATOR can access the moderation queue when a seeded account is supplied', async ({ page }) => {
  test.skip(!moderatorEmail || !moderatorPassword, 'Set PARKIO_REAL_MODERATOR_EMAIL/PASSWORD.');
  await login(page, moderatorEmail as string, moderatorPassword as string);
  await page.goto('/moderation');
  await expect(page.getByRole('heading', { name: 'Moderation' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Cases' })).toBeVisible();
});

test('ADMIN can access admin-only analytics when a seeded account is supplied', async ({ page }) => {
  test.skip(!adminEmail || !adminPassword, 'Set PARKIO_REAL_ADMIN_EMAIL/PASSWORD.');
  await login(page, adminEmail as string, adminPassword as string);
  await page.goto('/analytics');
  await expect(page.getByRole('heading', { name: 'Analytics', level: 1 })).toBeVisible();
  await expect(page.getByText(/Overview/i)).toBeVisible();
});

async function login(page: Page, email: string, password: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/map$/);
  await expect(page.getByLabel('Search location')).toBeVisible();
}

async function expectRefreshCookie(page: Page) {
  const cookies = await page.context().cookies();
  const refreshCookie = cookies.find((cookie) => cookie.name === refreshCookieName);
  expect(refreshCookie, `${refreshCookieName} cookie should exist`).toBeTruthy();
  expect(refreshCookie?.httpOnly).toBe(true);
}

async function expectNoLegacyTokenStorage(page: Page) {
  await expect
    .poll(() =>
      page.evaluate(() => ({
        accessToken: localStorage.getItem('parkio.accessToken'),
        refreshToken: localStorage.getItem('parkio.refreshToken'),
      })),
    )
    .toEqual({ accessToken: null, refreshToken: null });
}

async function fillCreateSpotWizard(
  page: Page,
  spot: { latitude: number; longitude: number; address: string },
) {
  const uniquePng = await createUniquePng(page);
  await page.goto('/upload');
  await page.getByLabel('Spot photo', { exact: true }).setInputFiles({
    name: `q5-real-stack-${Date.now()}.png`,
    mimeType: 'image/png',
    buffer: uniquePng,
  });
  await page.getByRole('button', { name: /next/i }).click();
  await page.getByText('Advanced coordinates').click();
  await page.getByLabel('Latitude').fill(String(spot.latitude));
  await page.getByLabel('Longitude').fill(String(spot.longitude));
  await page.getByLabel('Address (optional)').fill(spot.address);
  await page.getByRole('button', { name: /next/i }).click();
  await page.getByLabel('Description (optional)').fill('Real-stack E2E validation spot.');
  await page.getByText('Sedan', { exact: true }).click();
  await page.getByLabel('Parking context').selectOption('STREET_PARKING');
  await page.getByText('Legal', { exact: true }).click();
  await page.getByRole('button', { name: /next/i }).click();
}

function requireUserAccount() {
  test.skip(!userEmail || !userPassword, 'Set PARKIO_REAL_USER_EMAIL/PASSWORD for authenticated real E2E.');
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function createUniquePng(page: Page): Promise<Buffer> {
  const bytes = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 64;
    canvas.height = 64;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('canvas 2d unavailable');
    ctx.fillStyle = `rgb(${Math.floor(Math.random() * 255)},${Math.floor(Math.random() * 255)},${Math.floor(Math.random() * 255)})`;
    ctx.fillRect(0, 0, 64, 64);
    ctx.fillStyle = '#ffffff';
    ctx.font = '10px sans-serif';
    ctx.fillText(String(Date.now()), 4, 20);
    const dataUrl = canvas.toDataURL('image/png');
    const base64 = dataUrl.split(',')[1] ?? '';
    const binary = atob(base64);
    const arr = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) arr[i] = binary.charCodeAt(i);
    return Array.from(arr);
  });
  return Buffer.from(bytes);
}
