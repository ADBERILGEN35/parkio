/**
 * Chromium cookie / Origin / SameSite acceptance against disposable auth-service.
 *
 * The JUnit driver owns the app/evil static origins and SpringBoot RANDOM_PORT auth.
 * This process only drives Chromium against those already-running origins.
 *
 * Scope (lab only):
 *   - Real Chromium cookie jar against auth over HTTP on localhost
 *   - localhost is a secure context, so Secure+HttpOnly+SameSite=Strict cookies stick
 *   - Legitimate app origin on http://localhost:<appPort>
 *   - Cross-site attacker on http://127.0.0.1:<evilPort>
 *
 * Explicitly NOT proven:
 *   - Gateway CORS / credentials reflection / X-Gateway-Auth stamping
 *   - TLS sibling hosts (app.parkio.dev ↔ api.parkio.dev)
 *   - Production secret non-exposure (lab pages inject the gateway secret)
 *
 * Browser limits vs MockMvc (#125):
 *   - fetch() cannot set the forbidden Origin header; foreign-Origin CSRF is
 *     exercised by posting from the evil document
 *   - Cross-origin POSTs always carry Origin, so missing-Origin stays MockMvc-only
 *
 * Env (required when PARKIO_CSRF_BROWSER=1):
 *   PARKIO_CSRF_BROWSER=1
 *   PARKIO_CSRF_AUTH_BASE=http://localhost:<authPort>
 *   PARKIO_CSRF_GATEWAY_SECRET=...   (informational; already baked into page HTML)
 *   PARKIO_CSRF_APP_ORIGIN=http://localhost:<appPort>
 *   PARKIO_CSRF_EVIL_ORIGIN=http://127.0.0.1:<evilPort>
 *   PARKIO_CSRF_EMAIL / PARKIO_CSRF_PASSWORD
 *   PARKIO_CSRF_RESULT_FILE=<json path>
 */

import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`missing ${name}`);
  return value;
}

function writeResult(payload) {
  writeFileSync(required('PARKIO_CSRF_RESULT_FILE'), JSON.stringify(payload, null, 2));
}

async function loadChromium() {
  // ESM does not honor NODE_PATH for bare specifiers from scripts/. Resolve Playwright
  // from the path the JUnit driver discovered under frontend/apps/web/node_modules.
  const playwrightRoot = required('PARKIO_CSRF_PLAYWRIGHT_PACKAGE');
  const entry = join(playwrightRoot, 'index.mjs');
  const mod = await import(pathToFileURL(entry).href);
  if (!mod.chromium) {
    throw new Error(`@playwright/test at ${playwrightRoot} has no chromium export`);
  }
  return mod.chromium;
}

async function main() {
  if (process.env.PARKIO_CSRF_BROWSER !== '1') {
    writeResult({ status: 'skipped', reason: 'PARKIO_CSRF_BROWSER is not 1' });
    return;
  }

  const chromium = await loadChromium();
  const authBase = required('PARKIO_CSRF_AUTH_BASE').replace(/\/$/, '');
  const appOrigin = required('PARKIO_CSRF_APP_ORIGIN');
  const evilOrigin = required('PARKIO_CSRF_EVIL_ORIGIN');
  const email = required('PARKIO_CSRF_EMAIL');
  const password = required('PARKIO_CSRF_PASSWORD');

  if (!authBase.startsWith('http://localhost:')) {
    throw new Error(`auth base must be http://localhost:<port> for SameSite lab; got ${authBase}`);
  }
  if (!appOrigin.startsWith('http://localhost:')) {
    throw new Error(`app origin must be http://localhost:<port>; got ${appOrigin}`);
  }
  if (!evilOrigin.startsWith('http://127.0.0.1:')) {
    throw new Error(`evil origin must be http://127.0.0.1:<port>; got ${evilOrigin}`);
  }

  const browser = await chromium.launch({ headless: true });
  const outcome = {
    status: 'running',
    appOrigin,
    evilOrigin,
    authBase,
    checks: {},
    limitations: [
      'Not via gateway; lab page injects X-Gateway-Auth',
      'HTTP localhost secure-context lab, not TLS sibling subdomains',
      'Missing Origin cannot be exercised by Chromium cross-origin fetch (covered by MockMvc #125)',
    ],
  };

  try {
    const appContext = await browser.newContext();
    const appPage = await appContext.newPage();
    await appPage.goto(appOrigin);

    const login = await appPage.evaluate(
      async ({ email, password }) =>
        window.__parkio.call('/api/v1/auth/login', { body: { email, password } }),
      { email, password },
    );
    if (login.status !== 200) {
      throw new Error(`login failed HTTP ${login.status}: ${JSON.stringify(login.json)}`);
    }
    if (login.json?.refreshToken) {
      throw new Error('browser login leaked refreshToken in JSON body');
    }
    outcome.checks.loginHttp = login.status;

    const cookies = await appContext.cookies(`${authBase}/api/v1/auth/refresh-token`);
    const refreshCookies = cookies.filter((c) => c.name === 'parkio_refresh');
    outcome.checks.refreshCookieCount = refreshCookies.length;
    outcome.checks.cookieSecure = refreshCookies.every((c) => c.secure === true);
    outcome.checks.cookieHttpOnly = refreshCookies.every((c) => c.httpOnly === true);
    outcome.checks.cookieSameSite = refreshCookies.map((c) => c.sameSite);
    if (refreshCookies.length === 0) {
      throw new Error('login did not set parkio_refresh in the Chromium cookie jar');
    }
    if (!outcome.checks.cookieSecure || !outcome.checks.cookieHttpOnly) {
      throw new Error(`cookie flags unexpected: ${JSON.stringify(refreshCookies)}`);
    }
    if (!refreshCookies.every((c) => String(c.sameSite).toLowerCase() === 'strict')) {
      throw new Error(`SameSite not Strict: ${JSON.stringify(refreshCookies)}`);
    }

    const refreshOk = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', { body: {} }),
    );
    outcome.checks.allowedOriginRefresh = refreshOk.status;
    if (refreshOk.status !== 200) {
      throw new Error(`allowed-origin refresh failed: ${JSON.stringify(refreshOk)}`);
    }
    if (refreshOk.json?.refreshToken) {
      throw new Error('refresh leaked refreshToken in JSON body');
    }

    await appContext.clearCookies();
    const loginForReject = await appPage.evaluate(
      async ({ email, password }) =>
        window.__parkio.call('/api/v1/auth/login', { body: { email, password } }),
      { email, password },
    );
    if (loginForReject.status !== 200) {
      throw new Error(`pre-reject login failed HTTP ${loginForReject.status}`);
    }
    outcome.accessTokenBeforeRejects = loginForReject.json?.accessToken ?? null;
    outcome.refreshCookieValuesBeforeRejects = (
      await appContext.cookies(`${authBase}/api/v1/auth/refresh-token`)
    )
      .filter((c) => c.name === 'parkio_refresh')
      .map((c) => c.value);

    const forgedMobile = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', {
        body: {},
        headers: { 'X-Parkio-Client': 'mobile' },
      }),
    );
    outcome.checks.forgedMobileWithOrigin = forgedMobile.status;
    if (forgedMobile.status !== 200) {
      throw new Error(`forged mobile+Origin should stay on cookie path: ${JSON.stringify(forgedMobile)}`);
    }
    if (forgedMobile.json?.refreshToken) {
      throw new Error('forged mobile+Origin leaked refreshToken in JSON body');
    }

    await appContext.clearCookies();
    const loginLive = await appPage.evaluate(
      async ({ email, password }) =>
        window.__parkio.call('/api/v1/auth/login', { body: { email, password } }),
      { email, password },
    );
    if (loginLive.status !== 200) {
      throw new Error(`live login failed HTTP ${loginLive.status}`);
    }
    const liveCookies = await appContext.cookies([
      `${authBase}/api/v1/auth/refresh-token`,
      `${authBase}/api/v1/auth/logout`,
    ]);
    outcome.refreshCookieValuesBeforeCrossSite = liveCookies
      .filter((c) => c.name === 'parkio_refresh')
      .map((c) => c.value);

    const evilContext = await browser.newContext();
    await evilContext.addCookies(
      liveCookies.map((c) => ({
        name: c.name,
        value: c.value,
        domain: c.domain,
        path: c.path,
        httpOnly: c.httpOnly,
        secure: c.secure,
        sameSite: c.sameSite,
        expires: c.expires,
      })),
    );
    const evilPage = await evilContext.newPage();
    const captured = [];
    evilPage.on('request', (req) => {
      if (req.url().includes('/api/v1/auth/refresh-token') || req.url().includes('/api/v1/auth/logout')) {
        const headers = req.headers();
        captured.push({
          url: req.url(),
          method: req.method(),
          origin: headers.origin ?? headers.Origin ?? '',
        });
      }
    });
    await evilPage.goto(evilOrigin);

    const crossRefresh = await evilPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', { body: {} }),
    );
    outcome.checks.crossSiteRefreshStatus = crossRefresh.status;
    outcome.checks.crossSiteRefreshCapture = captured.filter(
      (c) => c.url.includes('/refresh-token') && c.method === 'POST',
    );
    if (crossRefresh.status === 200) {
      throw new Error(`cross-site refresh unexpectedly succeeded: ${JSON.stringify(crossRefresh)}`);
    }
    const refreshCapture = outcome.checks.crossSiteRefreshCapture[0];
    if (!refreshCapture) {
      throw new Error('did not capture cross-site refresh request headers');
    }
    // Cookie omission is asserted from the actual HTTP request received by the lab server.
    // Playwright request.headers() intentionally excludes cookie-related headers.
    // Playwright's request.headers() may omit Origin; when present it must be the evil page.
    if (refreshCapture.origin && refreshCapture.origin !== evilOrigin) {
      throw new Error(`expected Origin ${evilOrigin}, got ${refreshCapture.origin}`);
    }
    outcome.checks.crossSiteRefreshOriginObserved = refreshCapture.origin || null;

    const crossLogout = await evilPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/logout', { body: {} }),
    );
    outcome.checks.crossSiteLogoutStatus = crossLogout.status;
    outcome.checks.crossSiteLogoutCapture = captured.filter(
      (c) => c.url.includes('/logout') && c.method === 'POST',
    );
    if (crossLogout.status === 200 || crossLogout.status === 204) {
      throw new Error(`cross-site logout unexpectedly succeeded: ${JSON.stringify(crossLogout)}`);
    }
    const logoutCapture = outcome.checks.crossSiteLogoutCapture.at(-1);
    if (!logoutCapture) {
      throw new Error('did not capture cross-site logout request');
    }

    const logoutOk = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/logout', { body: {} }),
    );
    outcome.checks.allowedOriginLogout = logoutOk.status;
    if (logoutOk.status !== 204) {
      throw new Error(`allowed-origin logout failed: ${JSON.stringify(logoutOk)}`);
    }

    outcome.status = 'passed';
    writeResult(outcome);
  } catch (error) {
    outcome.status = 'failed';
    outcome.error = String(error?.stack || error);
    writeResult(outcome);
    throw error;
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  try {
    writeResult({ status: 'failed', error: String(error?.stack || error) });
  } catch {
    /* ignore secondary write failures */
  }
  console.error(error);
  process.exit(1);
});
