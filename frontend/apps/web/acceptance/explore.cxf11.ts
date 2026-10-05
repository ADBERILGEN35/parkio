import { expect, test, type Locator, type Page } from '@playwright/test';
import { EXPLORE_FACILITY, installMockApi, snap, t, waitForApp } from './support';

/**
 * CX-F11 scenario B: public Explore with its data and with provider failures. In these runs map tiles
 * and styles never load: every request off the machine is aborted.
 *
 * The two failure scenarios encode CL-F20's acceptance criteria (Asana 1219002255930420). Since
 * CL-F20 (option A) they pass. They were known defects before it. The keyboard scenarios check that
 * its new controls are reachable and work from the keyboard.
 */

/** Press Tab until `target` has focus, as a keyboard user would reach it. */
async function tabTo(page: Page, target: Locator, limit = 40) {
  for (let presses = 0; presses < limit; presses += 1) {
    await page.keyboard.press('Tab');
    if (await target.evaluate((element) => element === document.activeElement)) return;
  }
  throw new Error(`not reached with ${limit} Tab presses`);
}

for (const locale of ['tr', 'en'] as const) {
  test(`Explore loads its public data (${locale})`, async ({ page }, testInfo) => {
    const api = await installMockApi(page, locale);
    await page.goto('/explore');
    await waitForApp(page);
    await expect(page.locator('html')).toHaveAttribute('lang', locale);
    await expect(page.getByRole('heading', { level: 1 })).toBeAttached();
    await expect.poll(() => api.count('GET /public/explore/facilities')).toBeGreaterThan(0);
    await expect(page.getByText(t(locale, 'explore', 'unavailableTitle'))).toHaveCount(0);
    await snap(page, testInfo, '1-explore');
  });

  test(`a failed Explore query shows an accessible error with a working retry (${locale})`, async ({ page }, testInfo) => {
    const api = await installMockApi(page, locale);
    api.unavailable.add('GET /public/explore/facilities');
    await page.goto('/explore');
    await waitForApp(page);
    await expect.poll(() => api.count('GET /public/explore/facilities')).toBeGreaterThan(0);
    await snap(page, testInfo, '1-query-failed');

    const alert = page.getByRole('alert');
    await expect(alert).toBeVisible({ timeout: 5_000 });
    api.unavailable.delete('GET /public/explore/facilities');
    await alert.getByRole('button').first().click();
    await expect(alert).toHaveCount(0);
    await expect(page.getByText(EXPLORE_FACILITY.displayName).first()).toBeVisible();
  });

  test(`a failed map style or tile load shows an alert and a list fallback (${locale})`, async ({ page }, testInfo) => {
    const api = await installMockApi(page, locale);
    await page.goto('/explore');
    await waitForApp(page);
    await expect.poll(() => api.count('GET /public/explore/facilities')).toBeGreaterThan(0);
    await page.waitForLoadState('networkidle');
    await snap(page, testInfo, '1-map-provider-failed');

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByRole('list').getByText(EXPLORE_FACILITY.displayName)).toBeVisible();
  });

  test(`the Explore retry is reached and used from the keyboard (${locale})`, async ({ page }) => {
    const api = await installMockApi(page, locale);
    api.unavailable.add('GET /public/explore/facilities');
    await page.goto('/explore');
    await waitForApp(page);
    const retry = page.getByRole('alert').getByRole('button');
    await expect(retry).toBeVisible({ timeout: 5_000 });

    api.unavailable.delete('GET /public/explore/facilities');
    await tabTo(page, retry);
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByText(EXPLORE_FACILITY.displayName).first()).toBeVisible();
  });

  test(`the list fallback is reached and used from the keyboard (${locale})`, async ({ page }) => {
    await installMockApi(page, locale);
    await page.goto('/explore');
    await waitForApp(page);
    const item = page.getByRole('list').getByRole('button').filter({ hasText: EXPLORE_FACILITY.displayName });
    await expect(item).toBeVisible({ timeout: 10_000 });

    await tabTo(page, item);
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('selected-municipal-facility-preview')).toBeVisible();
  });
}
