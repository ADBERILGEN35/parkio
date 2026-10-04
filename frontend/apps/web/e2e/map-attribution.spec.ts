import { expect, test, type Locator, type Page, type Route } from '@playwright/test';

/**
 * /map keeps MapLibre's attribution, which carries the map credits, visible and reachable, and on phones
 * the bottom sheet's handle too. Neither the desktop results sidebar, nor the phone sheet in any state,
 * nor the page's own top overlay may cover them (Asana 1219145771874977).
 *
 * Every supported width runs in each viewport project: 360 and 390 px get the phone sheet, 768 px and
 * wider the sidebar. The session is restored instead of signed in, so the transient sign-in toast is not
 * on screen; toasts are not part of the layout under test.
 */

const USER_ID = '6f9619ff-8b86-4d01-b42d-00cf4fc964ff';
const user = { id: USER_ID, email: 'tester@parkio.dev', status: 'ACTIVE', roles: ['USER'] };

const VIEWPORTS = [
  { width: 360, height: 800 },
  { width: 390, height: 844 },
  { width: 768, height: 1024 },
  { width: 1024, height: 768 },
  { width: 1280, height: 720 },
  { width: 1440, height: 900 },
];

/** Sheet states in the order the test visits them, with the handle key that reaches each one. */
const SHEET_STATES = [
  { key: 'End', state: 'collapsed' },
  { key: 'ArrowUp', state: 'half open' },
  { key: 'Home', state: 'expanded' },
];

async function installMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('parkio.locale', 'en');
  });
  // Tiles and fonts stay offline; the attribution comes from the style, not from the tiles.
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
      return json({
        accessToken: 'access-user',
        tokenType: 'Bearer',
        accessTokenExpiresAt: '2999-01-01T00:00:00Z',
        refreshTokenExpiresAt: '2999-01-01T00:00:00Z',
        user,
      });
    }
    if (method === 'GET' && path === '/auth/me') return json(user);
    if (method === 'GET' && path === '/users/me') {
      return json({
        id: USER_ID,
        authUserId: USER_ID,
        email: user.email,
        displayName: 'Test Driver',
        phoneNumber: null,
        city: 'Izmir',
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
    if (method === 'GET' && path === '/parking/sessions/active') return route.fulfill({ status: 204, body: '' });
    if (method === 'GET' && path === '/parking/sessions/lifecycle-config') {
      return json({ sessionEnabled: true, allowManualStart: true });
    }
    if (method === 'GET' && path.endsWith('/nearby')) return json([]);
    if (method === 'GET' && path === '/notifications/me') return json([]);
    return json({});
  });
}

async function openMap(page: Page) {
  await installMocks(page);
  await page.goto('/map');
  await expect(page).toHaveURL(/\/map$/);
  await expect(page.getByRole('region', { name: /Interactive parking discovery map/i })).toBeVisible();
  await expect(page.locator('.maplibregl-ctrl-attrib a').first()).toBeVisible();
}

/** The element's box, whether it lies inside the viewport, and what covers its left, centre and right. */
async function placement(locator: Locator) {
  return locator.evaluate((el) => {
    const r = el.getBoundingClientRect();
    const y = r.top + r.height / 2;
    const covers = [r.left + 3, r.left + r.width / 2, r.right - 3].flatMap((x) => {
      const hit = document.elementFromPoint(x, y);
      if (hit && el.contains(hit)) return [];
      const owner = hit?.closest('[aria-label], [data-testid], aside, header, nav, section');
      return [
        owner?.getAttribute('aria-label') ?? owner?.getAttribute('data-testid') ?? owner?.tagName ?? 'outside the viewport',
      ];
    });
    return {
      box: [r.left, r.top, r.width, r.height].map(Math.round).join(','),
      inViewport: r.top >= 0 && r.left >= 0 && r.bottom <= window.innerHeight && r.right <= window.innerWidth,
      covers,
    };
  });
}

/** Visible, inside the viewport, not covered, and clickable by Playwright's actionability checks. */
async function expectReachable(locator: Locator, what: string) {
  await expect(locator, what).toBeVisible();
  const { box, inViewport, covers } = await placement(locator);
  expect(inViewport, `${what} lies inside the viewport (box ${box})`).toBe(true);
  expect(covers, `${what} is covered (box ${box})`).toEqual([]);
  await locator.click({ trial: true, timeout: 2_000 });
}

async function expectCreditsReachable(page: Page, where: string) {
  const attribution = page.locator('.maplibregl-ctrl-attrib');
  await expectReachable(attribution, `attribution, ${where}`);
  const links = await attribution.locator('a').all();
  expect(links.length, `attribution links, ${where}`).toBeGreaterThan(0);
  for (const link of links) {
    await expectReachable(link, `attribution link "${(await link.textContent())?.trim()}", ${where}`);
  }
}

test.describe('/map attribution and sheet handle', () => {
  for (const viewport of VIEWPORTS) {
    test(`stay visible and reachable at ${viewport.width}x${viewport.height}`, async ({ page }, testInfo) => {
      await page.setViewportSize(viewport);
      await openMap(page);

      if (viewport.width >= 768) {
        const sidebar = page.getByRole('complementary', { name: /^Search results$/i });
        await expect(sidebar).toBeVisible();
        // The sidebar slides in for 400 ms with an overshooting ease that briefly moves it left past
        // its place; measure the settled layout.
        await sidebar.evaluate((aside) =>
          Promise.all(
            aside
              .getAnimations({ subtree: true })
              .filter((animation) => animation.effect?.getComputedTiming().iterations !== Infinity)
              .map((animation) => animation.finished),
          ),
        );
        await expectCreditsReachable(page, 'sidebar');
        await page.screenshot({ path: testInfo.outputPath('sidebar.png') });
        return;
      }

      const handle = page.getByRole('button', { name: /Search results,/i });
      for (const { key, state } of SHEET_STATES) {
        await handle.focus();
        await handle.press(key);
        await expect(page.getByRole('button', { name: new RegExp(`Search results, ${state}`, 'i') })).toBeVisible();
        // Let the 280 ms snap transition finish before reading positions.
        await page.waitForTimeout(400);
        await expectCreditsReachable(page, `${state} sheet`);
        await expectReachable(handle, `sheet handle, ${state} sheet`);
        await page.screenshot({ path: testInfo.outputPath(`${state.replace(' ', '-')}.png`) });
      }
    });
  }
});
