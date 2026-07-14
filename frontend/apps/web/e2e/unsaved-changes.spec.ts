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
  await page.route(/openstreetmap\.org|api\.maptiler\.com|fonts\.(googleapis|gstatic)\.com/, (route) =>
    route.abort(),
  );

  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });

    if (method === 'POST' && path === '/auth/refresh-token') return json(authResponse());
    if (method === 'POST' && path === '/auth/login') return json(authResponse());
    if (method === 'GET' && path === '/auth/me') return json(user);
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/users/me/vehicle') {
      return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    }
    if (method === 'GET' && path === '/parking/spots/nearby') return json([]);
    if (method === 'GET' && path === '/parking/spots/mine') return json([]);
    if (method === 'GET' && path === '/geocoding/search') return json({ results: [] });
    if (method === 'POST' && path === '/media') {
      return json(
        {
          mediaId: '0b8f6c3a-0000-0000-0000-000000000011',
          contentType: 'image/jpeg',
          sizeBytes: 1024,
          createdAt: '2026-06-11T09:00:00Z',
        },
        201,
      );
    }
    if (method === 'POST' && path === '/parking/spots') {
      return json(
        {
          id: '0b8f6c3a-0000-0000-0000-000000000010',
          mediaId: '0b8f6c3a-0000-0000-0000-000000000011',
          ownerUserId: USER_ID,
          latitude: 41.01,
          longitude: 29.02,
          addressText: null,
          description: null,
          manualLocationEdited: true,
          suitableVehicleTypes: ['SEDAN'],
          parkingContext: 'STREET_PARKING',
          legalStatus: 'LEGAL',
          violationReasons: [],
          status: 'ACTIVE',
          confidenceScore: 0,
          verificationCount: 0,
          filledReportCount: 0,
          expiresAt: '2026-06-11T12:00:00Z',
          createdAt: '2026-06-11T09:00:00Z',
          updatedAt: '2026-06-11T09:00:00Z',
        },
        201,
      );
    }
    return json({ code: 'NOT_MOCKED', message: `Unmocked ${method} ${path}` }, 500);
  });
}

async function login(page: Page) {
  await page.goto('/login');
  await page.evaluate(() => localStorage.clear());
  await page.locator('input[autocomplete="email"]').fill(user.email);
  await page.locator('input[autocomplete="current-password"]').fill(PASSWORD);
  await page.getByRole('button', { name: /Giriş yap|Sign in/i }).click();
  await expect(page).toHaveURL(/\/map$/);
}

async function gotoUpload(page: Page) {
  await page.goto('/upload');
  await expect(page.locator('main h1')).toBeVisible();
}

test.describe('Upload unsaved-changes guard', () => {
  test.beforeEach(async ({ page }) => {
    await installMocks(page);
  });

  test('clean wizard navigates away without a warning', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page);
    await gotoUpload(page);
    await page.getByRole('navigation', { name: /Ana menü|Primary/i }).getByRole('link', { name: /Harita|Map/i }).click();
    await expect(page).toHaveURL(/\/map$/);
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('dirty photo blocks MobileNav and cancel preserves progress', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page);
    await gotoUpload(page);

    const fileInput = page.locator('input[type="file"]');
    await fileInput.setInputFiles({
      name: 'spot.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.alloc(2048, 1),
    });
    await expect(page.getByText(/spot\.jpg/i)).toBeVisible();

    await page.getByRole('navigation', { name: /Ana menü|Primary/i }).getByRole('link', { name: /Yerlerim|My Spots/i }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('heading')).toHaveText(/Park yeri paylaşımından çıkılsın mı\?|Leave parking spot sharing\?/i);

    await dialog.getByRole('button', { name: /Paylaşmaya devam et|Continue sharing/i }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page).toHaveURL(/\/upload$/);
    await expect(page.getByText(/spot\.jpg/i)).toBeVisible();
  });

  test('discard navigates to the intended More-menu destination', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page);
    await gotoUpload(page);
    await page.locator('input[type="file"]').setInputFiles({
      name: 'keep.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.alloc(1024, 2),
    });

    await page.getByRole('button', { name: /Daha fazla|More/i }).click();
    await page.locator('#mobile-nav-more a[href="/notifications"]').click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: /Çık ve değişiklikleri sil|Leave and discard changes/i }).click();
    await expect(page).toHaveURL(/\/notifications$/);
  });

  test('desktop nav is blocked while dirty', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    await login(page);
    await gotoUpload(page);
    await page.locator('input[type="file"]').setInputFiles({
      name: 'desk.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.alloc(1024, 3),
    });
    await page.locator('header').getByRole('link', { name: /Profil|Profile/i }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByRole('button', { name: /Paylaşmaya devam et|Continue sharing/i }).click();
    await expect(page).toHaveURL(/\/upload$/);
  });

  test('English dialog copy is shown after locale switch', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await login(page);
    await page.evaluate(() => localStorage.setItem('parkio.locale', 'en'));
    await gotoUpload(page);
    await page.reload();
    await page.locator('input[type="file"]').setInputFiles({
      name: 'en.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.alloc(1024, 4),
    });
    await page.getByRole('navigation', { name: /Primary/i }).getByRole('link', { name: /Map/i }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading')).toHaveText('Leave parking spot sharing?');
    await expect(dialog.getByRole('button', { name: 'Continue sharing' })).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Leave and discard changes' })).toBeVisible();
  });

  test('dialog fits 320px width without document overflow', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 568 });
    await login(page);
    await gotoUpload(page);
    await page.locator('input[type="file"]').setInputFiles({
      name: 'tiny.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.alloc(1024, 5),
    });
    await page.getByRole('navigation', { name: /Ana menü|Primary/i }).getByRole('link', { name: /Harita|Map/i }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    const box = await dialog.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.width).toBeLessThanOrEqual(320);
    const overflow = await page.evaluate(() => {
      const doc = document.documentElement;
      return doc.scrollWidth - doc.clientWidth;
    });
    expect(overflow).toBeLessThanOrEqual(1);
  });

  test('browser Back is blocked while dirty and cancel keeps state', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page);
    // SPA push (not page.goto) so goBack is a React Router POP, not a full reload.
    await page
      .getByRole('navigation', { name: /Ana menü|Primary/i })
      .getByRole('link', { name: /Paylaş|Share/i })
      .click();
    await expect(page).toHaveURL(/\/upload$/);
    await page.locator('input[type="file"]').setInputFiles({
      name: 'back.jpg',
      mimeType: 'image/jpeg',
      buffer: Buffer.alloc(1024, 6),
    });
    await page.goBack();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: /Paylaşmaya devam et|Continue sharing/i }).click();
    await expect(page).toHaveURL(/\/upload$/);
    await expect(page.getByText(/back\.jpg/i)).toBeVisible();
  });
});
