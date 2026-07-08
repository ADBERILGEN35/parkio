import { expect, test, type Page } from '@playwright/test';

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(1);
}

test.describe('landing public QA', () => {
  async function mockWaitlistSuccess(page: Page) {
    await page.route('**/api/v1/waitlist', async (route) => {
      await route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'accepted' }),
      });
    });
  }

  function headerBrand(page: Page) {
    return page
      .getByRole('navigation', { name: 'Landing page navigation' })
      .getByRole('link', { name: 'Parkio home' });
  }

  test('renders cleanly on desktop, tablet, and mobile viewports', async ({ page }) => {
    for (const viewport of [
      { width: 1440, height: 900 },
      { width: 768, height: 1024 },
      { width: 390, height: 844 },
    ]) {
      await page.setViewportSize(viewport);
      await page.goto('/');

      await expect(
        page.getByRole('heading', { name: 'Parking intelligence powered by real drivers.' }),
      ).toBeVisible();
      await expect(headerBrand(page)).toBeVisible();
      await expect(page.locator('form.landing-form')).toBeVisible();
      await expectNoHorizontalOverflow(page);
    }
  });

  test('supports dark mode and reduced motion preferences', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'reduce' });
    await page.goto('/');

    await expect(
      page.getByRole('heading', { name: 'Parking intelligence powered by real drivers.' }),
    ).toBeVisible();
    await expect(page.getByText('Hosted-beta preparation').first()).toBeVisible();

    const reducedMotion = await page.evaluate(() =>
      window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    );
    expect(reducedMotion).toBe(true);
  });

  test('keeps keyboard navigation, FAQ, and waitlist validation usable', async ({ page }) => {
    await mockWaitlistSuccess(page);
    await page.goto('/');
    await expect(page.locator('[data-route-focus]')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(
      page.getByRole('main').getByRole('link', { name: /join beta waitlist/i }).first(),
    ).toBeFocused();

    await page.getByRole('button', { name: /join beta waitlist/i }).last().click();
    await expect(page.getByRole('alert')).toContainText('Enter a valid email address.');

    await page.getByLabel(/^email/i).fill('driver@parkio.dev');
    await page.getByRole('button', { name: /join beta waitlist/i }).last().click();
    await expect(page.getByRole('alert')).toContainText(
      'Consent is required to receive beta updates.',
    );

    await page.getByLabel(/I agree to receive Parkio beta updates/i).check();
    await page.getByRole('button', { name: /join beta waitlist/i }).last().click();
    await expect(
      page.getByRole('heading', { name: 'Your Parkio beta interest is ready.' }),
    ).toBeVisible();

    const liveFaq = page.locator('details').filter({ hasText: 'Is Parkio live?' });
    await liveFaq.locator('summary').click();
    await expect(liveFaq.getByText(/public production service is not live yet/i)).toBeVisible();
  });

  test('keeps public and protected routing behavior honest', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL(/\/$/);

    await page.goto('/login');
    await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible();

    await page.goto('/terms');
    await expect(page.getByRole('heading', { name: 'Terms of Service' })).toBeVisible();
    await expect(page.getByText(/not final legal advice or a final public-launch legal review/i)).toBeVisible();

    await page.goto('/privacy');
    await expect(page.getByRole('heading', { name: 'Privacy Policy' })).toBeVisible();
    await expect(page.getByText(/stores your email, consent receipt time/i)).toBeVisible();

    await page.goto('/map');
    await expect(page).toHaveURL(/\/login$/);
  });
});
