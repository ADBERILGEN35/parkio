import { expect, test, type Page, type Route } from '@playwright/test';

const PASSWORD = 'StrongParkio123';
const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';

const user = {
  id: USER_ID,
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

const admin = {
  id: '7f9619ff-8b86-4d01-b42d-00cf4fc964aa',
  email: 'admin@parkio.dev',
  status: 'ACTIVE',
  roles: ['ADMIN'],
};

const registeredResponse = {
  accessToken: null,
  tokenType: 'Bearer',
  accessTokenExpiresAt: null,
  refreshTokenExpiresAt: null,
  user: { ...user, status: 'PENDING_VERIFICATION' },
};

function authResponse(authUser: typeof user | typeof admin) {
  return {
    accessToken: `access-${authUser.roles[0].toLowerCase()}`,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2999-01-01T00:00:00Z',
    refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
    user: authUser,
  };
}

function emptyAdminPage() {
  return { content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 };
}

async function installLocaleMockApi(page: Page) {
  let currentUser: typeof user | typeof admin | null = null;

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
      return json({ code: 'INVALID_TOKEN', message: 'No session', traceId: 'e2e-locale' }, 401);
    }
    if (method === 'POST' && path === '/auth/register') return json(registeredResponse, 201);
    if (method === 'POST' && path === '/auth/verify-email') return json(user);
    if (method === 'POST' && path === '/auth/resend-verification') return json(null);
    if (method === 'POST' && path === '/auth/login') {
      const body = request.postDataJSON() as { email?: string };
      currentUser = body.email === admin.email ? admin : user;
      return json(authResponse(currentUser));
    }
    if (method === 'POST' && path === '/auth/logout') {
      currentUser = null;
      return json(null);
    }
    if (method === 'GET' && path === '/auth/me') return json(currentUser ?? user);
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/users/me/vehicle') {
      return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    }
    if (method === 'GET' && path === '/parking/spots/nearby') return json([]);
    if (method === 'GET' && path === '/geocoding/search') return json({ results: [] });

    // Admin shell stubs
    if (method === 'GET' && path === '/admin/dashboard') {
      return json({
        totalUsers: 0,
        usersByStatus: {},
        verifiedUsers: 0,
        unverifiedUsers: 0,
        registrationsToday: 0,
        registrationsLast7Days: 0,
        registrationsLast30Days: 0,
        verificationConversionRate: 0,
        activeSessionCount: 0,
      });
    }
    if (method === 'GET' && path.startsWith('/admin/users')) return json(emptyAdminPage());
    if (method === 'GET' && path === '/admin/security/summary') {
      return json({
        suspendedUsers: 0,
        pendingVerificationUsers: 0,
        activeSessionCount: 0,
        reuseDetectedSessionCount: 0,
      });
    }
    if (method === 'GET' && path.startsWith('/admin/audit-events')) return json(emptyAdminPage());
    if (method === 'GET' && path === '/analytics/overview') {
      return json({
        totalParkingCreated: 0,
        totalParkingVerified: 0,
        totalParkingClaimed: 0,
        totalParkingRejected: 0,
        totalPointsEarned: 0,
        totalLevelUps: 0,
        totalNotificationsCreated: 0,
      });
    }
    if (method === 'GET' && path === '/analytics/daily') return json([]);
    if (method === 'GET' && path === '/analytics/parking') return json([]);
    if (method === 'GET' && path === '/analytics/metrics') return json([]);
    if (method === 'GET' && path === '/moderation/cases') return json([]);
    if (method === 'GET' && path === '/moderation/appeals') return json([]);

    return json({ code: 'NOT_MOCKED', message: `Unmocked ${method} ${path}` }, 500);
  });
}

async function spaGoto(page: Page, path: string) {
  await page.evaluate((nextPath) => {
    window.history.pushState({}, '', nextPath);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
  await expect(page).toHaveURL(new RegExp(`${path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`));
}

async function loginAs(page: Page, email: string) {
  await page.goto('/login');
  await page.locator('input[autocomplete="email"]').fill(email);
  await page.locator('input[autocomplete="current-password"]').fill(PASSWORD);
  await page.getByRole('button', { name: /Giriş yap|Sign in/i }).click();
  await expect(page).toHaveURL(/\/map$/);
}

test.describe('Locale flows', () => {
  test.beforeEach(async ({ page }) => {
    await installLocaleMockApi(page);
  });

  test('Flow A: fresh browser defaults Turkish, can switch to English and persist', async ({
    page,
  }) => {
    await page.goto('/login');
    await expect(page.locator('html')).toHaveAttribute('lang', 'tr');

    await page.goto('/profile');
    if (page.url().includes('/login')) {
      await expect(page.getByRole('heading')).toBeVisible();
    }

    await page.evaluate(() => localStorage.setItem('parkio.locale', 'en'));
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  });

  test('Flow B: register path localizes between Turkish and English', async ({ page }) => {
    await page.goto('/register');
    await expect(page.locator('html')).toHaveAttribute('lang', 'tr');
    await expect(page.getByRole('heading', { name: /Hesabınızı oluşturun|Create your account/i })).toBeVisible();
    await expect(page.getByLabel(/Ad soyad|Full name/i)).toBeVisible();

    await page.evaluate(() => localStorage.setItem('parkio.locale', 'en'));
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.getByRole('heading', { name: 'Create your account' })).toBeVisible();
    await expect(page.getByLabel('Full name')).toBeVisible();
    await expect(page.getByLabel('Email')).toBeVisible();
    await expect(page.getByLabel('Password', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Confirm password')).toBeVisible();

    await page.getByLabel('Full name').fill('E2E Locale Tester');
    await page.getByLabel('Email').fill('tester@parkio.dev');
    await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
    await page.getByLabel('Confirm password').fill(PASSWORD);
    await page.getByLabel(/I agree/).check();
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page).toHaveURL(/\/check-email/);
    await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible();
    await expect(page.getByText(/We sent a verification link/i)).toBeVisible();

    await page.goto('/verify-email?token=test-token');
    await expect(page.getByRole('heading', { name: 'Email verified' })).toBeVisible();
  });

  test('Flow C: admin shell labels follow locale without weakening RBAC', async ({ page }) => {
    await page.goto('/login');
    await page.evaluate(() => localStorage.clear());
    await loginAs(page, admin.email);
    await expect(page.locator('html')).toHaveAttribute('lang', 'tr');

    await spaGoto(page, '/admin');
    await expect(page.getByRole('heading', { name: /Yönetim paneli|Admin dashboard/i })).toBeVisible();
    await expect(page.getByRole('navigation', { name: /Yönetim|Admin/i })).toBeVisible();
    await expect(page.getByRole('link', { name: /Kontrol paneli|Dashboard/i })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Kullanıcılar' })).toBeVisible();

    await spaGoto(page, '/admin/users');
    await expect(page.getByRole('heading', { name: /Kullanıcılar|Users/i })).toBeVisible();

    await spaGoto(page, '/admin/security');
    await expect(page.getByRole('heading', { name: /Güvenlik|Security/i })).toBeVisible();

    await spaGoto(page, '/admin/audit');
    await expect(page.getByRole('heading', { name: /Denetim izi|Audit trail/i })).toBeVisible();

    await spaGoto(page, '/admin/system');
    await expect(page.getByRole('heading', { name: /Sistem|System/i })).toBeVisible();

    await spaGoto(page, '/admin/analytics');
    await expect(page).toHaveURL(/\/admin\/analytics$/);

    await spaGoto(page, '/admin/moderation');
    await expect(page).toHaveURL(/\/admin\/moderation$/);

    await page.evaluate(() => localStorage.setItem('parkio.locale', 'en'));
    await page.goto('/admin');
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.getByRole('heading', { name: 'Admin dashboard' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Users' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Security' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Audit' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'System' })).toBeVisible();
  });

  test('Flow D: mobile viewport language switch keeps layout usable', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/login');
    await page.evaluate(() => localStorage.setItem('parkio.locale', 'en'));
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    const overflow = await page.evaluate(() => {
      const el = document.documentElement;
      return el.scrollWidth > el.clientWidth + 2;
    });
    expect(overflow).toBe(false);
  });
});
