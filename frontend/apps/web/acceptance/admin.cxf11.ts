import { expect, test } from '@playwright/test';
import { installMockApi, member, snap, spaGoto, t, waitForApp } from './support';

/**
 * CX-F11 scenario F (the negative half): a signed-in user without the admin role does not get the
 * admin area, and the client never calls an admin API for them. The positive half, the admin shell
 * in Turkish and English, is e2e/locale.spec.ts Flow C, which the candidate run includes.
 */

/**
 * Admin-only API paths, as the api-client calls them and the gateway's ADMIN_ONLY rules list them:
 * - any `admin` path segment (`/admin/...`, `/waitlist/admin`, `/waitlist/admin/summary`);
 * - the confirmed-waitlist export `/waitlist/export`;
 * - the analytics dashboards under `/analytics/`.
 *
 * Moderation and AI-validation routes are privileged (moderator or admin), not admin-only, and no
 * admin page calls them.
 */
function isAdminScoped(call: string): boolean {
  const path = call.slice(call.indexOf(' ') + 1);
  return (
    path.split('/').includes('admin') ||
    path === '/waitlist/export' ||
    path.startsWith('/waitlist/export/') ||
    path.startsWith('/analytics/')
  );
}
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
    expect(api.requests.filter(isAdminScoped)).toEqual([]);
  });
}
