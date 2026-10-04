import { expect, test } from '@playwright/test';
import { PASSWORD, PHOTO_BYTES, SPOT_ID, escapeRegExp, installMockApi, member, snap, spaGoto, t, waitForApp } from './support';

/**
 * CX-F11 scenario E: offline, retry and duplicate submit. A dropped connection must leave the user
 * on the page with an error and a working retry; a double submit must send one request, so the
 * backend creates one record; the offline banner must follow the network.
 */
for (const locale of ['tr', 'en'] as const) {
  test(`a dropped connection on sign-in shows an error and a retry signs in (${locale})`, async ({ page }, testInfo) => {
    const api = await installMockApi(page, locale);
    api.dropOnce.add('POST /auth/login');

    await page.goto('/login');
    await waitForApp(page);
    await page.getByLabel(t(locale, 'auth', 'login.email')).fill(member.email);
    await page.getByLabel(t(locale, 'auth', 'login.password')).fill(PASSWORD);
    const submit = page.getByRole('button', { name: t(locale, 'auth', 'login.submit') });
    await submit.click();

    await expect(page.getByRole('alert').filter({ hasText: /\S/ }).first()).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
    await snap(page, testInfo, '1-connection-dropped');

    await submit.click();
    await expect(page).toHaveURL(/\/map$/);
    expect(api.count('POST /auth/login')).toBe(2);
  });

  test(`a double click on create account sends one registration (${locale})`, async ({ page }, testInfo) => {
    const api = await installMockApi(page, locale);
    api.delays.set('POST /auth/register', 1_500);

    await page.goto('/register');
    await waitForApp(page);
    await page.getByLabel(t(locale, 'auth', 'register.displayName')).fill('CX F11 Tester');
    await page.getByLabel(t(locale, 'auth', 'register.email')).fill(member.email);
    await page.getByLabel(t(locale, 'auth', 'register.password'), { exact: true }).fill(PASSWORD);
    await page.getByLabel(t(locale, 'auth', 'register.confirmPassword')).fill(PASSWORD);
    await page.getByLabel(new RegExp(escapeRegExp(t(locale, 'auth', 'register.termsPrefix')))).check();
    await page.getByRole('button', { name: t(locale, 'auth', 'register.submit') }).dblclick();

    await expect(page).toHaveURL(/\/check-email/);
    await snap(page, testInfo, '1-after-double-click');
    expect(api.count('POST /auth/register')).toBe(1);
  });

  test(`the offline banner follows the network (${locale})`, async ({ page, context }, testInfo) => {
    await installMockApi(page, locale, { signedIn: member });
    await page.goto('/map');
    await waitForApp(page);
    const banner = page.getByText(t(locale, 'common', 'offline.banner'));
    await expect(banner).toHaveCount(0);

    await context.setOffline(true);
    await expect(banner).toBeVisible();
    await snap(page, testInfo, '1-offline');

    await context.setOffline(false);
    await expect(banner).toHaveCount(0);
  });
}

test('a double submit of a new spot uploads once and creates one spot (en)', async ({ page }, testInfo) => {
  const api = await installMockApi(page, 'en', { signedIn: member });
  api.delays.set('POST /parking/spots', 1_500);

  await page.goto('/map');
  await waitForApp(page);
  await spaGoto(page, '/upload');
  await page.getByLabel('Spot photo', { exact: true }).setInputFiles({
    name: 'spot.jpg',
    mimeType: 'image/jpeg',
    buffer: PHOTO_BYTES,
  });
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByText('Advanced coordinates').click();
  await page.getByLabel('Latitude').fill('41.01');
  await page.getByLabel('Longitude').fill('28.97');
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByText('Sedan', { exact: true }).click();
  await page.getByLabel('Parking context').selectOption('STREET_PARKING');
  await page.getByText('Legal', { exact: true }).click();
  await page.getByRole('button', { name: 'Continue' }).click();
  await page.getByRole('button', { name: 'Upload & create spot' }).dblclick();

  await expect(page.getByRole('heading', { name: 'Spot created' })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/spots/${SPOT_ID}$`));
  await snap(page, testInfo, '1-spot-created');
  expect(api.count('POST /media/upload')).toBe(1);
  expect(api.count('POST /parking/spots')).toBe(1);
});
