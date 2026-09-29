/**
 * Chromium driver for the gateway HTTPS sibling-host CSRF lab.
 * Pages must NOT contain X-Gateway-Auth — gateway / auth-direct stamp it.
 * Browser status 0 is never sufficient: every malicious attempt asserts
 * Postgres refresh-session + epoch unchanged and records edge/auth attribution.
 */
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  assertSessionUnchanged,
  attributeLayers,
  readJsonl,
  readSessionState,
} from './session-probe.mjs';

function required(name) {
  const v = process.env[name];
  if (!v) throw new Error(`missing ${name}`);
  return v;
}

function writeResult(payload) {
  writeFileSync(required('PARKIO_CSRF_RESULT_FILE'), JSON.stringify(payload, null, 2));
}

function nowIso() {
  return new Date().toISOString();
}

async function loadChromium() {
  const root = required('PARKIO_CSRF_PLAYWRIGHT_PACKAGE');
  const mod = await import(pathToFileURL(join(root, 'index.mjs')).href);
  return mod.chromium;
}

function liveRefreshCookies(cookies) {
  return cookies.filter((c) => c.name === 'parkio_refresh');
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
  const apiAuthDirectOrigin = required('PARKIO_CSRF_API_AUTH_DIRECT_ORIGIN');
  const email = required('PARKIO_CSRF_EMAIL');
  const password = required('PARKIO_CSRF_PASSWORD');
  const pgContainer = required('PARKIO_CSRF_PG_CONTAINER');
  const captureFile = required('PARKIO_CSRF_CAPTURE_FILE');
  const edgeFile = required('PARKIO_CSRF_EDGE_FILE');

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
    apiAuthDirectOrigin,
    checks: {},
    layers: {},
    session: {},
    captures: [],
    edge: [],
  };

  const contextOpts = { ignoreHTTPSErrors: true };

  const probe = (rawCookie) =>
    readSessionState({ containerId: pgContainer, email, rawRefreshCookie: rawCookie });

  const attribute = (label, { origin, pathIncludes, sinceTs, browserStatus }) => {
    const edgeRows = readJsonl(edgeFile);
    const authRows = readJsonl(captureFile);
    const attr = attributeLayers({
      edgeRows,
      authRows,
      origin,
      pathIncludes,
      sinceTs,
      browserStatus,
    });
    outcome.layers[label] = attr;
    return attr;
  };

  const assertNoMutation = (before, after, label) => {
    try {
      assertSessionUnchanged(before, after, label);
    } catch (err) {
      outcome.status = 'security_defect';
      outcome.defect = {
        label,
        code: err.code || 'CSRF_SESSION_MUTATION',
        message: String(err.message),
        before,
        after,
      };
      throw err;
    }
  };

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
    const refreshCookies = liveRefreshCookies(cookiesAfterLogin);
    outcome.checks.cookieCount = refreshCookies.length;
    outcome.checks.cookieSecure = refreshCookies.every((c) => c.secure);
    outcome.checks.cookieHttpOnly = refreshCookies.every((c) => c.httpOnly);
    outcome.checks.cookieSameSite = refreshCookies.map((c) => c.sameSite);
    if (refreshCookies.length === 0) {
      throw new Error('no parkio_refresh cookie after login via gateway');
    }
    const rawCookie = refreshCookies[0].value;
    outcome.session.afterLogin = probe(rawCookie);
    if (outcome.session.afterLogin.revoked !== false || outcome.session.afterLogin.activeRefreshCount < 1) {
      throw new Error(`expected active refresh after login: ${JSON.stringify(outcome.session.afterLogin)}`);
    }

    const refreshOk = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', { body: {} }),
    );
    outcome.checks.allowedRefresh = refreshOk;
    if (refreshOk.status !== 200) {
      throw new Error(`allowed refresh failed: ${JSON.stringify(refreshOk)}`);
    }
    const cookiesAfterRefresh = liveRefreshCookies(
      await appContext.cookies(`${apiOrigin}/api/v1/auth/refresh-token`),
    );
    if (cookiesAfterRefresh.length === 0) {
      throw new Error('no cookie after allowed refresh');
    }
    const rawAfterRefresh = cookiesAfterRefresh[0].value;
    outcome.session.afterAllowedRefresh = probe(rawAfterRefresh);
    if (outcome.session.afterAllowedRefresh.sessionEpoch !== outcome.session.afterLogin.sessionEpoch) {
      throw new Error('allowed refresh must not bump session epoch');
    }
    if (outcome.session.afterAllowedRefresh.tokenId === outcome.session.afterLogin.tokenId) {
      throw new Error('allowed refresh must rotate token id');
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
      `${apiAuthDirectOrigin}/api/v1/auth/refresh-token`,
      `${apiAuthDirectOrigin}/api/v1/auth/logout`,
    ]);
    const liveRefresh = liveRefreshCookies(liveCookies);
    if (liveRefresh.length === 0) throw new Error('no refresh cookie after re-login');
    const attackRaw = liveRefresh[0].value;
    const baseline = probe(attackRaw);
    outcome.session.attackBaseline = baseline;
    if (baseline.revoked !== false) {
      throw new Error(`attack baseline token must be active: ${JSON.stringify(baseline)}`);
    }

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

    // --- sibling JSON (preflight path) ---
    let since = nowIso();
    const siblingRefresh = await evilPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', { body: {} }),
    );
    outcome.checks.siblingRefreshJson = siblingRefresh;
    if (siblingRefresh.status === 200) {
      throw new Error(`sibling refresh MUST NOT succeed: ${JSON.stringify(siblingRefresh)}`);
    }
    attribute('siblingRefreshJson', {
      origin: evilOrigin,
      pathIncludes: ['refresh-token'],
      sinceTs: since,
      browserStatus: siblingRefresh.status,
    });
    assertNoMutation(baseline, probe(attackRaw), 'siblingRefreshJson');

    since = nowIso();
    const siblingLogout = await evilPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/logout', { body: {} }),
    );
    outcome.checks.siblingLogoutJson = siblingLogout;
    if (siblingLogout.status === 200 || siblingLogout.status === 204) {
      throw new Error(`sibling logout MUST NOT succeed: ${JSON.stringify(siblingLogout)}`);
    }
    attribute('siblingLogoutJson', {
      origin: evilOrigin,
      pathIncludes: ['logout'],
      sinceTs: since,
      browserStatus: siblingLogout.status,
    });
    assertNoMutation(baseline, probe(attackRaw), 'siblingLogoutJson');

    // --- sibling simple form (no preflight) ---
    since = nowIso();
    const siblingRefreshSimple = await evilPage.evaluate(async () =>
      window.__parkio.callSimple('/api/v1/auth/refresh-token'),
    );
    outcome.checks.siblingRefreshSimple = siblingRefreshSimple;
    if (siblingRefreshSimple.status === 200) {
      throw new Error(`sibling simple refresh MUST NOT succeed: ${JSON.stringify(siblingRefreshSimple)}`);
    }
    const siblingSimpleAttr = attribute('siblingRefreshSimple', {
      origin: evilOrigin,
      pathIncludes: ['refresh-token'],
      sinceTs: since,
      browserStatus: siblingRefreshSimple.status,
    });
    assertNoMutation(baseline, probe(attackRaw), 'siblingRefreshSimple');
    if (!siblingSimpleAttr.reachedGateway && siblingRefreshSimple.status !== 0) {
      throw new Error(`simple sibling refresh should reach gateway edge: ${JSON.stringify(siblingSimpleAttr)}`);
    }

    since = nowIso();
    const siblingLogoutSimple = await evilPage.evaluate(async () =>
      window.__parkio.callSimple('/api/v1/auth/logout'),
    );
    outcome.checks.siblingLogoutSimple = siblingLogoutSimple;
    if (siblingLogoutSimple.status === 200 || siblingLogoutSimple.status === 204) {
      throw new Error(`sibling simple logout MUST NOT succeed: ${JSON.stringify(siblingLogoutSimple)}`);
    }
    attribute('siblingLogoutSimple', {
      origin: evilOrigin,
      pathIncludes: ['logout'],
      sinceTs: since,
      browserStatus: siblingLogoutSimple.status,
    });
    assertNoMutation(baseline, probe(attackRaw), 'siblingLogoutSimple');

    // --- auth Origin isolation (lab auth-direct; gateway CORS bypassed) ---
    // Simple form POST avoids CORS preflight; browser may not read the response
    // without ACAO — proof is auth capture 403 + unchanged session.
    since = nowIso();
    const originIsolateRefresh = await evilPage.evaluate(
      async ({ api }) => window.__parkio.callSimple('/api/v1/auth/refresh-token', { api }),
      { api: apiAuthDirectOrigin },
    );
    outcome.checks.authOriginIsolateRefresh = originIsolateRefresh;
    if (originIsolateRefresh.status === 200) {
      throw new Error(
        `auth Origin isolate refresh MUST NOT succeed: ${JSON.stringify(originIsolateRefresh)}`,
      );
    }
    const isolateAttr = attribute('authOriginIsolateRefresh', {
      origin: evilOrigin,
      pathIncludes: ['refresh-token'],
      sinceTs: since,
      browserStatus: originIsolateRefresh.status,
    });
    if (!isolateAttr.reachedAuth) {
      throw new Error(
        `auth Origin isolate must reach auth (got ${JSON.stringify(isolateAttr)}) — otherwise CORS still masks Origin guard`,
      );
    }
    if (!isolateAttr.authStatuses.includes(403)) {
      throw new Error(`auth Origin isolate auth status must be 403: ${JSON.stringify(isolateAttr)}`);
    }
    assertNoMutation(baseline, probe(attackRaw), 'authOriginIsolateRefresh');

    since = nowIso();
    const originIsolateLogout = await evilPage.evaluate(
      async ({ api }) => window.__parkio.callSimple('/api/v1/auth/logout', { api }),
      { api: apiAuthDirectOrigin },
    );
    outcome.checks.authOriginIsolateLogout = originIsolateLogout;
    if (originIsolateLogout.status === 200 || originIsolateLogout.status === 204) {
      throw new Error(`auth Origin isolate logout MUST NOT succeed: ${JSON.stringify(originIsolateLogout)}`);
    }
    const isolateLogoutAttr = attribute('authOriginIsolateLogout', {
      origin: evilOrigin,
      pathIncludes: ['logout'],
      sinceTs: since,
      browserStatus: originIsolateLogout.status,
    });
    if (!isolateLogoutAttr.reachedAuth || !isolateLogoutAttr.authStatuses.includes(403)) {
      throw new Error(
        `auth Origin isolate logout must reach auth with 403: ${JSON.stringify(isolateLogoutAttr)}`,
      );
    }
    assertNoMutation(baseline, probe(attackRaw), 'authOriginIsolateLogout');

    // --- cross-site JSON ---
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

    since = nowIso();
    const crossRefresh = await crossPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/refresh-token', { body: {} }),
    );
    outcome.checks.crossRefreshJson = crossRefresh;
    if (crossRefresh.status === 200) {
      throw new Error(`cross-site refresh MUST NOT succeed: ${JSON.stringify(crossRefresh)}`);
    }
    attribute('crossRefreshJson', {
      origin: crossOrigin,
      pathIncludes: ['refresh-token'],
      sinceTs: since,
      browserStatus: crossRefresh.status,
    });
    assertNoMutation(baseline, probe(attackRaw), 'crossRefreshJson');

    since = nowIso();
    const crossRefreshSimple = await crossPage.evaluate(async () =>
      window.__parkio.callSimple('/api/v1/auth/refresh-token'),
    );
    outcome.checks.crossRefreshSimple = crossRefreshSimple;
    if (crossRefreshSimple.status === 200) {
      throw new Error(`cross-site simple refresh MUST NOT succeed: ${JSON.stringify(crossRefreshSimple)}`);
    }
    attribute('crossRefreshSimple', {
      origin: crossOrigin,
      pathIncludes: ['refresh-token'],
      sinceTs: since,
      browserStatus: crossRefreshSimple.status,
    });
    assertNoMutation(baseline, probe(attackRaw), 'crossRefreshSimple');

    since = nowIso();
    const crossLogout = await crossPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/logout', { body: {} }),
    );
    outcome.checks.crossLogoutJson = crossLogout;
    if (crossLogout.status === 200 || crossLogout.status === 204) {
      throw new Error(`cross-site logout MUST NOT succeed: ${JSON.stringify(crossLogout)}`);
    }
    attribute('crossLogoutJson', {
      origin: crossOrigin,
      pathIncludes: ['logout'],
      sinceTs: since,
      browserStatus: crossLogout.status,
    });
    assertNoMutation(baseline, probe(attackRaw), 'crossLogoutJson');

    const logoutAll = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/logout-all', { body: {} }),
    );
    outcome.checks.logoutAllNoBearer = logoutAll;
    if (logoutAll.status === 200 || logoutAll.status === 204) {
      throw new Error(`logout-all without bearer must fail: ${JSON.stringify(logoutAll)}`);
    }
    outcome.layers.logoutAllNoBearer = {
      rejectionLayer: `http-${logoutAll.status}`,
      reachedAuth: true,
    };
    assertNoMutation(baseline, probe(attackRaw), 'logoutAllNoBearer');

    // Positive control logout (allowed origin) — must revoke
    const beforeLogout = probe(attackRaw);
    const logoutOk = await appPage.evaluate(async () =>
      window.__parkio.call('/api/v1/auth/logout', { body: {} }),
    );
    outcome.checks.allowedLogout = logoutOk;
    if (logoutOk.status !== 204) {
      throw new Error(`allowed logout failed: ${JSON.stringify(logoutOk)}`);
    }
    const afterLogout = probe(attackRaw);
    outcome.session.afterAllowedLogout = afterLogout;
    if (afterLogout.revoked !== true || afterLogout.revokedReason !== 'LOGOUT') {
      throw new Error(`allowed logout must revoke with LOGOUT: ${JSON.stringify(afterLogout)}`);
    }
    if (afterLogout.sessionEpoch !== beforeLogout.sessionEpoch) {
      throw new Error('single-device logout must not bump session epoch');
    }

    outcome.captures = readJsonl(captureFile);
    outcome.edge = readJsonl(edgeFile);
    const authBound = outcome.captures.filter((c) => c.path && c.path.includes('/api/v1/auth/'));
    if (authBound.length === 0) {
      throw new Error('no server-side captures at auth — gateway/auth-direct may not have forwarded');
    }
    if (!authBound.every((c) => c.hasGatewayAuth === true)) {
      throw new Error(`gateway/auth-direct did not stamp X-Gateway-Auth: ${JSON.stringify(authBound)}`);
    }

    outcome.status = 'passed';
    writeResult(outcome);
  } catch (error) {
    if (outcome.status !== 'security_defect') {
      outcome.status = 'failed';
    }
    outcome.error = String(error?.stack || error);
    outcome.captures = readJsonl(captureFile);
    outcome.edge = readJsonl(edgeFile);
    writeResult(outcome);
    throw error;
  } finally {
    await browser.close();
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(err?.code === 'CSRF_SESSION_MUTATION' ? 3 : 1);
});
