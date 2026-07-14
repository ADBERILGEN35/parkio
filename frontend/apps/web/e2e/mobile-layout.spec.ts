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
    if (method === 'GET' && path === '/notifications/me') {
      return json([
        {
          id: 'n1',
          type: 'POINT_EARNED',
          channel: 'IN_APP',
          title: 'Points',
          body: 'You earned points',
          status: 'UNREAD',
          createdAt: '2026-06-11T09:00:00Z',
          readAt: null,
          metadata: { points: '10', totalPoints: '10', messageKey: 'pointEarned' },
        },
      ]);
    }
    if (method === 'GET' && path === '/users/me/vehicle') {
      return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    }
    if (method === 'GET' && path === '/parking/spots/nearby') return json([]);
    if (method === 'GET' && path === '/parking/spots/mine') return json([]);
    if (method === 'GET' && path === '/geocoding/search') return json({ results: [] });
    if (method === 'GET' && path === '/users/me') {
      return json({
        id: USER_ID,
        authUserId: USER_ID,
        email: 'tester@parkio.dev',
        displayName: 'Very Long Display Name For Overflow Checks',
        phoneNumber: null,
        city: 'Istanbul',
        status: 'ACTIVE',
        createdAt: '2026-01-01T09:00:00Z',
      });
    }
    if (method === 'GET' && path === '/users/me/stats') {
      return json({ trustScore: 72, trustBand: 'HIGH_TRUST', totalPoints: 10, currentLevel: 1 });
    }
    if (method === 'GET' && path === '/users/me/preferences') {
      return json({ preferredRadiusMeters: 1500, notificationsEnabled: true, preferredLocale: null });
    }
    if (method === 'GET' && path === '/gamification/leaderboard') {
      return json([{ rank: 1, userId: USER_ID, totalPoints: 10, currentLevel: 1 }]);
    }
    if (method === 'GET' && path === '/gamification/me/progress') {
      return json({
        userId: USER_ID,
        totalPoints: 10,
        currentLevel: 1,
        updatedAt: '2026-06-11T09:00:00Z',
      });
    }
    if (method === 'GET' && path === '/gamification/me/level') {
      return json({
        userId: USER_ID,
        currentLevel: 1,
        totalPoints: 10,
        currentLevelMinPoints: 0,
        nextLevelMinPoints: 100,
        pointsToNextLevel: 90,
      });
    }
    if (method === 'GET' && path === '/gamification/me/points') {
      return json({ userId: USER_ID, totalPoints: 10, recentTransactions: [] });
    }
    if (method === 'GET' && path === '/gamification/me/access-policy') {
      return json({
        userId: USER_ID,
        currentLevel: 1,
        searchRadiusMeters: 1000,
        resultLimit: 10,
        dailyViewLimit: 50,
        verifiedSpotPriority: false,
        notificationPriority: false,
      });
    }
    if (method === 'GET' && path === '/gamification/levels') {
      return json([
        {
          level: 1,
          minPoints: 0,
          maxPoints: 99,
          searchRadiusMeters: 1000,
          resultLimit: 10,
          dailyViewLimit: 50,
          verifiedSpotPriority: false,
          notificationPriority: false,
        },
      ]);
    }
    if (method === 'GET' && /^\/users\/[^/]+\/public-profile$/.test(path)) {
      return json({
        userId: USER_ID,
        displayName: 'Test Driver',
        city: 'Izmir',
        trustBand: 'TRUSTED',
        currentLevel: 1,
        status: 'ACTIVE',
        memberSince: '2026-01-01T00:00:00Z',
      });
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

async function assertNoDocumentOverflow(page: Page) {
  const overflow = await page.evaluate(() => {
    const doc = document.documentElement;
    return {
      scrollWidth: doc.scrollWidth,
      clientWidth: doc.clientWidth,
    };
  });
  expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1);
}

async function assertNoIconTokenLeak(page: Page) {
  const text = await page.locator('body').innerText();
  expect(text).not.toMatch(/(?:\+)?__[A-Z0-9]+(?:__[A-Z0-9]+)*__/);
  expect(text).not.toMatch(/\bADD_LOCATION_ALT\b|\bPERSON_PIN_CIRCLE\b|\bSETTINGS\b(?!\s*&)/);
}

async function assertCompactTopOffset(page: Page) {
  await expect(page.locator('main h1').first()).toBeVisible();
  const metrics = await page.evaluate(() => {
    const main = document.querySelector('main');
    const heading = document.querySelector('main h1');
    if (!main || !heading) return null;
    const mainStyles = getComputedStyle(main);
    const headingTop = heading.getBoundingClientRect().top;
    return {
      paddingTop: parseFloat(mainStyles.paddingTop || '0'),
      headingTop,
    };
  });
  expect(metrics).not.toBeNull();
  // Mobile must not reserve the desktop header (64px) as unused padding.
  expect(metrics!.paddingTop).toBeLessThan(8);
  // Content should start near the top of the shell (allow compact page py-lg).
  expect(metrics!.headingTop).toBeLessThan(120);
}

const MOBILE_VIEWPORTS = [
  { width: 320, height: 568 },
  { width: 375, height: 812 },
  { width: 390, height: 844 },
  { width: 430, height: 932 },
] as const;

test.describe('Mobile layout integrity', () => {
  test.beforeEach(async ({ page }) => {
    await installMocks(page);
  });

  for (const viewport of MOBILE_VIEWPORTS) {
    test(`critical routes stay in-bounds at ${viewport.width}x${viewport.height}`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport);
      await login(page);

      const routes = ['/my-spots', '/upload', '/leaderboard', '/profile', '/notifications', '/gamification'];
      for (const path of routes) {
        await page.goto(path);
        await expect(page).toHaveURL(new RegExp(`${path}$`));
        await assertCompactTopOffset(page);
        await assertNoDocumentOverflow(page);
        await assertNoIconTokenLeak(page);
      }
    });
  }

  test('profile tabs stay reachable and heading has intentional focus style', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await login(page);
    await page.goto('/profile');

    const tablist = page.getByRole('tablist');
    await expect(tablist).toBeVisible();
    await expect(page.getByRole('tab').first()).toBeVisible();
    await assertNoDocumentOverflow(page);

    const heading = page.getByRole('heading', { level: 1 });
    await expect(heading).toBeVisible();
    // RouteAccessibility focuses h1 briefly; assert intentional non-keyboard style.
    const focusStyles = await heading.evaluate((el) => {
      el.classList.add('parkio-route-focus');
      el.setAttribute('tabindex', '-1');
      el.focus({ preventScroll: true });
      const cs = getComputedStyle(el);
      return { style: cs.outlineStyle, color: cs.outlineColor, width: cs.outlineWidth };
    });
    // Chromium treats scripted focus as :focus-visible; ensure we never keep UA black.
    const isBlack =
      focusStyles.color === 'rgb(0, 0, 0)' || focusStyles.color.startsWith('rgba(0, 0, 0');
    expect(isBlack).toBe(false);
  });

  test('notification filters scroll without widening the document', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 568 });
    await login(page);
    await page.goto('/notifications');
    await expect(page.getByRole('group', { name: /filtre|filter/i })).toBeVisible();
    await assertNoDocumentOverflow(page);
    const chips = page.getByRole('group', { name: /filtre|filter/i }).locator('button');
    await expect(chips.first()).toBeVisible();
    await chips.last().scrollIntoViewIfNeeded();
    await expect(chips.last()).toBeVisible();
    await assertNoDocumentOverflow(page);
  });

  test('leaderboard summary uses a compact three-column row', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await login(page);
    await page.goto('/leaderboard');
    await expect(page.locator('main h1')).toHaveText(/Sıralama|Leaderboard/i);
    await expect(page.locator('main').getByText(/^Sıra$|^Rank$/).first()).toBeVisible();

    const metrics = await page.evaluate(() => {
      const cards = [...document.querySelectorAll('main .grid')].find((grid) =>
        grid.querySelectorAll(':scope > *').length === 3,
      );
      if (!cards) return null;
      const kids = [...cards.children].map((el) => el.getBoundingClientRect());
      return {
        tops: kids.map((k) => Math.round(k.top)),
        widths: kids.map((k) => Math.round(k.width)),
      };
    });
    expect(metrics).not.toBeNull();
    expect(Math.max(...metrics!.tops) - Math.min(...metrics!.tops)).toBeLessThan(8);
    expect(Math.min(...metrics!.widths)).toBeGreaterThan(60);
  });

  test('upload wizard final CTA clears the bottom nav', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page);
    await page.goto('/upload');
    await expect(page.locator('main h1')).toBeVisible();
    await assertCompactTopOffset(page);

    const mainPaddingBottom = await page.evaluate(() =>
      parseFloat(getComputedStyle(document.querySelector('main')!).paddingBottom),
    );
    expect(mainPaddingBottom).toBeGreaterThanOrEqual(64);

    const continueBtn = page.getByRole('button', { name: /Devam et|Continue/i }).first();
    await expect(continueBtn).toBeVisible();
    await continueBtn.scrollIntoViewIfNeeded();
    const box = await continueBtn.boundingBox();
    const nav = page.getByRole('navigation', { name: /Ana menü|Primary/i });
    const navBox = await nav.boundingBox();
    expect(box).not.toBeNull();
    expect(navBox).not.toBeNull();
    expect(box!.y + box!.height).toBeLessThanOrEqual(navBox!.y + 2);
  });

  test('English locale preserves compact mobile shell on profile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await login(page);
    await page.goto('/profile');
    await page.evaluate(() => localStorage.setItem('parkio.locale', 'en'));
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await assertCompactTopOffset(page);
    await assertNoDocumentOverflow(page);
    await assertNoIconTokenLeak(page);
  });

  test('map uses full-bleed top on mobile (no desktop-header inset)', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page);
    await page.goto('/map');
    const mapRoot = page.locator('main div.fixed').first();
    await expect(mapRoot).toBeVisible({ timeout: 15_000 });
    const top = await mapRoot.evaluate((el) => el.getBoundingClientRect().top);
    expect(top).toBeLessThan(4);
    const mainPad = await page.evaluate(() =>
      parseFloat(getComputedStyle(document.querySelector('main')!).paddingTop),
    );
    expect(mainPad).toBeLessThan(8);
  });

  test('desktop content pages still clear the top nav', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    await login(page);
    await page.goto('/profile');
    await expect(page.locator('main h1')).toBeVisible();
    const paddingTop = await page.evaluate(() =>
      parseFloat(getComputedStyle(document.querySelector('main')!).paddingTop),
    );
    expect(paddingTop).toBeGreaterThanOrEqual(60);
    const headingTop = await page.evaluate(
      () => document.querySelector('main h1')!.getBoundingClientRect().top,
    );
    expect(headingTop).toBeGreaterThan(64);
  });
});
