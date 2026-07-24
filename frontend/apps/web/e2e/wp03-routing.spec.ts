import { expect, test, type Page, type Route } from '@playwright/test';

const PASSWORD = 'StrongParkio123';
const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';
const MODERATOR_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc96500';
const ADMIN_ID = '7f9619ff-8b86-4d01-b42d-00cf4fc964aa';
const SPOT_ID = '0b8f6c3a-0000-0000-0000-000000000123';

type TestRole = 'USER' | 'MODERATOR' | 'ADMIN';
type BootstrapOutcome =
  | 'active'
  | 'anonymous'
  | 'account-not-active'
  | 'account-not-verified';

interface TestUser {
  readonly id: string;
  readonly email: string;
  readonly status: 'ACTIVE';
  readonly roles: readonly TestRole[];
}

interface MockOptions {
  readonly bootstrap?: BootstrapOutcome;
  readonly deferBootstrap?: boolean;
  readonly profileReady?: boolean;
  readonly user?: TestUser;
}

const users = {
  user: {
    id: USER_ID,
    email: 'user@parkio.dev',
    status: 'ACTIVE',
    roles: ['USER'],
  },
  moderator: {
    id: MODERATOR_ID,
    email: 'moderator@parkio.dev',
    status: 'ACTIVE',
    roles: ['MODERATOR'],
  },
  admin: {
    id: ADMIN_ID,
    email: 'admin@parkio.dev',
    status: 'ACTIVE',
    roles: ['ADMIN'],
  },
} as const satisfies Record<string, TestUser>;

const publicSpot = {
  id: SPOT_ID,
  mediaId: null,
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

function authResponse(user: TestUser) {
  return {
    accessToken: `access-${user.roles[0].toLowerCase()}`,
    tokenType: 'Bearer',
    accessTokenExpiresAt: '2999-01-01T00:00:00Z',
    refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
    user,
  };
}

function emptyAdminPage() {
  return {
    content: [],
    page: 0,
    size: 25,
    totalElements: 0,
    totalPages: 0,
  };
}


/** Allowed request categories for focused WP-03 hermetic acceptance. */
const WP03_ALLOWED_REQUEST_CATEGORIES = [
  'local-vite-origin',
  'mocked-frontend-api',
  'deterministic-external-asset-stub',
] as const;

const TINY_JPEG = Buffer.from(
  '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAn/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIQAxAAAAGcP//EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAQUCf//EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQMBAT8Bf//EABQRAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQIBAT8Bf//Z',
  'base64',
);

function isLocalViteOrigin(url: URL, baseURL: string): boolean {
  const base = new URL(baseURL);
  return url.origin === base.origin;
}

function isMockedFrontendApi(url: URL): boolean {
  return url.pathname.includes('/api/v1/');
}

function isDeterministicExternalAssetHost(hostname: string): boolean {
  return (
    hostname === 'images.unsplash.com' ||
    hostname.endsWith('.openstreetmap.org') ||
    hostname === 'api.maptiler.com' ||
    hostname === 'fonts.googleapis.com' ||
    hostname === 'fonts.gstatic.com'
  );
}

async function installHermeticNetworkGuard(page: Page) {
  const unexpected: string[] = [];
  await page.route('**/*', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const baseURL = 'http://localhost:5193';

    if (isLocalViteOrigin(url, baseURL)) {
      return route.continue();
    }
    if (isMockedFrontendApi(url)) {
      // Handled by the more-specific **/api/v1/** route registered afterward.
      return route.fallback();
    }
    if (isDeterministicExternalAssetHost(url.hostname)) {
      if (url.hostname === 'images.unsplash.com') {
        return route.fulfill({
          status: 200,
          contentType: 'image/jpeg',
          body: TINY_JPEG,
        });
      }
      return route.abort();
    }

    unexpected.push(`${request.method()} ${url.href}`);
    return route.abort();
  });
  return {
    assertNoUnexpectedExternalRequests() {
      expect(
        unexpected,
        `Unexpected external requests (allowed: ${WP03_ALLOWED_REQUEST_CATEGORIES.join(', ')}): ${unexpected.join('; ')}`,
      ).toEqual([]);
    },
  };
}

class MockWp03Backend {
  private bootstrapGate:
    | {
        readonly promise: Promise<void>;
        readonly release: () => void;
      }
    | undefined;

  private currentUser: TestUser | null;
  private profileReady: boolean;
  bootstrap: BootstrapOutcome;
  refreshCount = 0;

  constructor(options: MockOptions) {
    this.bootstrap = options.bootstrap ?? 'active';
    this.currentUser = options.user ?? users.user;
    this.profileReady = options.profileReady ?? true;
    if (options.deferBootstrap) {
      let release = () => {};
      const promise = new Promise<void>((resolve) => {
        release = resolve;
      });
      this.bootstrapGate = { promise, release };
    }
  }

  setBootstrap(outcome: BootstrapOutcome) {
    this.bootstrap = outcome;
  }

  setProfileReady(ready: boolean) {
    this.profileReady = ready;
  }

  releaseBootstrap() {
    this.bootstrapGate?.release();
  }

  private selectedLoginUser(email?: string): TestUser {
    return Object.values(users).find((user) => user.email === email) ?? users.user;
  }

  async install(page: Page) {
    await page.addInitScript(() => {
      localStorage.setItem('parkio.locale', 'en');
    });
    await page.route('**/api/v1/**', async (route: Route) => {
      const request = route.request();
      const method = request.method();
      const path = new URL(request.url()).pathname.replace(
        /^\/api\/v1/,
        '',
      );
      const json = (data: unknown, status = 200) =>
        route.fulfill({
          status,
          contentType: 'application/json',
          body: JSON.stringify(data),
        });

      if (method === 'POST' && path === '/auth/refresh-token') {
        this.refreshCount += 1;
        await this.bootstrapGate?.promise;
        if (this.bootstrap === 'active' && this.currentUser) {
          return json(authResponse(this.currentUser));
        }
        if (this.bootstrap === 'account-not-active') {
          return json(
            {
              code: 'ACCOUNT_NOT_ACTIVE',
              message: 'Account is not active',
              traceId: 'wp03-account-not-active',
              timestamp: '2026-07-23T09:00:00Z',
            },
            403,
          );
        }
        if (this.bootstrap === 'account-not-verified') {
          return json(
            {
              code: 'ACCOUNT_NOT_VERIFIED',
              message: 'Account is not verified',
              traceId: 'wp03-account-not-verified',
              timestamp: '2026-07-23T09:00:00Z',
            },
            403,
          );
        }
        return json(
          {
            code: 'INVALID_TOKEN',
            message: 'No session',
            traceId: 'wp03-anonymous',
            timestamp: '2026-07-23T09:00:00Z',
          },
          401,
        );
      }
      if (method === 'POST' && path === '/auth/login') {
        const body = request.postDataJSON() as { email?: string };
        this.currentUser = this.selectedLoginUser(body.email);
        return json(authResponse(this.currentUser));
      }
      if (method === 'POST' && path === '/auth/logout') {
        this.currentUser = null;
        return json(null);
      }
      if (method === 'GET' && path === '/auth/me') {
        if (!this.profileReady) {
          return json(
            {
              code: 'ACCOUNT_NOT_ACTIVE',
              message: 'Profile is still provisioning',
              traceId: 'wp03-profile-provisioning',
              timestamp: '2026-07-23T09:00:00Z',
            },
            403,
          );
        }
        return json(this.currentUser ?? users.user);
      }
      if (method === 'GET' && path === '/notifications/me') {
        return json([]);
      }
      if (method === 'GET' && path === '/users/me/vehicle') {
        return json({ vehicleType: 'SEDAN', plate: '35PK123' });
      }
      if (method === 'GET' && path === '/users/me') {
        return json({
          id: USER_ID,
          authUserId: USER_ID,
          email: this.currentUser?.email ?? users.user.email,
          displayName: 'WP-03 Driver',
          phoneNumber: null,
          city: 'Istanbul',
          status: 'ACTIVE',
          createdAt: '2026-01-01T09:00:00Z',
        });
      }
      if (method === 'GET' && path === '/users/me/stats') {
        return json({
          trustScore: 72,
          trustBand: 'HIGH_TRUST',
          totalPoints: 10,
          currentLevel: 1,
        });
      }
      if (method === 'GET' && path === '/users/me/preferences') {
        return json({
          preferredRadiusMeters: 1500,
          notificationsEnabled: true,
          preferredLocale: null,
        });
      }
      if (method === 'PATCH' && path === '/users/me/preferences') {
        return json(request.postDataJSON());
      }
      if (method === 'PATCH' && path === '/users/me') {
        return json(request.postDataJSON());
      }
      if (method === 'GET' && path === '/parking/spots/nearby') {
        return json([]);
      }
      if (method === 'GET' && path === '/parking/spots/mine') {
        return json([]);
      }
      if (method === 'GET' && path === `/parking/spots/${SPOT_ID}`) {
        return json(publicSpot);
      }
      if (method === 'GET' && path === '/geocoding/search') {
        return json({ results: [] });
      }
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
      if (
        method === 'GET' &&
        path === `/admin/users/${USER_ID}`
      ) {
        return json({
          user: {
            id: USER_ID,
            email: users.user.email,
            status: 'ACTIVE',
            emailVerified: true,
            roles: ['USER'],
            createdAt: '2026-01-01T09:00:00Z',
            activeSessionCount: 0,
          },
          sessions: [],
          recentAuditEvents: [],
        });
      }
      if (method === 'GET' && path.startsWith('/admin/users')) {
        return json(emptyAdminPage());
      }
      if (method === 'GET' && path === '/admin/security/summary') {
        return json({
          suspendedUsers: 0,
          pendingVerificationUsers: 0,
          activeSessionCount: 0,
          reuseDetectedSessionCount: 0,
        });
      }
      if (
        method === 'GET' &&
        path.startsWith('/admin/audit-events')
      ) {
        return json(emptyAdminPage());
      }
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
      if (
        method === 'GET' &&
        ['/analytics/daily', '/analytics/parking', '/analytics/metrics'].includes(
          path,
        )
      ) {
        return json([]);
      }
      if (
        method === 'GET' &&
        ['/moderation/cases', '/moderation/appeals'].includes(path)
      ) {
        return json([]);
      }

      return json(
        {
          code: 'NOT_MOCKED',
          message: `Unmocked ${method} ${path}`,
        },
        500,
      );
    });
  }
}

type Wp03NetworkGuard = Awaited<ReturnType<typeof installHermeticNetworkGuard>>;

const networkByPage = new WeakMap<Page, Wp03NetworkGuard>();

async function installMockApi(page: Page, options: MockOptions = {}) {
  const network = await installHermeticNetworkGuard(page);
  networkByPage.set(page, network);
  const backend = new MockWp03Backend(options);
  await backend.install(page);
  return Object.assign(backend, { network });
}

function assertHermeticNetwork(page: Page) {
  networkByPage.get(page)?.assertNoUnexpectedExternalRequests();
}

async function spaGoto(page: Page, path: string) {
  await page.evaluate((nextPath) => {
    window.history.pushState({}, '', nextPath);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

/** Same-origin link click so React Router data-router + useBlocker observe the transition. */

async function callRuntimeAuth(
  page: Page,
  action: 'beginProvisioning' | 'markAccountNotVerified',
) {
  await page.evaluate((nextAction) => {
    const root = document.getElementById('root');
    if (!root) {
      throw new Error('Missing #root');
    }
    const containerKey = Object.keys(root).find((key) =>
      key.startsWith('__reactContainer'),
    );
    const host = containerKey
      ? (root as unknown as Record<
          string,
          {
            child?: unknown;
            stateNode?: { current?: unknown };
            current?: unknown;
          }
        >)[containerKey]
      : undefined;
    const startFiber =
      (host && 'child' in host && host.child ? host : undefined) ??
      host?.stateNode?.current ??
      host?.current ??
      host;

    type Fiber = {
      memoizedProps?: Record<string, unknown>;
      pendingProps?: Record<string, unknown>;
      child?: Fiber | null;
      sibling?: Fiber | null;
    };

    const seen = new Set<Fiber>();
    const queue: Fiber[] = startFiber ? [startFiber as Fiber] : [];
    while (queue.length > 0) {
      const node = queue.shift();
      if (!node || seen.has(node)) {
        continue;
      }
      seen.add(node);
      const props = {
        ...(node.pendingProps ?? {}),
        ...(node.memoizedProps ?? {}),
      };
      for (const bag of [props.runtime, props.value, props]) {
        const record = bag as {
          authStore?: {
            getState: () => {
              beginProvisioning?: () => void;
              markAccountRestricted?: (code: string) => void;
            };
          };
        } | null;
        const authStore = record?.authStore;
        if (authStore && typeof authStore.getState === 'function') {
          const state = authStore.getState();
          if (nextAction === 'beginProvisioning') {
            state.beginProvisioning?.();
          } else {
            state.markAccountRestricted?.('ACCOUNT_NOT_VERIFIED');
          }
          return true;
        }
      }
      if (node.child) queue.push(node.child);
      if (node.sibling) queue.push(node.sibling);
    }
    throw new Error('WebAppRuntime authStore was not found in the React tree');
  }, action);
}

async function clickNavigate(page: Page, path: string) {
  await page.evaluate((nextPath) => {
    const root = document.getElementById('root');
    if (!root) {
      throw new Error('Missing #root');
    }
    const containerKey = Object.keys(root).find((key) =>
      key.startsWith('__reactContainer'),
    );
    const host = containerKey
      ? (root as unknown as Record<
          string,
          {
            child?: unknown;
            stateNode?: { current?: unknown };
            current?: unknown;
          }
        >)[containerKey]
      : undefined;
    const startFiber =
      (host && 'child' in host && host.child ? host : undefined) ??
      host?.stateNode?.current ??
      host?.current ??
      host;

    type Fiber = {
      memoizedProps?: Record<string, unknown>;
      pendingProps?: Record<string, unknown>;
      child?: Fiber | null;
      sibling?: Fiber | null;
    };

    const seen = new Set<Fiber>();
    const queue: Fiber[] = startFiber ? [startFiber as Fiber] : [];
    while (queue.length > 0) {
      const node = queue.shift();
      if (!node || seen.has(node)) {
        continue;
      }
      seen.add(node);
      const props = {
        ...(node.pendingProps ?? {}),
        ...(node.memoizedProps ?? {}),
      };
      for (const bag of [props.runtime, props.value, props]) {
        const record = bag as {
          router?: { navigate?: (to: string) => unknown };
        } | null;
        if (typeof record?.router?.navigate === 'function') {
          void record.router.navigate(nextPath);
          return true;
        }
      }
      if (node.child) queue.push(node.child);
      if (node.sibling) queue.push(node.sibling);
    }
    throw new Error('App router.navigate was not found in the React tree');
  }, path);
}

async function markUploadDirty(page: Page) {
  await page.goto('/upload');
  await expect(page.locator('main h1')).toBeVisible();
  await page.locator('input[type="file"]').setInputFiles({
    name: 'wp03-dirty.jpg',
    mimeType: 'image/jpeg',
    buffer: Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10]),
  });
  await expect(page.getByText('wp03-dirty.jpg')).toBeVisible();
}

test.describe('WP-03 canonical routing acceptance', () => {

  test.afterEach(async ({ page }) => {
    assertHermeticNetwork(page);
  });
  test('protected anonymous entry waits for bootstrap and preserves only a sanitized return path', async ({
    page,
  }) => {
    const backend = await installMockApi(page, {
      bootstrap: 'anonymous',
      deferBootstrap: true,
    });

    await page.goto('/admin/analytics?unsafe=1#fragment');
    await expect(page.getByRole('status', { name: 'Loading…' })).toBeVisible();
    await expect(page).toHaveURL(
      /\/admin\/analytics\?unsafe=1#fragment$/,
    );

    backend.releaseBootstrap();
    await expect(page).toHaveURL(/\/login$/);
    await page.getByLabel('Email').fill(users.admin.email);
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/admin\/analytics$/);
    expect(new URL(page.url()).search).toBe('');
    expect(new URL(page.url()).hash).toBe('');
  });

  test('authenticated direct entry and browser reload reuse the canonical protected policy', async ({
    page,
  }) => {
    const backend = await installMockApi(page, {
      user: users.user,
    });

    await page.goto('/map');
    await expect(page.getByLabel('Search location')).toBeVisible();
    await expect(page).toHaveURL(/\/map$/);
    await page.reload();
    await expect(page.getByLabel('Search location')).toBeVisible();
    await expect(page).toHaveURL(/\/map$/);
    expect(backend.refreshCount).toBe(2);
  });

  test('provisioning and authenticated users receive lifecycle-owned replacement redirects', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      sessionStorage.setItem(
        'parkio.pendingProfile',
        JSON.stringify({ displayName: 'Pending Driver' }),
      );
    });
    await installMockApi(page, {
      user: users.user,
      profileReady: false,
    });

    await page.goto('/map');
    await expect(page).toHaveURL(/\/preparing$/);
    await expect(
      page.getByRole('heading', { name: 'Preparing your account' }),
    ).toBeVisible();

    const cleanPage = await page.context().newPage();
    await installMockApi(cleanPage, { user: users.user });
    await cleanPage.goto('/preparing');
    await expect(cleanPage).toHaveURL(/\/map$/);
    await expect(cleanPage.getByLabel('Search location')).toBeVisible();
  });

  test('account-not-verified converges on the canonical verification surface', async ({
    page,
  }) => {
    await installMockApi(page, {
      bootstrap: 'account-not-verified',
      user: users.user,
    });

    await page.goto('/map');
    await expect(page).toHaveURL(/\/check-email$/);
    await expect(
      page.getByRole('heading', { name: 'Check your email' }),
    ).toBeVisible();
  });

  test('account-not-active lifecycle precedes role policy and public surfaces stay reachable', async ({
    page,
  }) => {
    await installMockApi(page, {
      bootstrap: 'account-not-active',
      user: users.user,
    });

    await page.goto('/admin');
    await expect(
      page.getByRole('heading', {
        name: 'Your account is not active',
      }),
    ).toBeVisible();
    await expect(page.getByText('Access denied')).toHaveCount(0);

    for (const [path, heading] of [
      ['/login', 'Welcome back'],
      ['/privacy', 'Privacy Policy'],
      ['/terms', 'Terms of Service'],
    ] as const) {
      await spaGoto(page, path);
      await expect(page).toHaveURL(new RegExp(`${path}$`));
      await expect(
        page.getByRole('heading', { name: heading }),
      ).toBeVisible();
    }
  });

  test('ordinary users are denied privileged routes without losing their session', async ({
    page,
  }) => {
    await installMockApi(page, { user: users.user });

    await page.goto('/admin/moderation');
    await expect(
      page.getByText(
        'This area requires a moderator or admin role.',
      ),
    ).toBeVisible();
    await spaGoto(page, '/admin');
    await expect(
      page.getByText('This area requires an admin role.'),
    ).toBeVisible();
    await spaGoto(page, '/map');
    await expect(page.getByLabel('Search location')).toBeVisible();
  });

  test('moderators can render moderation but not administrator-only pages', async ({
    page,
  }) => {
    await installMockApi(page, { user: users.moderator });

    await page.goto('/admin/moderation');
    await expect(
      page.getByRole('heading', { name: 'Moderation' }),
    ).toBeVisible();
    await spaGoto(page, '/admin/analytics');
    await expect(
      page.getByText('This area requires an admin role.'),
    ).toBeVisible();
    await spaGoto(page, '/map');
    await expect(page.getByLabel('Search location')).toBeVisible();
  });

  test('administrators can render every canonical administrator destination with its manifest title', async ({
    page,
  }) => {
    await installMockApi(page, { user: users.admin });
    const destinations = [
      ['/admin', 'Parkio — Admin dashboard', 'Admin dashboard'],
      ['/admin/users', 'Parkio — Users', 'Users'],
      [
        `/admin/users/${USER_ID}`,
        'Parkio — User',
        users.user.email,
      ],
      ['/admin/security', 'Parkio — Security', 'Security'],
      ['/admin/moderation', 'Parkio — Moderation', 'Moderation'],
      ['/admin/analytics', 'Parkio — Analytics', 'Analytics'],
      ['/admin/audit', 'Parkio — Audit trail', 'Audit trail'],
      ['/admin/system', 'Parkio — System', 'System'],
    ] as const;

    for (const [path, title, heading] of destinations) {
      if (page.url() === 'about:blank') {
        await page.goto(path);
      } else {
        await spaGoto(page, path);
      }
      await expect(page).toHaveURL(
        new RegExp(`${path.replaceAll('/', '\\/')}$`),
      );
      await expect(page).toHaveTitle(title);
      await expect(
        page.getByRole('heading', { name: heading, exact: true }),
      ).toBeVisible();
    }
  });

  test('aliases replace history while explicit user navigation pushes history', async ({
    page,
  }) => {
    await installMockApi(page, { user: users.admin });

    await page.goto('/map');
    await spaGoto(page, '/analytics');
    await expect(page).toHaveURL(/\/admin\/analytics$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/map$/);

    await page.locator('header a[href="/profile"]').click();
    await expect(page).toHaveURL(/\/profile$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/map$/);

    await spaGoto(page, '/moderation');
    await expect(page).toHaveURL(/\/admin\/moderation$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/map$/);
  });

  test('document titles follow final, parameterized, unknown, and locale-updated routes while focus follows navigation', async ({
    page,
  }) => {
    await installMockApi(page, { user: users.admin });

    await page.goto(`/spots/${SPOT_ID}`);
    await expect(page).toHaveTitle('Parkio — Spot Details');

    await spaGoto(page, '/definitely-not-a-route');
    await expect(page).toHaveTitle('Parkio — Not Found');
    await expect(
      page.getByRole('heading', { name: 'Page not found' }),
    ).toBeVisible();
    await expect
      .poll(() =>
        page.evaluate(() =>
          document.activeElement?.matches(
            '[data-route-focus], h1, main',
          ),
        ),
      )
      .toBe(true);

    await spaGoto(page, '/profile');
    await expect(page).toHaveTitle('Parkio — Settings');
    await page
      .getByRole('tab', { name: 'Notifications' })
      .click();
    await page.getByRole('radio', { name: 'Türkçe' }).click();
    await expect(page).toHaveURL(/\/profile$/);
    await expect(page).toHaveTitle('Parkio — Ayarlar');
  });

  test('dirty upload blocks ordinary navigation but an authentication teardown reaches the manifest bypass', async ({
    page,
  }) => {
    await installMockApi(page, { user: users.user });
    await markUploadDirty(page);

    await page.locator('header a[href="/profile"]').click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page
      .getByRole('button', { name: 'Continue sharing' })
      .click();
    await expect(page).toHaveURL(/\/upload$/);

    await page.evaluate(() => {
      const channel = new BroadcastChannel('parkio.auth');
      channel.postMessage({
        version: 1,
        type: 'session-destroyed',
        eventId: 'wp03-browser-session-destruction',
      });
      channel.close();
    });
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });

  test('legal, product, administrator, unknown, malformed, query-bearing, and trailing-slash paths remain guarded metadata', async ({
    page,
  }) => {
    await installMockApi(page, { user: users.admin });
    await page.goto('/upload');

    const eligibility = await page.evaluate(
      async (destinations) => {
        const { isNavigationInterruptionBypassPath } = await import(
          '/src/routing/route-manifest.ts'
        );
        return destinations.map((destination) => ({
          destination,
          bypass: isNavigationInterruptionBypassPath(destination),
        }));
      },
      [
        '/privacy',
        '/map',
        '/admin',
        '/unknown-destination',
        '/spots/not-a-uuid',
        '/login?return=/map',
        '/login/',
      ],
    );

    expect(eligibility).toEqual([
      { destination: '/privacy', bypass: false },
      { destination: '/map', bypass: false },
      { destination: '/admin', bypass: false },
      { destination: '/unknown-destination', bypass: false },
      { destination: '/spots/not-a-uuid', bypass: false },
      { destination: '/login?return=/map', bypass: false },
      { destination: '/login/', bypass: false },
    ]);
  });

  test('restricted lifecycle keeps every public authentication and legal route reachable', async ({
    page,
  }) => {
    await installMockApi(page, {
      bootstrap: 'account-not-active',
      user: users.user,
    });
    await page.goto('/admin');
    await expect(
      page.getByRole('heading', {
        name: 'Your account is not active',
      }),
    ).toBeVisible();

    const publicRoutes = await page.evaluate(async () => {
      const {
        getPublicRouteIds,
        getRoutePath,
        ROUTE_IDS,
      } = await import('/src/routing/route-manifest.ts');
      return getPublicRouteIds()
        .filter((id) => id !== ROUTE_IDS.NOT_FOUND)
        .map((id) => ({
          id,
          path: getRoutePath(id),
        }));
    });

    const headings: Record<string, string> = {
      '/login': 'Welcome back',
      '/register': 'Create your account',
      '/forgot-password': 'Reset your password',
      '/reset-password': 'Choose a new password',
      '/check-email': 'Check your email',
      '/verify-email': 'Verify your email',
      '/privacy': 'Privacy Policy',
      '/terms': 'Terms of Service',
    };

    expect(publicRoutes.map((route) => route.path).sort()).toEqual(
      [
        '/login',
        '/register',
        '/forgot-password',
        '/reset-password',
        '/check-email',
        '/verify-email',
        '/privacy',
        '/terms',
      ].sort(),
    );

    for (const route of publicRoutes) {
      await spaGoto(page, route.path);
      await expect(page).toHaveURL(new RegExp(`${route.path}$`));
      await expect(
        page.getByRole('heading', { name: headings[route.path] }),
      ).toBeVisible();
    }
  });

  test('dirty upload bypasses every exact manifest interruption destination', async ({
    page,
  }) => {
    const backend = await installMockApi(page, { user: users.user });
    await markUploadDirty(page);

    const bypassRoutes = await page.evaluate(async () => {
      const {
        ROUTE_MANIFEST,
        getRoutePath,
        isNavigationInterruptionBypassPath,
      } = await import('/src/routing/route-manifest.ts');
      return ROUTE_MANIFEST.filter(
        (entry) =>
          entry.navigationInterruption === 'bypass' &&
          (entry.kind === 'path' || entry.kind === 'index'),
      ).map((entry) => {
        const path = getRoutePath(entry.id);
        return {
          id: entry.id,
          path,
          bypass: isNavigationInterruptionBypassPath(path),
        };
      });
    });

    expect(bypassRoutes.map((route) => route.path).sort()).toEqual(
      [
        '/login',
        '/register',
        '/forgot-password',
        '/reset-password',
        '/check-email',
        '/verify-email',
        '/preparing',
      ].sort(),
    );
    expect(bypassRoutes.every((route) => route.bypass)).toBe(true);

    const headings: Record<string, string | RegExp> = {
      '/login': 'Welcome back',
      '/register': 'Create your account',
      '/forgot-password': 'Reset your password',
      '/reset-password': 'Choose a new password',
      '/check-email': 'Check your email',
      '/verify-email': 'Verify your email',
      '/preparing': /Preparing your account|Parkio/,
    };

    for (const route of bypassRoutes) {
      await markUploadDirty(page);
      if (route.path === '/preparing') {
        backend.setProfileReady(false);
        await page.evaluate(() => {
          sessionStorage.setItem(
            'parkio.pendingProfile',
            JSON.stringify({ displayName: 'Bypass Preparing Driver' }),
          );
        });
        await callRuntimeAuth(page, 'beginProvisioning');
        await expect(page.getByRole('dialog')).toHaveCount(0);
        await expect(page).toHaveURL(/\/preparing$/);
        await expect(
          page.getByRole('heading', { name: 'Preparing your account' }),
        ).toBeVisible();
        continue;
      }
      await clickNavigate(page, route.path);
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(page).toHaveURL(new RegExp(`${route.path}$`));
      await expect(
        page.getByRole('heading', { name: headings[route.path] }),
      ).toBeVisible();
    }
  });

  test('dirty upload does not block provisioning or not-verified lifecycle redirects', async ({
    page,
  }) => {
    const backend = await installMockApi(page, {
      user: users.user,
      profileReady: false,
    });
    await markUploadDirty(page);
    await page.evaluate(() => {
      sessionStorage.setItem(
        'parkio.pendingProfile',
        JSON.stringify({ displayName: 'Dirty Provisioning Driver' }),
      );
    });
    void backend;

    await callRuntimeAuth(page, 'beginProvisioning');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page).toHaveURL(/\/preparing$/);
    await expect(
      page.getByRole('heading', { name: 'Preparing your account' }),
    ).toBeVisible();

    const verifiedPage = await page.context().newPage();
    await installMockApi(verifiedPage, {
      user: users.user,
    });
    await markUploadDirty(verifiedPage);
    await callRuntimeAuth(verifiedPage, 'markAccountNotVerified');
    await expect(verifiedPage.getByRole('dialog')).toHaveCount(0);
    await expect(verifiedPage).toHaveURL(/\/check-email$/);
    await expect(
      verifiedPage.getByRole('heading', { name: 'Check your email' }),
    ).toBeVisible();
    assertHermeticNetwork(verifiedPage);
    await verifiedPage.close();
  });

  test('dirty upload blocks representative non-bypass destinations until confirmation', async ({
    page,
  }) => {
    await installMockApi(page, { user: users.admin });

    const blocked: ReadonlyArray<{
      destination: string;
      go: () => Promise<void>;
    }> = [
      {
        destination: '/privacy',
        go: async () => clickNavigate(page, '/privacy'),
      },
      {
        destination: '/map',
        go: async () =>
          page.getByRole('link', { name: 'Map', exact: true }).click(),
      },
      {
        destination: '/admin',
        go: async () => clickNavigate(page, '/admin'),
      },
      {
        destination: '/unknown-destination',
        go: async () => clickNavigate(page, '/unknown-destination'),
      },
    ];

    for (const entry of blocked) {
      await markUploadDirty(page);
      await entry.go();
      await expect(page.getByRole('dialog')).toBeVisible();
      await expect(page).toHaveURL(/\/upload$/);
      await page
        .getByRole('button', { name: 'Continue sharing' })
        .click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(page).toHaveURL(/\/upload$/);
    }

    // Query/trailing variants must not inherit bypass eligibility from a related
    // exact path. Manifest helpers already reject `/login?…` and `/login/`; prove
    // the same class of malformed destinations stay blocked in the browser.
    for (const destination of ['/privacy?from=upload', '/map/'] as const) {
      await markUploadDirty(page);
      await clickNavigate(page, destination);
      await expect(page.getByRole('dialog')).toBeVisible();
      await expect(page).toHaveURL(/\/upload$/);
      await page
        .getByRole('button', { name: 'Continue sharing' })
        .click();
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(page).toHaveURL(/\/upload$/);
    }
  });

  test('authentication and lifecycle redirects replace browser history', async ({
    page,
  }) => {
    await installMockApi(page, {
      bootstrap: 'anonymous',
    });
    await page.goto('/privacy');
    await expect(
      page.getByRole('heading', { name: 'Privacy Policy' }),
    ).toBeVisible();
    await spaGoto(page, '/map');
    await expect(page).toHaveURL(/\/login$/);
    await page.goBack();
    await expect(page).toHaveURL(/\/privacy$/);
    await expect(page).not.toHaveURL(/\/map$/);

    const provisioning = await page.context().newPage();
    await provisioning.addInitScript(() => {
      sessionStorage.setItem(
        'parkio.pendingProfile',
        JSON.stringify({ displayName: 'History Driver' }),
      );
    });
    await installMockApi(provisioning, {
      user: users.user,
      profileReady: false,
    });
    await provisioning.goto('/privacy');
    await spaGoto(provisioning, '/map');
    await expect(provisioning).toHaveURL(/\/preparing$/);
    await provisioning.goBack();
    await expect(provisioning).toHaveURL(/\/privacy$/);
    await expect(provisioning).not.toHaveURL(/\/map$/);
    assertHermeticNetwork(provisioning);
    await provisioning.close();

    const unverified = await page.context().newPage();
    await installMockApi(unverified, {
      bootstrap: 'account-not-verified',
      user: users.user,
    });
    await unverified.goto('/privacy');
    await spaGoto(unverified, '/map');
    await expect(unverified).toHaveURL(/\/check-email$/);
    await unverified.goBack();
    await expect(unverified).toHaveURL(/\/privacy$/);
    await expect(unverified).not.toHaveURL(/\/map$/);
    assertHermeticNetwork(unverified);
    await unverified.close();
  });
});
