import { expect, request, test, type Browser, type Page } from '@playwright/test';

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
  await expect(page.getByRole('button', { name: /Join the waitlist/i })).toBeVisible();
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await page.getByRole('button', { name: 'TR', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'tr');
});

test('hero waitlist CTA targets the form and nav uses waitlist labels', async ({ page, baseURL }) => {
  await page.goto(baseURL ?? '/');
  const heroCta = page.locator('#hero-waitlist-cta');
  await expect(heroCta).toBeVisible();
  await expect(heroCta).toHaveAttribute('href', '#waitlist');
  await expect(heroCta).toHaveText(/Bekleme listesine katıl/i);
  await expect(page.getByRole('navigation', { name: /Ana navigasyon|Primary navigation/i }).getByRole('link', { name: /Bekleme listesi/i })).toBeVisible();
  await expect(page.locator('#primary-product-cta')).toBeVisible();
  await expect(page.getByRole('contentinfo').getByRole('link', { name: /Giriş yap/i })).toBeVisible();
  await heroCta.click();
  await expect(page.locator('#waitlist-form')).toBeInViewport();
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
  await page.locator('#waitlist-full-name').fill('Ayşe Yılmaz');
  await page.locator('#waitlist-email').fill('synthetic-w01a@example.com');
  await page.locator('#waitlist-consent').check();
  await page.locator('#waitlist-form button[type="submit"]').click();
  await expect(page.locator('[data-waitlist-feedback]')).toBeVisible();
  await expect(page.locator('[data-waitlist-feedback]')).toContainText(/Teşekkürler|Thanks/i);
  await expect(page.locator('[data-waitlist-isolated-note]')).toBeVisible();
});

/** Answers the waitlist API (CORS preflight included) with one fixed response (CL-F19). */
async function mockWaitlistApi(
  page: Page,
  status: number,
  body: string,
  contentType = 'application/json',
) {
  await page.route('https://api.parkio.dev/api/v1/waitlist**', async (route) => {
    const cors = {
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'POST, OPTIONS',
      'access-control-allow-headers': 'content-type, accept',
    };
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: cors });
      return;
    }
    await route.fulfill({ status, headers: { ...cors, 'content-type': contentType }, body });
  });
}

async function submitWaitlist(page: Page, baseURL: string | undefined) {
  await page.goto(`${baseURL ?? '/'}?lang=en#waitlist`);
  await page.locator('#waitlist-full-name').fill('Ayse Yilmaz');
  await page.locator('#waitlist-email').fill('synthetic-clf19@example.com');
  await page.locator('#waitlist-consent').check();
  await page.locator('#waitlist-form button[type="submit"]').click();
  await expect(page.locator('[data-waitlist-feedback]')).toBeVisible();
}

test('a 503 without the delivery-failure code is not reported as a saved signup (CL-F19)', async ({
  page,
  baseURL,
}) => {
  await mockWaitlistApi(page, 503, '<html>Service Unavailable</html>', 'text/html');
  await submitWaitlist(page, baseURL);
  const feedback = page.locator('[data-waitlist-feedback]');
  await expect(feedback).toHaveAttribute('data-feedback-key', 'waitlist.error.generic');
  await expect(feedback).not.toContainText(/saved/i);
});

test('the explicit delivery-failure code is reported as a saved signup (CL-F19)', async ({ page, baseURL }) => {
  await mockWaitlistApi(page, 503, JSON.stringify({ code: 'WAITLIST_EMAIL_DELIVERY_FAILED' }));
  await submitWaitlist(page, baseURL);
  await expect(page.locator('[data-waitlist-feedback]')).toHaveAttribute(
    'data-feedback-key',
    'waitlist.error.delivery',
  );
});

for (const lang of ['en', 'tr']) {
  test(`an invalid confirmation link says so without suggesting a retry (${lang}, CL-F19)`, async ({ page }) => {
    await mockWaitlistApi(page, 400, JSON.stringify({ code: 'WAITLIST_TOKEN_INVALID' }));
    await page.goto(`/waitlist/confirm/?token=fixture-expired&lang=${lang}`);
    await page.locator('#waitlist-confirm-form button[type="submit"]').click();
    const feedback = page.locator('[data-waitlist-feedback]');
    await expect(feedback).toHaveAttribute('data-feedback-key', 'waitlist.page.confirm.invalid');
    await expect(feedback).not.toContainText(/try again|tekrar dene/i);
  });
}

test('an unsubscribe server error is not shown as an invalid link (CL-F19)', async ({ page }) => {
  await mockWaitlistApi(page, 503, '', 'text/plain');
  await page.goto('/waitlist/unsubscribe/?token=fixture&lang=en');
  await page.locator('#waitlist-withdraw-form button[type="submit"]').click();
  await expect(page.locator('[data-waitlist-feedback]')).toHaveAttribute(
    'data-feedback-key',
    'waitlist.page.token.serverError',
  );
});

test('waitlist query mock bypass cannot fake success when meta remains api', async ({ page, baseURL }) => {
  await page.goto(`${baseURL ?? '/'}?waitlistMock=1#waitlist`);
  await page.locator('#waitlist-full-name').fill('Ayşe Yılmaz');
  await page.locator('#waitlist-email').fill('synthetic-w01a-bypass@example.com');
  await page.locator('#waitlist-consent').check();
  await page.locator('#waitlist-form button[type="submit"]').click();
  await expect(page.locator('[data-waitlist-feedback]')).toBeVisible();
  // API call fails in static marketing harness — must not show mock success.
  await expect(page.locator('[data-waitlist-feedback]')).not.toContainText(/Teşekkürler|Thanks/i);
  await expect(page.locator('[data-waitlist-isolated-note]')).toBeHidden();
});

// CL-F39.5: the local server sends the .htaccess headers, so these run under the served CSP.
const HTML_PAGES = ['/', '/privacy/', '/terms/', '/waitlist/confirm/', '/waitlist/unsubscribe/', '/404.html'] as const;

/** Records securitypolicyviolation events of each document the page loads. */
async function recordCspViolations(page: Page): Promise<() => Promise<string[]>> {
  await page.addInitScript(() => {
    const violations: string[] = [];
    Object.defineProperty(window, '__cspViolations', { value: violations });
    document.addEventListener('securitypolicyviolation', (event) => {
      violations.push(`${event.effectiveDirective} ${event.blockedURI || 'inline'}`);
    });
  });
  return () => page.evaluate(() => (window as unknown as { __cspViolations: string[] }).__cspViolations);
}

test('serves every page with a script policy without unsafe-inline, HSTS and no CSP violations', async ({
  page,
  baseURL,
}) => {
  const violations = await recordCspViolations(page);

  for (const path of HTML_PAGES) {
    const response = await page.goto(`${baseURL}${path}`);
    const headers = response?.headers() ?? {};
    const scriptSrc = (headers['content-security-policy'] ?? '')
      .split(';')
      .map((directive) => directive.trim())
      .find((directive) => directive.startsWith('script-src'));
    expect(scriptSrc, path).toBe("script-src 'self'");
    expect(headers['strict-transport-security'], path).toMatch(/^max-age=\d+/);
    await page.waitForLoadState('networkidle');
    expect(await violations(), path).toEqual([]);
  }
});

test('keeps the JSON-LD data block, which the script policy does not apply to', async ({ page, baseURL }) => {
  const violations = await recordCspViolations(page);
  await page.goto(baseURL ?? '/');

  const jsonLd = JSON.parse((await page.locator('script[type="application/ld+json"]').textContent()) ?? '');
  expect(jsonLd['@graph'].length).toBeGreaterThan(0);
  expect(await violations()).toEqual([]);
});

test('the served CSP blocks an injected inline script', async ({ page, baseURL }) => {
  const violations = await recordCspViolations(page);
  await page.route('**/privacy/', async (route) => {
    const response = await route.fetch();
    const body = (await response.text()).replace(
      '</body>',
      '<script>document.documentElement.dataset.inlineScript = "ran";</script></body>',
    );
    await route.fulfill({ response, body });
  });

  await page.goto(`${baseURL}/privacy/`);

  await expect.poll(violations).toContainEqual(expect.stringMatching(/^script-src/));
  await expect(page.locator('html')).not.toHaveAttribute('data-inline-script', 'ran');
});

test('waitlist pages localize their title without inline script', async ({ page, baseURL }) => {
  await page.goto(`${baseURL}/waitlist/confirm/`);
  await expect(page).toHaveTitle('Parkio | Bildirim listesi onayı');
  await page.getByRole('button', { name: 'EN', exact: true }).click();
  await expect(page).toHaveTitle('Parkio | Confirm notification list');

  await page.goto(`${baseURL}/waitlist/unsubscribe/?lang=tr`);
  await expect(page).toHaveTitle('Parkio | Bildirim listesinden çıkış');
  await page.getByRole('button', { name: 'EN', exact: true }).click();
  await expect(page).toHaveTitle('Parkio | Leave notification list');
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
