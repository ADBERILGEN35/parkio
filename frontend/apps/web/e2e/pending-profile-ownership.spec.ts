import { expect, test, type BrowserContext, type Page, type Route } from '@playwright/test';

/**
 * CX-F04 / U04 browser acceptance: registration-captured profile fields must only
 * be applied to the account that registered them. All backend traffic is mocked
 * at the network layer with synthetic accounts; nothing leaves the browser.
 */
const PASSWORD = 'StrongParkio123';

const accountA = {
  id: '1a1a1a1a-0000-4000-8000-00000000000a',
  email: 'synthetic-a@parkio.test',
  status: 'ACTIVE',
  roles: ['USER'],
};
const accountB = {
  id: '2b2b2b2b-0000-4000-8000-00000000000b',
  email: 'synthetic-b@parkio.test',
  status: 'ACTIVE',
  roles: ['USER'],
};
type Account = typeof accountA;

interface ProfilePatch {
  body: Record<string, unknown>;
  authorization: string | undefined;
}

function tokenFor(account: Account) {
  return `access-${account.id}`;
}

function authResponse(account: Account) {
  return {
    accessToken: tokenFor(account),
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2999-01-01T00:00:00Z',
    refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
    user: account,
  };
}

/** Context-wide mock so every tab shares one synthetic cookie session. */
async function installMockApi(context: BrowserContext) {
  const state = { cookieSession: null as Account | null, patches: [] as ProfilePatch[] };
  const byEmail = new Map([accountA, accountB].map((a) => [a.email, a]));
  const byToken = new Map([accountA, accountB].map((a) => [`Bearer ${tokenFor(a)}`, a]));

  await context.addInitScript(() => {
    localStorage.setItem('parkio.locale', 'en');
  });
  await context.route(/openstreetmap\.org|api\.maptiler\.com|fonts\.(googleapis|gstatic)\.com/, (route) =>
    route.abort(),
  );
  await context.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const method = request.method();
    const path = new URL(request.url()).pathname.replace(/^\/api\/v1/, '');
    const json = (data: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
    const bearerAccount = byToken.get(request.headers().authorization ?? '');

    if (method === 'POST' && path === '/auth/refresh-token') {
      if (state.cookieSession) return json(authResponse(state.cookieSession));
      return json({ code: 'INVALID_TOKEN', message: 'No session', traceId: 'e2e-refresh' }, 401);
    }
    if (method === 'GET' && path === '/auth/registration-mode') return json({ mode: 'OPEN' });
    if (method === 'POST' && path === '/auth/register') {
      const body = request.postDataJSON() as { email?: string };
      const account = byEmail.get(body.email ?? '');
      if (!account) return json({ code: 'VALIDATION_FAILED', message: 'unknown' }, 400);
      return json(
        {
          accessToken: null,
          tokenType: 'Bearer',
          accessTokenExpiresAt: null,
          refreshTokenExpiresAt: null,
          user: { ...account, status: 'PENDING_VERIFICATION' },
        },
        201,
      );
    }
    if (method === 'POST' && path === '/auth/login') {
      const body = request.postDataJSON() as { email?: string };
      const account = byEmail.get(body.email ?? '');
      if (!account) return json({ code: 'INVALID_CREDENTIALS', message: 'no' }, 401);
      state.cookieSession = account;
      return json(authResponse(account));
    }
    if (method === 'POST' && path === '/auth/logout') {
      state.cookieSession = null;
      return json(null);
    }
    if (method === 'GET' && path === '/auth/me') {
      return bearerAccount ? json(bearerAccount) : json({ code: 'UNAUTHORIZED', message: 'no' }, 401);
    }
    if (method === 'PATCH' && path === '/users/me') {
      state.patches.push({
        body: request.postDataJSON() as Record<string, unknown>,
        authorization: request.headers().authorization,
      });
      return json(request.postDataJSON());
    }
    if (method === 'GET' && path === '/notifications/me') return json([]);
    if (method === 'GET' && path === '/users/me/vehicle') return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    if (method === 'GET' && path === '/parking/spots/nearby') return json([]);
    if (method === 'GET' && path === '/geocoding/search') return json({ results: [] });
    return json({ code: 'NOT_MOCKED', message: `Unmocked ${method} ${path}` }, 500);
  });
  return state;
}

async function register(page: Page, account: Account, phone = '5551234567') {
  await page.goto('/register');
  await page.getByLabel('Full name').fill('Registrant A');
  await page.getByLabel('Email').fill(account.email);
  await page.getByLabel(/Phone number/).fill(phone);
  await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
  await page.getByLabel('Confirm password').fill(PASSWORD);
  await page.getByLabel(/I agree/).check();
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page).toHaveURL(/\/check-email/);
}

/** In-app navigation: keeps the JS realm (and the in-memory phone) alive. */
async function spaGoto(page: Page, path: string) {
  await page.evaluate((nextPath) => {
    window.history.pushState({}, '', nextPath);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

async function login(page: Page, account: Account) {
  await page.getByLabel('Email').fill(account.email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

function pendingStorage(page: Page) {
  return page.evaluate(() => sessionStorage.getItem('parkio.pendingProfile'));
}

test.beforeEach(({}, testInfo) => {
  test.skip(testInfo.project.name !== 'chromium', 'identity handoff is viewport-independent');
});

test('A registers, B signs in in the same tab: no profile PATCH', async ({ context, page }) => {
  const api = await installMockApi(context);
  await register(page, accountA);
  await spaGoto(page, '/login');
  await login(page, accountB);

  await expect(page).toHaveURL(/\/map$/);
  await page.waitForTimeout(500);
  expect(api.patches).toEqual([]);
  expect(await pendingStorage(page)).toBeNull();
});

test('A registers, A signs in: exactly one PATCH with A\'s fields under A\'s bearer', async ({ context, page }) => {
  const api = await installMockApi(context);
  await register(page, accountA);
  await spaGoto(page, '/login');
  await login(page, accountA);

  await expect(page).toHaveURL(/\/map$/);
  await page.waitForTimeout(500);
  expect(api.patches).toEqual([
    {
      body: { displayName: 'Registrant A', phoneNumber: '5551234567' },
      authorization: `Bearer ${tokenFor(accountA)}`,
    },
  ]);
  expect(await pendingStorage(page)).toBeNull();
});

test('after a full reload, B gets neither A\'s name nor a phone prompt; storage never holds the phone', async ({
  context,
  page,
}) => {
  const api = await installMockApi(context);
  await register(page, accountA);
  const stored = await pendingStorage(page);
  expect(stored).not.toContain('5551234567');
  expect(JSON.parse(stored!)).toMatchObject({ ownerUserId: accountA.id });

  await page.goto('/login'); // full reload: in-memory phone is gone
  await login(page, accountB);

  await expect(page).toHaveURL(/\/map$/);
  await page.waitForTimeout(500);
  expect(api.patches).toEqual([]);
  await expect(page.getByText(/phone number was not kept/)).toHaveCount(0);
});

test('after a full reload, A gets the name only and is asked to re-enter the phone', async ({ context, page }) => {
  const api = await installMockApi(context);
  await register(page, accountA);
  await page.goto('/login');
  await login(page, accountA);

  await expect(page.getByText(/phone number was not kept/)).toBeVisible();
  expect(api.patches.map((p) => p.body)).toEqual([{ displayName: 'Registrant A' }]);
});

test('a legacy ownerless pending payload is never applied', async ({ context, page }) => {
  const api = await installMockApi(context);
  await page.goto('/login');
  await page.evaluate(() =>
    sessionStorage.setItem(
      'parkio.pendingProfile',
      JSON.stringify({ displayName: 'Legacy Name', needsPhoneReentry: true }),
    ),
  );
  await login(page, accountA);

  await expect(page).toHaveURL(/\/map$/);
  await page.waitForTimeout(500);
  expect(api.patches).toEqual([]);
  expect(await pendingStorage(page)).toBeNull();
});

test('multi-tab: another tab signs in as B, the registering tab reloads into B and applies nothing', async ({
  context,
  page,
}) => {
  const api = await installMockApi(context);
  await register(page, accountA);

  const other = await context.newPage();
  await other.goto('/login');
  await login(other, accountB);
  await expect(other).toHaveURL(/\/map$/);

  // Registering tab restores the shared cookie session (now B) on a protected route.
  await page.goto('/map');
  await expect(page).toHaveURL(/\/map$/);
  await page.waitForTimeout(500);
  expect(api.patches).toEqual([]);
  expect(await pendingStorage(page)).toBeNull();
});
