/**
 * Chromium driver for the gateway HTTPS sibling-host CSRF lab.
 * Pages must NOT contain X-Gateway-Auth — the gateway stamps it.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

function required(name) {
  const v = process.env[name];
  if (!v) throw new Error(`missing ${name}`);
  return v;
}

function writeResult(payload) {
  writeFileSync(required('PARKIO_CSRF_RESULT_FILE'), JSON.stringify(payload, null, 2));
}

function readCaptures() {
  const path = required('PARKIO_CSRF_CAPTURE_FILE');
  try {
    return readFileSync(path, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((line) => JSON.parse(line));
  } catch {
    return [];
  }
}

async function loadChromium() {
  const root = required('PARKIO_CSRF_PLAYWRIGHT_PACKAGE');
  const mod = await import(pathToFileURL(join(root, 'index.mjs')).href);
  return mod.chromium;
}

async function main() {
  if (process.env.PARKIO_CSRF_GATEWAY_BROWSER !== '1') {
    writeResult({ status: 'skipped', reason: 'PARKIO_CSRF_GATEWAY_BROWSER is not 1' });
    process.exit(2);
  }

  const chromium = await loadChromium();
  const appOrigin = required('PARKIO_CSRF_APP_ORIGIN');
  const evilOrigin = required('PARKIO_CSRF_EVIL_ORIGIN');
  const crossOrigin = required('PARKIO_CSRF_CROSS_ORIGIN');
  const apiOrigin = required('PARKIO_CSRF_API_ORIGIN');
  const email = required('PARKIO_CSRF_EMAIL');
  const password = required('PARKIO_CSRF_PASSWORD');

  const browser = await chromium.launch({
    headless: true,
    args: [
      '--host-resolver-rules=MAP *.parkio.test 127.0.0.1, MAP cross.example.test 127.0.0.1, MAP parkio.test 127.0.0.1',
    ],
  });

  const outcome = {
    status: 'running',
    appOrigin,
    evilOrigin,
    crossOrigin,
    apiOrigin,
    checks: {},
    layers: {},
    captures: [],
  };

  const contextOpts = { ignoreHTTPSErrors: true };
  try {
    const appContext = await browser.newContext(contextOpts);
    const appPage = await appContext.newPage();
    await appPage.goto(`${appOrigin}/`, { waitUntil: 'domcontentloaded' });

    const login = await appPage.evaluate(
      async ({ email, password }) =>
        window.__parkio.call('/api/v1/auth/login', { body: { email, password } }),
      { email, password },
    );
    outcome.checks.login = login;
    if (login.status !== 200) {
      throw new Error(`allowed login failed: ${JSON.stringify(login)}`);
    }
    if (login.json?.refreshToken) {
      throw new Error('login leaked refreshToken in JSON');
    }
    const cookiesAfterLogin = await appContext.cookies(`${apiOrigin}/api/v1/auth/refresh-token`);
    const refreshCookies = cookiesAfterLogin.filter((c) => c.name === 'parkio_refresh');
    outcome.checks.cookieCount = refreshCookies.length;
    outcome.checks.cookieSecure = refreshCookies.every((c) => c.secure);
    outcome.checks.cookieHttpOnly = refreshCookies.every((c) => c.httpOnly);
    outcome.checks.cookieSameSite = refreshCookies.map((c) => c.sameSite);
    if (refreshCookies.length === 0) {
      throw new Error('no parkio_refresh cookie after login via gateway');
    }

    const refreshOk = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', { body: {} }),
    );
    outcome.checks.allowedRefresh = refreshOk;
    if (refreshOk.status !== 200) {
      throw new Error(`allowed refresh failed: ${JSON.stringify(refreshOk)}`);
    }

    const forgedMobile = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', {
        body: {},
        headers: { 'X-Parkio-Client': 'mobile' },
      }),
    );
    outcome.checks.forgedMobile = forgedMobile;
    if (forgedMobile.status !== 200) {
      throw new Error(`forged mobile should stay cookie path: ${JSON.stringify(forgedMobile)}`);
    }
    if (forgedMobile.json?.refreshToken) {
      throw new Error('forged mobile leaked body refreshToken');
    }

    await appContext.clearCookies();
    const login2 = await appPage.evaluate(
      async ({ email, password }) =>
        window.__parkio.call('/api/v1/auth/login', { body: { email, password } }),
      { email, password },
    );
    if (login2.status !== 200) throw new Error(`re-login failed: ${JSON.stringify(login2)}`);
    const liveCookies = await appContext.cookies([
      `${apiOrigin}/api/v1/auth/refresh-token`,
      `${apiOrigin}/api/v1/auth/logout`,
    ]);
    outcome.refreshCookieValuesBeforeSibling = liveCookies
      .filter((c) => c.name === 'parkio_refresh')
      .map((c) => c.value);

    const evilContext = await browser.newContext(contextOpts);
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
    await evilPage.goto(`${evilOrigin}/`, { waitUntil: 'domcontentloaded' });
    const siblingRefresh = await evilPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', { body: {} }),
    );
    outcome.checks.siblingRefresh = siblingRefresh;
    if (siblingRefresh.status === 200) {
      throw new Error(`sibling refresh MUST NOT succeed: ${JSON.stringify(siblingRefresh)}`);
    }
    outcome.layers.siblingRefresh =
      siblingRefresh.status === 0 ? 'gateway-cors-or-network' : `http-${siblingRefresh.status}`;

    const siblingLogout = await evilPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/logout', { body: {} }),
    );
    outcome.checks.siblingLogout = siblingLogout;
    if (siblingLogout.status === 200 || siblingLogout.status === 204) {
      throw new Error(`sibling logout MUST NOT succeed: ${JSON.stringify(siblingLogout)}`);
    }
    outcome.layers.siblingLogout =
      siblingLogout.status === 0 ? 'gateway-cors-or-network' : `http-${siblingLogout.status}`;

    const crossContext = await browser.newContext(contextOpts);
    await crossContext.addCookies(
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
    const crossPage = await crossContext.newPage();
    await crossPage.goto(`${crossOrigin}/`, { waitUntil: 'domcontentloaded' });
    const crossRefresh = await crossPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', { body: {} }),
    );
    outcome.checks.crossRefresh = crossRefresh;
    if (crossRefresh.status === 200) {
      throw new Error(`cross-site refresh MUST NOT succeed: ${JSON.stringify(crossRefresh)}`);
    }
    outcome.layers.crossRefresh =
      crossRefresh.status === 0 ? 'gateway-cors-or-network-or-samesite' : `http-${crossRefresh.status}`;

    const logoutAll = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/logout-all', { body: {} }),
    );
    outcome.checks.logoutAllNoBearer = logoutAll;
    if (logoutAll.status === 200 || logoutAll.status === 204) {
      throw new Error(`logout-all without bearer must fail: ${JSON.stringify(logoutAll)}`);
    }
    outcome.layers.logoutAllNoBearer = `http-${logoutAll.status}`;

    const logoutOk = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/logout', { body: {} }),
    );
    outcome.checks.allowedLogout = logoutOk;
    if (logoutOk.status !== 204) {
      throw new Error(`allowed logout failed: ${JSON.stringify(logoutOk)}`);
    }

    outcome.captures = readCaptures();
    const authBound = outcome.captures.filter((c) => c.path && c.path.includes('/api/v1/auth/'));
    if (authBound.length === 0) {
      throw new Error('no server-side captures at auth — gateway may not have forwarded');
    }
    if (!authBound.every((c) => c.hasGatewayAuth === true)) {
      throw new Error(`gateway did not stamp X-Gateway-Auth: ${JSON.stringify(authBound)}`);
    }
    const siblingReached = authBound.filter(
      (c) => c.origin === evilOrigin && (c.path.includes('refresh') || c.path.includes('logout')),
    );
    outcome.checks.siblingReachedAuth = siblingReached;
    for (const row of siblingReached) {
      if (row.status === 200 || row.status === 204) {
        throw new Error(`auth accepted sibling mutation: ${JSON.stringify(row)}`);
      }
      if (row.hasParkioRefreshCookie && row.status !== 403) {
        throw new Error(`expected Origin 403 when sibling cookie present: ${JSON.stringify(row)}`);
      }
    }

    outcome.status = 'passed';
    writeResult(outcome);
  } catch (error) {
    outcome.status = 'failed';
    outcome.error = String(error?.stack || error);
    outcome.captures = readCaptures();
    writeResult(outcome);
    throw error;
  } finally {
    await browser.close();
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
