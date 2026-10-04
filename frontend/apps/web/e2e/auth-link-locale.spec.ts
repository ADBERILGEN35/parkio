import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * CL-F21 browser acceptance: the `lang` of an auth link (tr|en) decides the page language,
 * `<html lang>` included, over the browser's stored preference. Register and verify links
 * already did this; reset-password and login links now do too. As on register and verify,
 * the link language also becomes the stored preference. All backend traffic is mocked at
 * the network layer with a synthetic account; nothing leaves the browser.
 */

const LOCALE_KEY = 'parkio.locale';

const verifiedUser = {
  id: '3c3c3c3c-0000-4000-8000-00000000000c',
  email: 'synthetic-locale@parkio.test',
  status: 'ACTIVE',
  roles: ['USER'],
};

const LINKS = [
  {
    name: 'reset-password',
    path: '/reset-password?token=synthetic-reset-token',
    heading: { en: 'Choose a new password', tr: 'Yeni bir şifre seçin' },
  },
  {
    name: 'login',
    path: '/login',
    heading: { en: 'Welcome back', tr: 'Tekrar hoş geldiniz' },
  },
  // Unchanged controls: these routes honored the link language before CL-F21.
  {
    name: 'verify-email',
    path: '/verify-email?token=synthetic-verify-token',
    heading: { en: 'Email verified', tr: 'E-posta doğrulandı' },
  },
  {
    name: 'register',
    path: '/register',
    heading: { en: 'Create your account', tr: 'Hesabınızı oluşturun' },
  },
] as const;

const [RESET_LINK, LOGIN_LINK] = LINKS;

async function installMockApi(page: Page) {
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
      return json({ code: 'INVALID_TOKEN', message: 'No session', traceId: 'e2e-link-locale' }, 401);
    }
    if (method === 'POST' && path === '/auth/verify-email') return json(verifiedUser);
    // Open registration, so the register control shows its form rather than the closed notice.
    if (method === 'GET' && path === '/auth/registration-mode') return json({ mode: 'OPEN' });

    return json({ code: 'NOT_MOCKED', message: `Unmocked ${method} ${path}` }, 500);
  });
}

/** Saves `stored` as the browser's language preference, then opens `link` as a new page load. */
async function openWithStoredLocale(page: Page, stored: 'tr' | 'en', link: string) {
  await page.goto('/login');
  await page.evaluate(({ key, locale }) => localStorage.setItem(key, locale), {
    key: LOCALE_KEY,
    locale: stored,
  });
  await page.goto(link);
}

function withLang(path: string, lang: string) {
  return `${path}${path.includes('?') ? '&' : '?'}lang=${lang}`;
}

test.describe('Auth link language (CL-F21)', () => {
  test.beforeEach(async ({ page }) => {
    await installMockApi(page);
  });

  for (const link of LINKS) {
    for (const [stored, lang] of [
      ['tr', 'en'],
      ['en', 'tr'],
    ] as const) {
      test(`${link.name}: a lang=${lang} link wins over a stored ${stored} preference`, async ({
        page,
      }) => {
        await openWithStoredLocale(page, stored, withLang(link.path, lang));

        await expect(page.locator('html')).toHaveAttribute('lang', lang);
        await expect(page.getByRole('heading', { name: link.heading[lang] })).toBeVisible();
        expect(await page.evaluate((key) => localStorage.getItem(key), LOCALE_KEY)).toBe(lang);
      });
    }
  }

  test('an unsupported lang value keeps the stored preference', async ({ page }) => {
    await openWithStoredLocale(page, 'tr', withLang(RESET_LINK.path, 'de'));
    await expect(page.getByRole('heading', { name: RESET_LINK.heading.tr })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'tr');

    await openWithStoredLocale(page, 'en', withLang(LOGIN_LINK.path, 'en-US'));
    await expect(page.getByRole('heading', { name: LOGIN_LINK.heading.en })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  });

  test('pages opened after a reset link keep the link language', async ({ page }) => {
    await openWithStoredLocale(page, 'tr', withLang(RESET_LINK.path, 'en'));
    await expect(page.getByRole('heading', { name: RESET_LINK.heading.en })).toBeVisible();

    await page.goto(LOGIN_LINK.path);
    await expect(page.getByRole('heading', { name: LOGIN_LINK.heading.en })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  });
});
