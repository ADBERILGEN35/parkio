import { expect, request, test, type Browser } from '@playwright/test';

const REQUIRED_CRAWLER_COPY = [
  'Oğuzhan Taşyaran',
  'How Parkio works as a business',
  'Roadmap',
  'production infrastructure is live',
  'Public access is prepared and remains disabled',
] as const;

test('serves the complete static marketing surface with correct content types', async ({ request: api }) => {
  const routes = [
    ['/', 200, /^text\/html/],
    ['/privacy/', 200, /^text\/html/],
    ['/terms/', 200, /^text\/html/],
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
  } finally {
    await browserClient.dispose();
    await crawlerClient.dispose();
  }
});

test('remains substantive with JavaScript disabled', async ({ browser, baseURL }) => {
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();

  try {
    await page.goto(baseURL ?? '/');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'How Parkio works as a business' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Oğuzhan Taşyaran' })).toBeVisible();
    await expect(page.getByText('Public access is prepared and remains disabled', { exact: false })).toBeVisible();
  } finally {
    await context.close();
  }
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
