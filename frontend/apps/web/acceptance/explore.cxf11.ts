import { expect, test } from '@playwright/test';
import { EXPLORE_FACILITY, installMockApi, snap, t, waitForApp } from './support';

/**
 * CX-F11 scenario B: public Explore with its data and with provider failures. In these runs map tiles
 * and styles never load: every request off the machine is aborted.
 *
 * The two failure scenarios encode CL-F20's acceptance criteria (Asana 1219002255930420). Until that
 * fix lands they fail and are marked as known defects with test.fail. The mark is set only after the
 * page has rendered and the failure is in place, so a broken setup still fails the run. When CL-F20
 * lands, Playwright reports an unexpected pass and the mark must be removed.
 */
const CL_F20 = 'Known defect CL-F20 (Asana 1219002255930420): no accessible error, retry or list fallback on Explore failures';

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
    test.fail(true, CL_F20);

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
    test.fail(true, CL_F20);

    await expect(page.getByRole('alert')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByRole('list').getByText(EXPLORE_FACILITY.displayName)).toBeVisible();
  });
}
