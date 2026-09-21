import { expect, request, test, type Browser } from '@playwright/test';

const REQUIRED_CRAWLER_COPY = [
  'Oğuzhan Taşyaran',
  'Park alanı keşfet',
  'https://www.linkedin.com/in/oguzhan-tasyaran/',
  'https://www.linkedin.com/company/parkio-app',
  'https://app.parkio.dev/explore',
  'Kayıtlar açıldığında',
] as const;

test('serves the complete static marketing surface with correct content types', async ({ request: api }) => {
  const routes = [
    ['/', 200, /^text\/html/],
    ['/privacy/', 200, /^text\/html/],
    ['/terms/', 200, /^text\/html/],
    ['/waitlist/confirm/', 200, /^text\/html/],
    ['/waitlist/unsubscribe/', 200, /^text\/html/],
    ['/i18n.js', 200, /javascript/],
    ['/waitlist.js', 200, /javascript/],
    ['/robots.txt', 200, /^text\/plain/],
    ['/sitemap.xml', 200, /^application\/xml/],
    ['/404.html', 200, /^text\/html/],
    ['/not-a-real-marketing-route', 404, /^text\/html/],
  ] as const;

  for (const [path, status, contentType] of routes) {
    const response = await api.get(path);
    expect(response.status(), path).toBe(status);
    expect(response.headers()['content-type'], path).toMatch(contentType);
  }
});

test('returns equivalent server HTML to browser and crawler user agents', async ({ baseURL }) => {
  const browserClient = await request.newContext({
    baseURL,
    extraHTTPHeaders: { 'user-agent': 'Mozilla/5.0 ParkioStaticValidation' },
  });
  const crawlerClient = await request.newContext({
    baseURL,
    extraHTTPHeaders: { 'user-agent': 'Googlebot/2.1 (+http://www.google.com/bot.html)' },
  });

  try {
    const browserHtml = await (await browserClient.get('/')).text();
    const crawlerHtml = await (await crawlerClient.get('/')).text();
    expect(crawlerHtml).toBe(browserHtml);
    for (const copy of REQUIRED_CRAWLER_COPY) {
      expect(browserHtml).toContain(copy);
    }
    expect(browserHtml).toMatch(/lang=["']tr["']/i);
  } finally {
    await browserClient.dispose();
    await crawlerClient.dispose();
  }
});

test('remains substantive with JavaScript disabled in Turkish', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();

  try {
    await page.goto(baseURL ?? '/');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Oğuzhan Taşyaran' })).toBeVisible();
    await expect(page.getByRole('link', { name: /Park alanı keşfet/i }).first()).toBeVisible();
    await expect(page.locator('#waitlist-form')).toBeVisible();
    await expect(page.locator('#waitlist-email')).toBeVisible();
  } finally {
    await context.close();
  }
});

test('language switch persists and updates waitlist copy', async ({ page, baseURL }) => {
  await page.goto(baseURL ?? '/');
  await expect(page.locator('html')).toHaveAttribute('lang', 'tr');
  await page.getByRole('button', { name: 'EN', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.getByRole('button', { name: /Join the notification list/i })).toBeVisible();
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await page.getByRole('button', { name: 'TR', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'tr');
});

test('waitlist mock submit via explicit meta shows success without claiming live provider', async ({
  page,
  baseURL,
}) => {
  // Production bundles use meta=api|unavailable and ignore ?waitlistMock=1.
  // Local/test mock requires rewriting the mode meta before scripts bind.
  await page.route('**/*', async (route) => {
    const request = route.request();
    if (request.resourceType() !== 'document') {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    const headers = response.headers();
    let body = await response.text();
    body = body.replace(
      /(<meta name="parkio-waitlist-mode" content=")[^"]*(")/,
      '$1mock$2',
    );
    await route.fulfill({
      status: response.status(),
      headers,
      body,
    });
  });

  await page.goto(`${baseURL ?? '/'}#waitlist`);
  await page.locator('#waitlist-email').fill('synthetic-w01a@example.com');
  await page.locator('#waitlist-consent').check();
  await page.locator('#waitlist-form button[type="submit"]').click();
  await expect(page.locator('[data-waitlist-feedback]')).toBeVisible();
  await expect(page.locator('[data-waitlist-feedback]')).toContainText(/Teşekkürler|Thanks/i);
  await expect(page.locator('[data-waitlist-isolated-note]')).toBeVisible();
});

test('waitlist query mock bypass cannot fake success when meta remains api', async ({ page, baseURL }) => {
  await page.goto(`${baseURL ?? '/'}?waitlistMock=1#waitlist`);
  await page.locator('#waitlist-email').fill('synthetic-w01a-bypass@example.com');
  await page.locator('#waitlist-consent').check();
  await page.locator('#waitlist-form button[type="submit"]').click();
  await expect(page.locator('[data-waitlist-feedback]')).toBeVisible();
  // API call fails in static marketing harness — must not show mock success.
  await expect(page.locator('[data-waitlist-feedback]')).not.toContainText(/Teşekkürler|Thanks/i);
  await expect(page.locator('[data-waitlist-isolated-note]')).toBeHidden();
});

for (const width of [360, 390, 768, 1440]) {
  test(`has no horizontal overflow or clipped primary CTA at ${width}px`, async ({ browser, baseURL }) => {
    await assertResponsiveLayout(browser, baseURL ?? '/', width);
  });
}

async function assertResponsiveLayout(browser: Browser, url: string, width: number): Promise<void> {
  const context = await browser.newContext({ viewport: { width, height: 900 } });
  const page = await context.newPage();

  try {
    await page.goto(url);
    await expect(page.locator('main')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Oğuzhan Taşyaran' })).toBeVisible();
    await expect(page.locator('#primary-product-cta')).toBeVisible();

    const layout = await page.evaluate(() => {
      const cta = document.querySelector<HTMLElement>('#primary-product-cta');
      const rect = cta?.getBoundingClientRect();
      return {
        viewportWidth: window.innerWidth,
        documentWidth: document.documentElement.scrollWidth,
        bodyWidth: document.body.scrollWidth,
        ctaLeft: rect?.left ?? -1,
        ctaRight: rect?.right ?? Number.POSITIVE_INFINITY,
      };
    });

    expect(layout.documentWidth).toBeLessThanOrEqual(layout.viewportWidth);
    expect(layout.bodyWidth).toBeLessThanOrEqual(layout.viewportWidth);
    expect(layout.ctaLeft).toBeGreaterThanOrEqual(0);
    expect(layout.ctaRight).toBeLessThanOrEqual(layout.viewportWidth);
  } finally {
    await context.close();
  }
}
