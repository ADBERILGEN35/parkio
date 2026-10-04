import { expect, test } from '@playwright/test';
import { installMockApi, member, snap, spaGoto, t, waitForApp } from './support';

/**
 * CX-F11 scenario F (the negative half): a signed-in user without the admin role does not get the
 * admin area, and the client never calls an admin API for them. The positive half, the admin shell
 * in Turkish and English, is e2e/locale.spec.ts Flow C, which the candidate run includes.
 */
for (const locale of ['tr', 'en'] as const) {
  test(`a user without the admin role does not reach the admin area (${locale})`, async ({ page }, testInfo) => {
    const api = await installMockApi(page, locale, { signedIn: member });
    await page.goto('/map');
    await waitForApp(page);
    await expect(page).toHaveURL(/\/map$/);

    for (const target of ['/admin', '/admin/users', '/admin/waitlist']) {
      await spaGoto(page, target);
      // Denial is shown, not just the admin page missing: an empty page would pass the checks below.
      await expect(page.getByText(t(locale, 'common', 'accessDenied.adminRequired'))).toBeVisible();
      await expect(page.getByRole('heading', { name: t(locale, 'admin', 'dashboard.title') })).toHaveCount(0);
      await expect(page.getByRole('heading', { name: t(locale, 'admin', 'users.title') })).toHaveCount(0);
      await expect(page.getByRole('heading', { name: t(locale, 'admin', 'waitlist.title') })).toHaveCount(0);
    }
    await snap(page, testInfo, '1-admin-denied');
    expect(api.requests.filter((call) => / \/admin\//.test(call))).toEqual([]);
  });
}
