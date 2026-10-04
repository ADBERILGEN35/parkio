import { expect, test, type Page } from '@playwright/test';
import { keyboardWalk, measurePage, type Locale } from './helpers';

/**
 * CL-F30: the marketing site, measured in Turkish and English as `serve-marketing-site.mjs` serves
 * it (with the .htaccess headers). Requests that leave the local server are aborted; the waitlist
 * confirm and withdraw calls are answered locally, so no real submission is made.
 */
// `lang`: single-language pages keep their own language whatever the visitor chose; the others follow
// the selected locale.
const PAGES: { name: string; path: string; lang?: string }[] = [
  { name: 'home', path: '/' },
  { name: 'privacy', path: '/privacy/', lang: 'en' },
  { name: 'terms', path: '/terms/', lang: 'en' },
  { name: 'waitlist-confirm', path: '/waitlist/confirm/?token=a11y-confirm-token' },
  { name: 'waitlist-unsubscribe', path: '/waitlist/unsubscribe/?token=a11y-withdraw-token' },
  { name: 'not-found', path: '/404.html', lang: 'en' },
];

async function installMocks(page: Page, locale: Locale, external: string[]) {
  await page.addInitScript((value) => localStorage.setItem('parkio.marketing.locale', value), locale);
  await page.route(
    (url) => url.hostname !== '127.0.0.1' && url.hostname !== 'localhost',
    (route) => {
      external.push(route.request().url());
      return route.abort();
    },
  );
  await page.route(/\/waitlist\/(confirm|withdraw)$/, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'OK' }) }),
  );
}

for (const locale of ['tr', 'en'] as const) {
  test.describe(`marketing (${locale})`, () => {
    for (const target of PAGES) {
      test(`${target.name} (${locale})`, async ({ page }, testInfo) => {
        const external: string[] = [];
        await installMocks(page, locale, external);
        await page.goto(target.path);
        await page.waitForLoadState('networkidle');
        await expect(page.locator('main').first()).toBeVisible();
        if (external.length) testInfo.annotations.push({ type: 'aborted-external', description: external.join(', ') });
        await measurePage(page, testInfo, target.name, locale, target.lang ?? locale);
        if (target.name === 'privacy') {
          // The English policy carries a Turkish waitlist paragraph; its language is marked (WCAG 3.1.2).
          await expect(page.locator('p', { hasText: 'TR:' }).first()).toHaveAttribute('lang', 'tr');
        }

        // The footer is a contentinfo landmark and each of its links has an accessible name.
        const footer = page.getByRole('contentinfo');
        if (await footer.count()) {
          await expect(footer.first()).toBeVisible();
          const unnamed = await footer
            .first()
            .getByRole('link')
            .evaluateAll((links) =>
              links.filter((link) => !(link.getAttribute('aria-label') || link.textContent || '').trim()).length,
            );
          expect(unnamed, `${target.name} (${locale}): footer links without a name`).toBe(0);
        } else {
          testInfo.annotations.push({ type: 'footer', description: 'no contentinfo landmark on this page' });
        }
        await keyboardWalk(page, testInfo, target.name, locale, 150);
      });
    }
  });
}
