import { expect, test } from '@playwright/test';
import { PASSWORD, escapeRegExp, installMockApi, member, snap, spaGoto, t, waitForApp } from './support';

/**
 * CX-F11 scenario A: a new account in Turkish and in English, end to end on one candidate:
 * register, check-email, verify, sign in, open the profile. Labels come from the app's own
 * translations, so each run proves the flow in that language.
 */
for (const locale of ['tr', 'en'] as const) {
  test(`register, verify, sign in and open the profile (${locale})`, async ({ page }, testInfo) => {
    const api = await installMockApi(page, locale);

    await page.goto('/register');
    await waitForApp(page);
    await expect(page.locator('html')).toHaveAttribute('lang', locale);
    await page.getByLabel(t(locale, 'auth', 'register.displayName')).fill('CX F11 Tester');
    await page.getByLabel(t(locale, 'auth', 'register.email')).fill(member.email);
    await page.getByLabel(t(locale, 'auth', 'register.password'), { exact: true }).fill(PASSWORD);
    await page.getByLabel(t(locale, 'auth', 'register.confirmPassword')).fill(PASSWORD);
    await page.getByLabel(new RegExp(escapeRegExp(t(locale, 'auth', 'register.termsPrefix')))).check();
    await snap(page, testInfo, '1-register');
    await page.getByRole('button', { name: t(locale, 'auth', 'register.submit') }).click();

    await expect(page).toHaveURL(/\/check-email/);
    await expect(page.getByRole('heading', { name: t(locale, 'auth', 'checkEmail.title') })).toBeVisible();
    await snap(page, testInfo, '2-check-email');

    await page.goto('/verify-email?token=cxf11-verify-token');
    await expect(page.getByRole('heading', { name: t(locale, 'auth', 'verifyEmail.titleSuccess') })).toBeVisible();
    await snap(page, testInfo, '3-verified');
    await page.getByRole('button', { name: t(locale, 'auth', 'verifyEmail.signIn') }).click();

    await page.getByLabel(t(locale, 'auth', 'login.email')).fill(member.email);
    await page.getByLabel(t(locale, 'auth', 'login.password')).fill(PASSWORD);
    await page.getByRole('button', { name: t(locale, 'auth', 'login.submit') }).click();
    await expect(page).toHaveURL(/\/map$/);
    await expect(page.locator('html')).toHaveAttribute('lang', locale);

    await spaGoto(page, '/profile');
    await expect(page).toHaveURL(/\/profile$/);
    await expect(page.getByText(member.email).first()).toBeVisible();
    await snap(page, testInfo, '4-profile');

    expect(api.count('POST /auth/register')).toBe(1);
    expect(api.count('POST /auth/verify-email')).toBe(1);
    expect(api.count('POST /auth/login')).toBe(1);
    if (api.unmocked.length) testInfo.annotations.push({ type: 'unmocked', description: api.unmocked.join(', ') });
  });
}
