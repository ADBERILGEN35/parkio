#!/usr/bin/env node
/**
 * U04 live pending-profile ownership acceptance (Asana 1219001664327469) against the production web app.
 *
 * Live mode runs only inside a U04 window of u05-priv001a-window.py (`start --scenario u04`): the window opens
 * invite-only registration and account erasure, and its cleanup erases every account whose credentials this tool
 * stores (always BEFORE the account registers). Synthetic identities only: priv001a-u04*@priv001a.parkio.invalid,
 * display names "PRIV001A U04 Rn", phones in the Ofcom drama range +44 7700 900xxx.
 *
 * Cases (the U04 criteria: A->B PATCH 0; A->A PATCH 1; reload/logout paths do not transfer data; no phone in
 * storage; the map renders with a non-synthetic key):
 *   a-a        R1 registers, is verified, signs in: exactly one PATCH /users/me, R1's name and phone, R1's token.
 *   a-b        R2 registers; R1 signs in in the same tab (in-app navigation): no PATCH; R2's fields leave nowhere.
 *   logout-1   R1 signs out in R2's tab; R2 (now verified) signs in there: no PATCH.
 *   logout-2   R1 signs out in its own tab; R2 signs in there: no PATCH.
 *   reload-b   R3 registers; full reload; R1 signs in: no PATCH, no phone prompt, R3's fields leave nowhere.
 *   storage    after each case: no synthetic phone in localStorage, sessionStorage, IndexedDB, Cache Storage or
 *              cookies; no pending profile left behind.
 *   map        /map draws MapTiler tiles (HTTP 200) with a key whose SHA-256 fingerprint is the deployed one and
 *              that is not a repository placeholder; the canvas is not blank.
 *
 * --mock-api is the dry run: the same flow against the production web bundle, but every API call is answered
 * inside the browser by a mock (no request reaches api.parkio.dev) and no SSH call is made.
 * --map-only <url> runs only the map check on a public page (e.g. https://app.parkio.dev/explore): no account, no
 * window, no SSH. It re-checks the map criterion read-only once a valid key is deployed.
 *
 * Output: <evidence>/u04-live-results.json, /map screenshots (synthetic accounts only), SHA256SUMS.
 * Never printed or stored: invite tokens, passwords, bearer tokens, cookie values, the map key.
 *
 * usage: node u04-live-ownership.mjs --run <window run> --evidence <dir> --map-fingerprint <hex12>
 *        node u04-live-ownership.mjs --mock-api --evidence <dir> --map-fingerprint <hex12>
 *        node u04-live-ownership.mjs --map-only <url> --evidence <dir> --map-fingerprint <hex12>
 *        [--playwright <dir of the @playwright/test package>]
 */
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import zlib from 'node:zlib';

const APP = 'https://app.parkio.dev';
const API_ORIGIN = 'https://api.parkio.dev';
const SSH = '/mnt/c/Windows/System32/OpenSSH/ssh.exe';
const SSH_ARGS = ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=30',
  '-o', 'ServerAliveCountMax=10', 'civo@74.220.31.27'];
const HOST = 'parkio-civo-prod';
const PRIVATE = '/root/u05-priv001a-window';
const SYNTHETIC_EMAIL = /^priv001a-[a-z0-9]{6,64}@priv001a\.parkio\.invalid$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PROFILE = '/api/v1/users/me';
const PHONE_PROMPT = /phone number was not kept/i;
// scripts/lib/web_bundle_map_config.py (is_synthetic), lowercased
const MAP_PLACEHOLDERS = new Set(['synthetic-non-production-value', 'secret_sentinel_maptiler_public_key',
  'ci-public-map-key', 'fixture-public-map-key', 'fixture-public-map-key-never-use-in-production',
  'replace_me_maptiler_public_key', 'your_maptiler_key']);
const MAP_SYNTHETIC_CLASS = 'ci-web-build-security-synthetic';
const TOOL_DEADLINE_MS = 15 * 60 * 1000;

function arg(name, fallback = undefined) {
  const i = process.argv.indexOf(name);
  return i > 1 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}
const MOCK = process.argv.includes('--mock-api');
const MAP_ONLY = arg('--map-only', '');
const RUN = arg('--run', '');
const EVIDENCE = arg('--evidence', '');
const MAP_FP = arg('--map-fingerprint', '');
const PW_DIR = arg('--playwright',
  '/mnt/c/Users/ADBERILGEN/Documents/parkio-wt-u04-pending-profile/frontend/node_modules/@playwright/test');

// ---- secrets never leave this process ---------------------------------------------------------------------------
const secrets = new Set();
const keep = (v) => {
  if (typeof v === 'string' && v.length >= 8) secrets.add(v);
  return v;
};
function redact(text) {
  let t = String(text);
  for (const v of secrets) t = t.split(v).join('<redacted>');
  return t.replace(/eyJ[\w-]+\.[\w-]+\.[\w-]*/g, '<jwt>').replace(/([?&](?:key|invite|token)=)[^&\s"']+/gi, '$1<redacted>');
}
const stamp = () => new Date().toISOString().replace(/\.\d+Z$/, 'Z');
const say = (...a) => process.stdout.write(redact(`${stamp()} ${a.join(' ')}`) + '\n');
function stop(msg) {
  process.stderr.write(redact(`STOP: ${msg}`) + '\n');
  process.exit(2);
}
if (MAP_ONLY && !/^https:\/\/[^\s]+$/.test(MAP_ONLY)) stop('--map-only needs an https URL');
if (!MOCK && !MAP_ONLY && !/^\d{8}T\d{6}Z$/.test(RUN)) stop('--run <window run id> is required (or --mock-api / --map-only)');
if (!EVIDENCE) stop('--evidence <dir> is required');
if (!/^[0-9a-f]{12}$/.test(MAP_FP)) stop('--map-fingerprint <12 hex> is required (the D4 map guard fingerprint)');
const require = createRequire(import.meta.url);
const { chromium } = require(PW_DIR);

// ---- results ------------------------------------------------------------------------------------------------------
const results = { tool: 'u04-live-ownership', mode: MAP_ONLY ? 'map-only' : MOCK ? 'mock-api' : 'live', run: RUN || null,
  app: MAP_ONLY || APP,
  startedAt: stamp(), accounts: {}, checks: [], storage: [], map: null, screenshots: [], errors: [] };
function check(kase, name, pass, detail = '') {
  results.checks.push({ case: kase, check: name, pass: Boolean(pass), detail: redact(detail) });
  say(`${pass ? 'PASS' : 'FAIL'} [${kase}] ${name}${detail ? ` - ${detail}` : ''}`);
}

// ---- the window's operator commands (live) or their fakes (mock) -------------------------------------------------
const fakeWorld = { verified: new Set(), accounts: new Map() };
function remote(action, extra = [], input = '') {
  for (const a of extra) if (!/^[A-Za-z0-9@._+-]+$/.test(a)) throw new Error('unsafe remote argument');
  const cmd = ['sudo', '-n', 'python3', '-I', `${PRIVATE}/${RUN}/window.py`, action, '--run', RUN, ...extra].join(' ');
  const r = spawnSync(SSH, [...SSH_ARGS, cmd], { input, encoding: 'utf8', timeout: 240000, maxBuffer: 1 << 20 });
  return { code: r.status, out: (r.stdout || '').replace(/\r/g, '').trim() };
}
function opInvite() {
  if (MOCK) return keep(crypto.randomBytes(24).toString('base64url'));
  const r = remote('u04-invite');
  const token = r.out.split('\n').pop() || '';
  if (r.code !== 0 || !/^[A-Za-z0-9_-]{16,256}$/.test(token)) {
    throw new Error(`u04-invite failed (exit ${r.code}): ${r.out.startsWith('STOP') ? r.out : 'no token'}`);
  }
  return keep(token);
}
function opStoreCreds(a) {
  if (MOCK) return;
  const r = remote('u04-creds', ['--name', a.tag], `email=${a.email}\npassword=${a.password}\n`);
  if (r.code !== 0 || !r.out.includes(`stored creds-${a.tag}`)) throw new Error(`u04-creds ${a.tag}: ${r.out}`);
}
function opVerify(a) {
  if (MOCK) {
    fakeWorld.verified.add(a.email);
    return fakeWorld.accounts.get(a.email)?.id || '';
  }
  const r = remote('u04-verify', ['--email', a.email]);
  const m = /^verified ([0-9a-f-]{36})$/.exec(r.out.split('\n').pop() || '');
  if (r.code !== 0 || !m) throw new Error(`u04-verify ${a.tag}: ${r.out}`);
  return m[1];
}
function opDone() {
  if (MOCK || MAP_ONLY) return 'none';
  const r = remote('u04-done');
  return r.code === 0 ? 'U04 hold ended' : `u04-done failed (exit ${r.code}): ${r.out}`;
}
function opPreflight() {
  if (MOCK) return;
  const h = spawnSync(SSH, [...SSH_ARGS, 'hostname; id -un'], { input: '', encoding: 'utf8', timeout: 60000 });
  const [host, user] = (h.stdout || '').replace(/\r/g, '').trim().split('\n');
  if (h.status !== 0 || host !== HOST || user !== 'civo') stop(`unexpected SSH target (${host || '?'} / ${user || '?'})`);
  const s = remote('status');
  let st = null;
  try {
    st = JSON.parse(s.out);
  } catch {
    st = null;
  }
  if (!st || st.scenario !== 'u04' || st.phase !== 'U04-HOLD') {
    stop(`window ${RUN} is not in a U04 hold (phase ${st?.phase ?? '?'}, scenario ${st?.scenario ?? '?'})`);
  }
  results.windowPhaseAtStart = st.phase;
}

// ---- synthetic accounts -------------------------------------------------------------------------------------------
function password() {
  const pick = (set, n) => Array.from({ length: n }, () => set[crypto.randomInt(set.length)]).join('');
  const chars = (pick('ABCDEFGHJKLMNPQRSTUVWXYZ', 4) + pick('abcdefghijkmnopqrstuvwxyz', 8) + pick('23456789', 4)
    + pick('!#%+-=?@', 3) + pick('ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789', 7)).split('');
  for (let i = chars.length - 1; i > 0; i -= 1) {
    const j = crypto.randomInt(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}
function account(tag, phoneSuffix) {
  const email = `priv001a-u04${tag.toLowerCase()}${crypto.randomBytes(6).toString('hex')}@priv001a.parkio.invalid`;
  if (!SYNTHETIC_EMAIL.test(email)) throw new Error('generated email is not synthetic');
  return { tag, email, password: keep(password()), name: `PRIV001A U04 ${tag}`, phone: `+4477009001${phoneSuffix}`,
    id: null };
}
const phoneNeedles = (a) => [a.phone, a.phone.slice(1), a.phone.slice(3)];

// ---- mock API (dry run only): the production bundle talks to this instead of api.parkio.dev ------------------------
function jwtFor(id) {
  const b = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  return `${b({ alg: 'none', typ: 'JWT' })}.${b({ sub: id, exp: 4102444800 })}.mock`;
}
async function installMockApi(ctx) {
  const session = { account: null };
  const cors = (req) => ({ 'access-control-allow-origin': APP, 'access-control-allow-credentials': 'true',
    'access-control-allow-methods': 'GET,POST,PATCH,PUT,DELETE,OPTIONS',
    'access-control-allow-headers': req.headers()['access-control-request-headers'] || '*' });
  await ctx.route((url) => url.origin === API_ORIGIN, async (route) => {
    const req = route.request();
    const method = req.method();
    const p = new URL(req.url()).pathname.replace(/^\/api\/v1/, '');
    const json = (data, status = 200) => route.fulfill({ status, contentType: 'application/json',
      headers: cors(req), body: JSON.stringify(data) });
    if (method === 'OPTIONS') return route.fulfill({ status: 204, headers: cors(req), body: '' });
    const bearer = (req.headers().authorization || '').replace(/^Bearer /, '');
    const byBearer = [...fakeWorld.accounts.values()].find((a) => bearer && jwtFor(a.id) === bearer);
    const auth = (a) => ({ accessToken: jwtFor(a.id), tokenType: 'Bearer', accessTokenExpiresAt: '2999-01-01T00:00:00Z',
      refreshTokenExpiresAt: '2999-01-01T00:00:00Z', user: { id: a.id, email: a.email, status: 'ACTIVE', roles: ['USER'] } });
    if (method === 'GET' && p === '/auth/registration-mode') return json({ mode: 'INVITE' });
    if (method === 'POST' && p === '/auth/register') {
      const body = req.postDataJSON();
      if (!body.inviteToken) return json({ code: 'INVITE_REQUIRED', message: 'invite' }, 403);
      const a = { id: crypto.randomUUID(), email: body.email };
      fakeWorld.accounts.set(body.email, a);
      return json({ accessToken: null, tokenType: 'Bearer', accessTokenExpiresAt: null, refreshTokenExpiresAt: null,
        user: { id: a.id, email: a.email, status: 'PENDING_VERIFICATION', roles: ['USER'] } }, 201);
    }
    if (method === 'POST' && p === '/auth/login') {
      const a = fakeWorld.accounts.get(req.postDataJSON().email);
      if (!a) return json({ code: 'INVALID_CREDENTIALS', message: 'no' }, 401);
      if (!fakeWorld.verified.has(a.email)) return json({ code: 'EMAIL_NOT_VERIFIED', message: 'verify' }, 403);
      session.account = a;
      return json(auth(a));
    }
    if (method === 'POST' && p === '/auth/refresh-token') {
      return session.account ? json(auth(session.account)) : json({ code: 'INVALID_TOKEN', message: 'none' }, 401);
    }
    if (method === 'POST' && p === '/auth/logout') {
      session.account = null;
      return route.fulfill({ status: 204, headers: cors(req), body: '' });
    }
    if (method === 'GET' && p === '/auth/me') {
      return byBearer ? json({ id: byBearer.id, email: byBearer.email, status: 'ACTIVE', roles: ['USER'] })
        : json({ code: 'UNAUTHORIZED', message: 'no' }, 401);
    }
    if (method === 'PATCH' && p === '/users/me') {
      return byBearer ? json({ userId: byBearer.id, ...req.postDataJSON() }) : json({ code: 'INVALID_TOKEN' }, 401);
    }
    if (method === 'GET' && p === '/users/me') {
      return byBearer ? json({ userId: byBearer.id, displayName: null, phoneNumber: null })
        : json({ code: 'UNAUTHORIZED' }, 401);
    }
    if (method === 'GET' && p === '/notifications/me') return json([]);
    if (method === 'GET' && p === '/users/me/vehicle') return json({ vehicleType: 'SEDAN', plate: '35PK123' });
    if (method === 'GET' && p === '/parking/spots/nearby') return json([]);
    if (method === 'GET' && p === '/geocoding/search') return json({ results: [] });
    if (method === 'POST' && /analytics|telemetry|events|client-errors/.test(p)) {
      return route.fulfill({ status: 202, headers: cors(req), body: '' });
    }
    return json({ code: 'NOT_MOCKED', message: `${method} ${p}` }, 500);
  });
  // A dry run sends nothing to third parties other than the map tiles a public visitor loads.
  await ctx.route((url) => ![new URL(APP).host, new URL(API_ORIGIN).host, 'api.maptiler.com'].includes(url.host),
    (route) => route.abort());
}

// ---- browser helpers ----------------------------------------------------------------------------------------------
function jwtSub(authorization) {
  const m = /^Bearer\s+[\w-]+\.([\w-]+)\.[\w-]*$/.exec(authorization || '');
  if (!m) return null;
  try {
    return JSON.parse(Buffer.from(m[1], 'base64url').toString('utf8')).sub || null;
  } catch {
    return null;
  }
}
async function newTab(browser, label) {
  const ctx = await browser.newContext({ locale: 'en-US', viewport: { width: 1280, height: 800 } });
  await ctx.addInitScript(() => {
    try {
      localStorage.setItem('parkio.locale', 'en');
    } catch {
      /* storage blocked */
    }
  });
  if (MOCK) await installMockApi(ctx);
  const w = { label, api: [], patches: [], maptiler: [], navs: [] };
  ctx.on('request', (req) => {
    const u = new URL(req.url());
    if (u.origin === API_ORIGIN) {
      const e = { t: Date.now(), method: req.method(), url: req.url(), body: req.postData() || '' };
      w.api.push(e);
      if (req.method() === 'PATCH' && u.pathname === PROFILE) {
        w.patches.push({ t: e.t, body: e.body, sub: jwtSub(req.headers().authorization), req });
      }
    }
  });
  ctx.on('response', (res) => {
    const u = new URL(res.url());
    if (u.hostname !== 'api.maptiler.com') return;
    const entry = { url: res.url(), status: res.status(), error: null };
    w.maptiler.push(entry);
    if (res.status() >= 400) {
      res.text().then((t) => { entry.error = t.slice(0, 80).replace(/\s+/g, ' '); }).catch(() => {});
    }
  });
  const page = await ctx.newPage();
  page.on('framenavigated', (f) => {
    if (f === page.mainFrame()) w.navs.push({ t: Date.now(), path: new URL(f.url()).pathname });
  });
  return { ctx, page, w };
}
async function spaGoto(page, p) {
  await page.evaluate((next) => {
    window.history.pushState({}, '', next);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, p);
}
async function register(tab, a, kase) {
  const { page } = tab;
  const token = opInvite();
  opStoreCreds(a); // before the registration: the window's cleanup can always find the account
  try {
    await page.goto(`${APP}/register?invite=${token}`, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.getByLabel('Full name').waitFor({ timeout: 30000 });
  } catch (e) {
    throw new Error(redact(e.message).split('\n')[0]);
  }
  await page.waitForFunction(() => !new URL(location.href).searchParams.has('invite'), null, { timeout: 10000 })
    .then(() => check(kase, `${a.tag}: the invite token leaves the address bar`, true))
    .catch(() => check(kase, `${a.tag}: the invite token leaves the address bar`, false));
  await page.getByLabel('Full name').fill(a.name);
  await page.getByLabel('Email').fill(a.email);
  await page.getByLabel(/Phone number/).fill(a.phone);
  await page.getByLabel('Password', { exact: true }).fill(a.password);
  await page.getByLabel('Confirm password').fill(a.password);
  await page.getByLabel(/I agree/).check();
  const respP = page.waitForResponse((r) => r.url() === `${API_ORIGIN}/api/v1/auth/register`
    && r.request().method() === 'POST', { timeout: 60000 });
  await page.getByRole('button', { name: 'Create account' }).click();
  const resp = await respP;
  let body = null;
  try {
    body = await resp.json();
  } catch {
    body = null;
  }
  if (resp.status() !== 201 && resp.status() !== 200) {
    throw new Error(`register ${a.tag}: HTTP ${resp.status()} ${body?.code ?? ''}`);
  }
  a.id = body?.user?.id ?? null;
  results.accounts[a.tag] = { id: a.id, registeredAt: stamp() };
  await page.waitForURL(/\/check-email$/, { timeout: 30000 });
  check(kase, `${a.tag}: registered through the invite (HTTP ${resp.status()}, id returned)`, UUID.test(a.id || ''));
  const stored = await pending(page);
  let parsed = null;
  try {
    parsed = JSON.parse(stored || 'null');
  } catch {
    parsed = null;
  }
  check(kase, `${a.tag}: the pending profile is bound to ${a.tag}'s id and holds no phone`,
    parsed && parsed.ownerUserId === a.id && !phoneNeedles(a).some((n) => (stored || '').includes(n)),
    `keys ${parsed ? Object.keys(parsed).sort().join(',') : 'none'}`);
}
async function pending(page) {
  return page.evaluate(() => sessionStorage.getItem('parkio.pendingProfile'));
}
async function login(page, a) {
  await page.getByLabel('Email').fill(a.email);
  await page.getByLabel('Password').fill(a.password);
  const respP = page.waitForResponse((r) => r.url() === `${API_ORIGIN}/api/v1/auth/login`
    && r.request().method() === 'POST', { timeout: 60000 });
  await page.getByRole('button', { name: 'Sign in' }).click();
  const resp = await respP;
  if (resp.status() !== 200) throw new Error(`login ${a.tag}: HTTP ${resp.status()}`);
  await page.waitForURL(/\/map$/, { timeout: 120000 });
  await page.waitForTimeout(4000); // let any deferred profile write happen before counting
}
async function signOut(page) {
  await spaGoto(page, '/profile');
  const btn = page.getByRole('button', { name: 'Sign out', exact: true }).first();
  await btn.waitFor({ timeout: 30000 });
  const respP = page.waitForResponse((r) => r.url() === `${API_ORIGIN}/api/v1/auth/logout`
    && r.request().method() === 'POST', { timeout: 30000 }).catch(() => null);
  await btn.click();
  const resp = await respP;
  await page.waitForURL(/\/login(\?.*)?$/, { timeout: 30000 });
  return resp ? resp.status() : null;
}
function patchesSince(w, t) {
  return w.patches.filter((p) => p.t >= t);
}
function requestsCarrying(w, t, a) {
  const needles = [a.name, ...phoneNeedles(a)];
  return w.api.filter((e) => e.t >= t && needles.some((n) => e.body.includes(n) || e.url.includes(n)))
    .map((e) => `${e.method} ${new URL(e.url).pathname}`);
}
async function storageScan(tab, kase, accounts) {
  const needles = accounts.flatMap(phoneNeedles);
  const inPage = await tab.page.evaluate(async (ns) => {
    const hit = (s) => ns.filter((n) => String(s).includes(n));
    const out = { hits: {}, keys: { localStorage: [], sessionStorage: [], indexedDB: [], caches: 0 } };
    const add = (where, s) => {
      const h = hit(s);
      if (h.length) out.hits[where] = [...new Set([...(out.hits[where] || []), ...h])];
    };
    for (let i = 0; i < localStorage.length; i += 1) {
      const k = localStorage.key(i);
      out.keys.localStorage.push(k);
      add('localStorage', `${k}=${localStorage.getItem(k)}`);
    }
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const k = sessionStorage.key(i);
      out.keys.sessionStorage.push(k);
      add('sessionStorage', `${k}=${sessionStorage.getItem(k)}`);
    }
    try {
      for (const d of (await indexedDB.databases?.()) || []) {
        out.keys.indexedDB.push(d.name);
        const dump = await new Promise((resolve) => {
          const r = indexedDB.open(d.name);
          r.onerror = () => resolve('');
          r.onsuccess = () => {
            const db = r.result;
            const stores = Array.from(db.objectStoreNames);
            if (!stores.length) {
              db.close();
              resolve('');
              return;
            }
            const tx = db.transaction(stores, 'readonly');
            const parts = [];
            let left = stores.length;
            for (const s of stores) {
              const g = tx.objectStore(s).getAll();
              g.onsuccess = () => {
                try {
                  parts.push(JSON.stringify(g.result));
                } catch {
                  parts.push('');
                }
                left -= 1;
                if (!left) {
                  db.close();
                  resolve(parts.join('\n'));
                }
              };
              g.onerror = () => {
                left -= 1;
                if (!left) {
                  db.close();
                  resolve(parts.join('\n'));
                }
              };
            }
          };
        });
        add('indexedDB', dump);
      }
    } catch {
      out.idbError = true;
    }
    try {
      for (const name of await caches.keys()) {
        const c = await caches.open(name);
        for (const req of await c.keys()) {
          out.keys.caches += 1;
          add('cacheStorage', req.url);
          const res = await c.match(req);
          const ct = res?.headers.get('content-type') || '';
          if (res && /json|text\/(plain|html)/.test(ct)) add('cacheStorage', await res.text());
        }
      }
    } catch {
      out.cacheError = true;
    }
    out.pending = sessionStorage.getItem('parkio.pendingProfile');
    return out;
  }, needles);
  const cookies = await tab.ctx.cookies();
  const cookieHits = needles.filter((n) => cookies.some((c) => c.value.includes(n) || c.name.includes(n)));
  if (cookieHits.length) inPage.hits.cookies = cookieHits;
  const entry = { case: kase, tab: tab.w.label, at: stamp(), keys: { ...inPage.keys, cookies: cookies.map((c) => c.name).sort() },
    phoneHits: inPage.hits, pendingProfileLeft: inPage.pending !== null };
  results.storage.push(entry);
  check(kase, `${tab.w.label}: no synthetic phone in localStorage, sessionStorage, IndexedDB, Cache Storage or cookies`,
    Object.keys(inPage.hits).length === 0, Object.keys(inPage.hits).length ? JSON.stringify(inPage.hits) : '');
  return entry;
}
async function screenshot(tab, name) {
  const file = path.join(EVIDENCE, `${name}.png`);
  await tab.page.screenshot({ path: file });
  results.screenshots.push(path.basename(file));
}

// ---- map ----------------------------------------------------------------------------------------------------------
function pngDecode(buf) {
  let off = 8;
  let w = 0;
  let h = 0;
  let ct = 0;
  let bd = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0);
      h = data.readUInt32BE(4);
      bd = data[8];
      ct = data[9];
      if (data[12] !== 0) throw new Error('interlaced PNG');
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  if (bd !== 8 || (ct !== 2 && ct !== 6)) throw new Error(`unsupported PNG (depth ${bd}, colour type ${ct})`);
  const bpp = ct === 6 ? 4 : 3;
  const stride = w * bpp;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const px = Buffer.alloc(h * stride);
  for (let y = 0; y < h; y += 1) {
    const f = raw[y * (stride + 1)];
    for (let x = 0; x < stride; x += 1) {
      const a = x >= bpp ? px[y * stride + x - bpp] : 0;
      const b = y > 0 ? px[(y - 1) * stride + x] : 0;
      const c = x >= bpp && y > 0 ? px[(y - 1) * stride + x - bpp] : 0;
      let v = raw[y * (stride + 1) + 1 + x];
      if (f === 1) v += a;
      else if (f === 2) v += b;
      else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      }
      px[y * stride + x] = v & 255;
    }
  }
  return { w, h, bpp, stride, px };
}
/** Colour statistics of the screenshot pixels at the given CSS points (device scale 1). */
function pointStats(img, points) {
  const colours = new Set();
  let sum = 0;
  let sum2 = 0;
  let n = 0;
  for (const [x, y] of points) {
    if (x < 0 || y < 0 || x >= img.w || y >= img.h) continue;
    const i = y * img.stride + x * img.bpp;
    colours.add(((img.px[i] >> 3) << 10) | ((img.px[i + 1] >> 3) << 5) | (img.px[i + 2] >> 3));
    const l = 0.299 * img.px[i] + 0.587 * img.px[i + 1] + 0.114 * img.px[i + 2];
    sum += l;
    sum2 += l * l;
    n += 1;
  }
  const mean = n ? sum / n : 0;
  return { points: n, distinctColours: colours.size,
    lumaStd: n ? Math.round(Math.sqrt(Math.max(0, sum2 / n - mean * mean)) * 10) / 10 : 0 };
}
async function mapCheck(tab, kase) {
  const { page, w } = tab;
  const canvas = page.locator('canvas.maplibregl-canvas').first();
  await canvas.waitFor({ state: 'visible', timeout: 60000 });
  const until = Date.now() + 45000;
  const tiles = () => w.maptiler.filter((r) => /\.pbf|\/tiles\//.test(new URL(r.url).pathname));
  while (Date.now() < until && tiles().filter((r) => r.status === 200).length < 4) await page.waitForTimeout(1000);
  await page.waitForTimeout(3000);
  const keys = [...new Set(w.maptiler.map((r) => new URL(r.url).searchParams.get('key')).filter(Boolean))];
  keys.forEach(keep);
  const style = w.maptiler.filter((r) => /style\.json$/.test(new URL(r.url).pathname));
  const fps = keys.map((k) => crypto.createHash('sha256').update(k, 'utf8').digest('hex').slice(0, 12));
  const synthetic = keys.some((k) => MAP_PLACEHOLDERS.has(k.trim().toLowerCase()) || k.trim().toUpperCase().startsWith('REPLACE_ME_')
    || k.trim() === MAP_SYNTHETIC_CLASS || /^ci-web-build-security-synthetic[-_.]/.test(k.trim()));
  // The map's own pixels: only grid points where the canvas is the topmost element, so panels and buttons drawn
  // over the map cannot count as a rendered basemap.
  const points = await page.evaluate(() => {
    const c = document.querySelector('canvas.maplibregl-canvas');
    const r = c.getBoundingClientRect();
    const out = [];
    for (let y = Math.ceil(r.top) + 2; y < r.bottom - 2; y += 8) {
      for (let x = Math.ceil(r.left) + 2; x < r.right - 2; x += 8) {
        if (document.elementFromPoint(x, y) === c) out.push([x, y]);
      }
    }
    return out;
  });
  const shotFile = path.join(EVIDENCE, `${kase}-full.png`);
  const buf = await page.screenshot({ path: shotFile });
  results.screenshots.push(path.basename(shotFile));
  const stats = { ...pointStats(pngDecode(buf), points), gridPointsOnCanvas: points.length };
  const attribution = ((await page.locator('.maplibregl-ctrl-attrib').first().innerText().catch(() => '')) || '')
    .replace(/\s+/g, ' ').trim();
  results.map = { styleResponses: style.map((r) => `${r.status}${r.error ? ` ${r.error}` : ''}`), tileResponses: tiles().length,
    tileOk: tiles().filter((r) => r.status === 200).length, keysSeen: keys.length, keyFingerprints: fps,
    expectedFingerprint: MAP_FP, canvas: stats, attribution: attribution.slice(0, 160) };
  check(kase, 'the MapTiler style loads (HTTP 200)', style.length > 0 && style.every((r) => r.status === 200),
    `style responses ${style.map((r) => `${r.status}${r.error ? ` "${r.error}"` : ''}`).join(',') || 'none'}`);
  check(kase, 'map tiles load (HTTP 200)', results.map.tileOk >= 4, `${results.map.tileOk}/${results.map.tileResponses} tiles 200`);
  check(kase, 'one map key, not a repository placeholder or the CI synthetic class', keys.length === 1 && !synthetic,
    `${keys.length} key(s) seen`);
  check(kase, `the key's fingerprint is the deployed one (${MAP_FP})`, fps.length === 1 && fps[0] === MAP_FP,
    `seen ${fps.join(',') || 'none'}`);
  check(kase, 'the basemap is drawn (pixels where the canvas is topmost)',
    stats.points >= 500 && stats.distinctColours >= 20 && stats.lumaStd >= 5,
    `${stats.points} points, ${stats.distinctColours} colours, luma sd ${stats.lumaStd}; attribution "${attribution.slice(0, 60)}"`);
}

// ---- the cases ----------------------------------------------------------------------------------------------------
async function mapOnly() {
  fs.mkdirSync(EVIDENCE, { recursive: true, mode: 0o700 });
  const browser = await chromium.launch({ headless: true });
  results.browser = `chromium ${browser.version()}`;
  try {
    const tab = await newTab(browser, 'map-only');
    await tab.page.goto(MAP_ONLY, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await mapCheck(tab, 'map');
  } catch (e) {
    results.errors.push(`map: ${redact(e?.message || String(e)).split('\n')[0]}`);
  }
  await browser.close();
  finish(0);
}

async function main() {
  if (MAP_ONLY) return mapOnly();
  fs.mkdirSync(EVIDENCE, { recursive: true, mode: 0o700 });
  opPreflight();
  const deadline = setTimeout(() => {
    results.errors.push('the tool deadline passed');
    finish(1);
  }, TOOL_DEADLINE_MS);
  deadline.unref();
  const browser = await chromium.launch({ headless: true });
  results.browser = `chromium ${browser.version()}`;
  const R1 = account('R1', '41');
  const R2 = account('R2', '42');
  const R3 = account('R3', '43');
  const all = [R1, R2, R3];
  const step = async (kase, fn) => {
    try {
      await fn();
    } catch (e) {
      const msg = redact(e?.message || String(e)).split('\n')[0];
      results.errors.push(`${kase}: ${msg}`);
      check(kase, 'the case ran to its end', false, msg);
    }
  };
  let t1;
  let t2;
  let t3;

  await step('a-a', async () => {
    t1 = await newTab(browser, 'tab1');
    await register(t1, R1, 'a-a');
    await spaGoto(t1.page, '/login');
    const id = opVerify(R1);
    check('a-a', 'R1 verified by the allowlisted operator step (same id)', id === R1.id);
    const t = Date.now();
    await login(t1.page, R1);
    const ps = patchesSince(t1.w, t);
    const res = ps.length ? await ps[0].req.response() : null;
    check('a-a', 'exactly one PATCH /users/me', ps.length === 1, `${ps.length} PATCH`);
    let body = null;
    try {
      body = JSON.parse(ps[0]?.body || 'null');
    } catch {
      body = null;
    }
    check('a-a', "the PATCH carries R1's name and phone and nothing else",
      body && body.displayName === R1.name && body.phoneNumber === R1.phone && Object.keys(body).length === 2,
      body ? `fields ${Object.keys(body).sort().join(',')}` : 'no body');
    check('a-a', "the PATCH is sent under R1's token", ps.length === 1 && ps[0].sub === R1.id, ps[0]?.sub ? 'sub matches' : 'no sub');
    check('a-a', 'the PATCH succeeds', res && res.status() >= 200 && res.status() < 300, `HTTP ${res?.status() ?? 'none'}`);
    check('a-a', 'the pending profile is cleared', (await pending(t1.page)) === null);
    await storageScan(t1, 'a-a', all);
    await screenshot(t1, 'a-a-r1-map');
  });

  await step('a-b', async () => {
    t2 = await newTab(browser, 'tab2');
    const t = Date.now();
    await register(t2, R2, 'a-b');
    await spaGoto(t2.page, '/login');
    await login(t2.page, R1);
    check('a-b', 'R1 lands on /map without the preparation screen', !t2.w.navs.some((n) => n.t >= t && n.path === '/preparing'));
    check('a-b', 'no PATCH /users/me', patchesSince(t2.w, t).length === 0, `${patchesSince(t2.w, t).length} PATCH`);
    const carried = requestsCarrying(t2.w, t, R2);
    check('a-b', "no request carries R2's name or phone", carried.length === 0, carried.join('; '));
    check('a-b', "R2's pending profile is discarded", (await pending(t2.page)) === null);
    await storageScan(t2, 'a-b', all);
    await screenshot(t2, 'a-b-r1-map');
  });

  await step('logout-1', async () => {
    if (!t2) throw new Error('tab2 is missing');
    const t = Date.now();
    const status = await signOut(t2.page);
    check('logout-1', 'R1 signs out (POST /auth/logout)', status !== null && status < 300, `HTTP ${status ?? 'none'}`);
    check('logout-1', 'nothing pending after the sign-out', (await pending(t2.page)) === null);
    const id = opVerify(R2);
    check('logout-1', 'R2 verified by the allowlisted operator step (same id)', id === R2.id);
    await login(t2.page, R2);
    check('logout-1', 'no PATCH /users/me for R2 after R1 signed out', patchesSince(t2.w, t).length === 0,
      `${patchesSince(t2.w, t).length} PATCH`);
    const carried = [...requestsCarrying(t2.w, t, R1), ...requestsCarrying(t2.w, t, R2)];
    check('logout-1', "no request carries R1's or R2's name or phone", carried.length === 0, carried.join('; '));
    await storageScan(t2, 'logout-1', all);
  });

  await step('logout-2', async () => {
    if (!t1) throw new Error('tab1 is missing');
    const t = Date.now();
    const status = await signOut(t1.page);
    check('logout-2', 'R1 signs out (POST /auth/logout)', status !== null && status < 300, `HTTP ${status ?? 'none'}`);
    await login(t1.page, R2);
    check('logout-2', 'no PATCH /users/me for R2 in the tab R1 registered in', patchesSince(t1.w, t).length === 0,
      `${patchesSince(t1.w, t).length} PATCH`);
    const carried = requestsCarrying(t1.w, t, R1);
    check('logout-2', "no request carries R1's name or phone", carried.length === 0, carried.join('; '));
    await storageScan(t1, 'logout-2', all);
  });

  await step('reload-b', async () => {
    t3 = await newTab(browser, 'tab3');
    const t = Date.now();
    await register(t3, R3, 'reload-b');
    await t3.page.goto(`${APP}/login`, { waitUntil: 'domcontentloaded', timeout: 60000 }); // full reload
    await t3.page.getByLabel('Email').waitFor({ timeout: 30000 });
    await login(t3.page, R1);
    check('reload-b', 'R1 lands on /map without the preparation screen', !t3.w.navs.some((n) => n.t >= t && n.path === '/preparing'));
    check('reload-b', 'no PATCH /users/me', patchesSince(t3.w, t).length === 0, `${patchesSince(t3.w, t).length} PATCH`);
    check('reload-b', 'no phone re-entry prompt', (await t3.page.getByText(PHONE_PROMPT).count()) === 0);
    const carried = requestsCarrying(t3.w, t, R3);
    check('reload-b', "no request carries R3's name or phone", carried.length === 0, carried.join('; '));
    check('reload-b', "R3's pending profile is discarded", (await pending(t3.page)) === null);
    await storageScan(t3, 'reload-b', all);
    await screenshot(t3, 'reload-b-r1-map');
  });

  await step('map', async () => {
    if (!t3) throw new Error('tab3 is missing');
    await mapCheck(t3, 'map');
  });

  await browser.close();
  clearTimeout(deadline);
  finish(0);
}

let finished = false;
function finish(code) {
  if (finished) return;
  finished = true;
  results.hold = opDone();
  results.finishedAt = stamp();
  results.verdict = results.checks.length > 0 && results.checks.every((c) => c.pass) && results.errors.length === 0
    ? 'PASS' : 'FAIL';
  const criterion = (c) => {
    if (/synthetic phone|holds no phone/.test(c.check)) return 'no phone in browser storage';
    return { 'a-a': 'A->A: exactly one correct PATCH', 'a-b': 'A->B: PATCH 0', 'logout-1': 'reload/logout do not transfer data',
      'logout-2': 'reload/logout do not transfer data', 'reload-b': 'reload/logout do not transfer data',
      map: 'map renders with a non-synthetic key' }[c.case] || c.case;
  };
  results.criteria = {};
  for (const c of results.checks) {
    const k = criterion(c);
    results.criteria[k] = results.criteria[k] ?? { pass: 0, fail: 0 };
    results.criteria[k][c.pass ? 'pass' : 'fail'] += 1;
  }
  for (const [k, v] of Object.entries(results.criteria)) say(`${v.fail ? 'FAIL' : 'PASS'} criterion: ${k} (${v.pass} pass, ${v.fail} fail)`);
  const file = path.join(EVIDENCE, 'u04-live-results.json');
  fs.writeFileSync(file, redact(JSON.stringify(results, null, 2)) + '\n', { mode: 0o600 });
  const sums = [...results.screenshots, 'u04-live-results.json'].map((f) => {
    const h = crypto.createHash('sha256').update(fs.readFileSync(path.join(EVIDENCE, f))).digest('hex');
    return `${h}  ${f}`;
  });
  fs.writeFileSync(path.join(EVIDENCE, 'SHA256SUMS'), sums.join('\n') + '\n');
  say(`U04 LIVE OWNERSHIP ${results.mode.toUpperCase()} ${results.verdict}: ${results.checks.filter((c) => c.pass).length}`
    + `/${results.checks.length} checks, ${results.errors.length} error(s); hold: ${results.hold}; ${file}`);
  process.exit(code || (results.verdict === 'PASS' ? 0 : 1));
}

main().catch((e) => {
  results.errors.push(redact(e?.message || String(e)).split('\n')[0]);
  finish(1);
});
