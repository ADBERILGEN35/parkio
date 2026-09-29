/**
 * Opt-in gateway HTTPS sibling-host CSRF lab.
 *
 * PARKIO_CSRF_GATEWAY_BROWSER=1 required (exit 2 if unset — no silent skip).
 * Starts disposable Postgres+Redis, real auth-service + gateway-service bootRun,
 * TLS frontends for app/api/evil/cross, then Chromium. Browser pages never receive
 * X-Gateway-Auth; the gateway stamps it. Auth captures Origin/Cookie presence.
 */
import { spawn, execFileSync } from 'node:child_process';
import { createServer as createHttpsServer } from 'node:https';
import { request as httpRequest } from 'node:http';
import { createConnection } from 'node:net';
import {
  closeSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { generateLabCerts } from './generate-certs.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const REPO = join(__dirname, '..', '..');
const GATEWAY_SECRET = 'test-only-parkio-gateway-internal-secret-0123456789';
const PASSWORD = 'StrongerPass123!';

const APP_PORT = 18443;
const API_PORT = 18444;
const EVIL_PORT = 18445;
const CROSS_PORT = 18446;
const AUTH_PORT = 18081;
const GATEWAY_PORT = 18080;
const REDIS_PORT = 16379;
const PG_PORT = 15432;

function fail(msg, code = 1) {
  console.error(msg);
  process.exit(code);
}

if (process.env.PARKIO_CSRF_GATEWAY_BROWSER !== '1') {
  fail('PARKIO_CSRF_GATEWAY_BROWSER must be 1 (refusing silent skip)', 2);
}

try {
  execFileSync('docker', ['info'], { stdio: 'ignore' });
} catch {
  fail('Docker daemon required for disposable Postgres/Redis (refusing incomplete lab)', 1);
}

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';

function waitPort(port, host = '127.0.0.1', timeoutMs = 180_000) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const tryOnce = () => {
      const socket = createConnection({ port, host }, () => {
        socket.end();
        resolve();
      });
      socket.on('error', () => {
        socket.destroy();
        if (Date.now() - start > timeoutMs) reject(new Error(`timeout waiting for ${host}:${port}`));
        else setTimeout(tryOnce, 400);
      });
    };
    tryOnce();
  });
}

function dockerRun(args) {
  return execFileSync('docker', ['run', '-d', '--rm', ...args], { encoding: 'utf8' }).trim();
}

function listenHttps(port, tls, handler) {
  return new Promise((resolve, reject) => {
    const server = createHttpsServer(tls, handler);
    server.listen(port, '127.0.0.1', () => resolve(server));
    server.on('error', reject);
  });
}

function reverseProxy(tls, listenPort, targetPort) {
  return listenHttps(listenPort, tls, (req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      const headers = { ...req.headers, host: `127.0.0.1:${targetPort}` };
      delete headers['x-gateway-auth'];
      const uReq = httpRequest(
        {
          hostname: '127.0.0.1',
          port: targetPort,
          path: req.url,
          method: req.method,
          headers,
        },
        (uRes) => {
          res.writeHead(uRes.statusCode || 502, uRes.headers);
          uRes.pipe(res);
        },
      );
      uReq.on('error', (err) => {
        res.writeHead(502, { 'content-type': 'text/plain' });
        res.end(String(err));
      });
      if (body.length) uReq.write(body);
      uReq.end();
    });
  });
}

function staticPage(tls, listenPort, apiOrigin, title) {
  const html = `<!doctype html><html><body><h1>${title}</h1>
<script>
window.__parkio = {
  api: ${JSON.stringify(apiOrigin)},
  async call(path, { headers = {}, body, method = 'POST' } = {}) {
    try {
      const response = await fetch(this.api + path, {
        method, credentials: 'include',
        headers: { 'content-type': 'application/json', ...headers },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await response.text();
      let json = null;
      try { json = text ? JSON.parse(text) : null; } catch { json = { raw: text }; }
      return { status: response.status, json, networkError: null,
        acao: response.headers.get('access-control-allow-origin'),
        acac: response.headers.get('access-control-allow-credentials') };
    } catch (err) {
      return { status: 0, networkError: String(err), json: null, acao: null, acac: null };
    }
  }
};
</script></body></html>`;
  return listenHttps(listenPort, tls, (_req, res) => {
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(html);
  });
}

async function httpJson(url, { method = 'POST', headers = {}, body } = {}) {
  const res = await fetch(url, {
    method,
    headers: { 'content-type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = { raw: text };
  }
  return { status: res.status, json, text };
}

function spawnBoot(module, env, logFile) {
  const fd = openSync(logFile, 'w');
  const child = spawn(
    process.platform === 'win32' ? 'gradlew.bat' : './gradlew',
    [`:${module}:bootRun`, '--no-daemon'],
    {
      cwd: REPO,
      env: { ...process.env, ...env },
      stdio: ['ignore', fd, fd],
      shell: process.platform === 'win32',
    },
  );
  child.__fd = fd;
  child.__log = logFile;
  return child;
}

async function main() {
  const playwrightPkg = process.env.PARKIO_CSRF_PLAYWRIGHT_PACKAGE;
  if (!playwrightPkg || !existsSync(join(playwrightPkg, 'index.mjs'))) {
    fail('PARKIO_CSRF_PLAYWRIGHT_PACKAGE must point at @playwright/test (index.mjs)');
  }

  const work = mkdtempSync(join(tmpdir(), 'parkio-csrf-gw-'));
  const captureFile = join(work, 'auth-captures.jsonl');
  const resultFile = join(work, 'result.json');
  writeFileSync(captureFile, '');
  const certs = generateLabCerts(join(work, 'certs'));
  const tls = { key: certs.key, cert: certs.cert };

  const appOrigin = `https://app.parkio.test:${APP_PORT}`;
  const apiOrigin = `https://api.parkio.test:${API_PORT}`;
  const evilOrigin = `https://evil.parkio.test:${EVIL_PORT}`;
  const crossOrigin = `https://cross.example.test:${CROSS_PORT}`;

  const containers = [];
  const children = [];
  const servers = [];

  const cleanup = async () => {
    for (const s of servers) {
      try {
        await new Promise((r) => s.close(r));
      } catch {
        /* ignore */
      }
    }
    for (const c of children) {
      try {
        c.kill();
      } catch {
        /* ignore */
      }
      try {
        closeSync(c.__fd);
      } catch {
        /* ignore */
      }
    }
    for (const id of containers) {
      try {
        execFileSync('docker', ['rm', '-f', id], { stdio: 'ignore' });
      } catch {
        /* ignore */
      }
    }
  };

  try {
    console.log('Starting Postgres + Redis...');
    containers.push(
      dockerRun([
        '-e',
        'POSTGRES_PASSWORD=csrf',
        '-e',
        'POSTGRES_USER=csrf',
        '-e',
        'POSTGRES_DB=parkio_auth',
        '-p',
        `${PG_PORT}:5432`,
        'postgres:16-alpine',
      ]),
    );
    containers.push(dockerRun(['-p', `${REDIS_PORT}:6379`, 'redis:7-alpine']));
    await waitPort(PG_PORT);
    await waitPort(REDIS_PORT);
    // Create gateway DB
    execFileSync(
      'docker',
      [
        'exec',
        containers[0],
        'psql',
        '-U',
        'csrf',
        '-d',
        'parkio_auth',
        '-c',
        'CREATE DATABASE parkio_gateway;',
      ],
      { stdio: 'inherit' },
    );

    const authLog = join(work, 'auth.log');
    const authEnv = {
      SERVER_PORT: String(AUTH_PORT),
      SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${PG_PORT}/parkio_auth`,
      SPRING_DATASOURCE_USERNAME: 'csrf',
      SPRING_DATASOURCE_PASSWORD: 'csrf',
      SPRING_DATA_REDIS_HOST: '127.0.0.1',
      SPRING_DATA_REDIS_PORT: String(REDIS_PORT),
      PARKIO_GATEWAY_INTERNAL_SECRET: GATEWAY_SECRET,
      PARKIO_CORS_ALLOWED_ORIGINS: appOrigin,
      PARKIO_REFRESH_COOKIE_SECURE: 'true',
      PARKIO_EMAIL_PROVIDER: 'logging',
      PARKIO_EMAIL_VERIFICATION_LOG_TOKEN: 'true',
      PARKIO_KAFKA_PROVISION_TOPICS: 'false',
      SPRING_KAFKA_LISTENER_AUTO_STARTUP: 'false',
      SPRING_APPLICATION_JSON: JSON.stringify({
        parkio: {
          'csrf-lab': {
            'capture-enabled': true,
            'capture-file': captureFile.replace(/\\/g, '/'),
          },
          security: {
            jwt: {
              'generate-ephemeral-key': true,
              'key-id': 'csrf-lab',
              issuer: 'parkio-auth-csrf-lab',
              audience: 'parkio-api-csrf-lab',
            },
            'refresh-cookie': {
              secure: true,
              'same-site': 'Strict',
              'allowed-origins': [appOrigin],
            },
            'email-verification': { 'log-token': true },
          },
          kafka: { 'provision-topics': false, relay: { enabled: false } },
          lifecycle: { retention: { 'outbox-enabled': false, 'inbox-enabled': false } },
          registration: { mode: 'open' },
          email: { provider: 'logging', 'allow-logging-provider': true },
          gateway: { 'internal-secret': GATEWAY_SECRET },
        },
        spring: { kafka: { listener: { 'auto-startup': false } } },
        management: { tracing: { enabled: false } },
      }),
    };

    console.log('Booting auth-service...');
    children.push(spawnBoot('services:auth-service', authEnv, authLog));
    await waitPort(AUTH_PORT);
    console.log('auth ready');

    const gwLog = join(work, 'gateway.log');
    const gwEnv = {
      SERVER_PORT: String(GATEWAY_PORT),
      SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${PG_PORT}/parkio_gateway`,
      SPRING_DATASOURCE_USERNAME: 'csrf',
      SPRING_DATASOURCE_PASSWORD: 'csrf',
      SPRING_DATA_REDIS_HOST: '127.0.0.1',
      SPRING_DATA_REDIS_PORT: String(REDIS_PORT),
      PARKIO_GATEWAY_INTERNAL_SECRET: GATEWAY_SECRET,
      PARKIO_AUTH_SERVICE_URI: `http://127.0.0.1:${AUTH_PORT}`,
      PARKIO_AUTH_JWKS_URI: `http://127.0.0.1:${AUTH_PORT}/api/v1/auth/.well-known/jwks.json`,
      PARKIO_CORS_ALLOWED_ORIGINS: appOrigin,
      PARKIO_CORS_ALLOW_CREDENTIALS: 'true',
      PARKIO_WAITLIST_ADMISSIONS_ENABLED: 'false',
      PARKIO_WAITLIST_HASH_SECRET: 'test-only-waitlist-hash-secret-0123456789',
      SPRING_APPLICATION_JSON: JSON.stringify({
        parkio: {
          security: {
            jwt: {
              issuer: 'parkio-auth-csrf-lab',
              audience: 'parkio-api-csrf-lab',
              'jwks-uri': `http://127.0.0.1:${AUTH_PORT}/api/v1/auth/.well-known/jwks.json`,
            },
          },
          gateway: {
            'internal-secret': GATEWAY_SECRET,
            cors: { 'allowed-origins': [appOrigin], 'allow-credentials': true },
            'user-status': { 'base-url': 'http://127.0.0.1:1' },
            'session-epoch': { 'base-url': `http://127.0.0.1:${AUTH_PORT}` },
          },
          waitlist: {
            'admissions-enabled': false,
            'hash-secret': 'test-only-waitlist-hash-secret-0123456789',
            email: { provider: 'logging', 'allow-logging-provider': true },
          },
        },
        management: { tracing: { enabled: false } },
      }),
    };

    console.log('Booting gateway-service...');
    children.push(spawnBoot('services:gateway-service', gwEnv, gwLog));
    await waitPort(GATEWAY_PORT);
    console.log('gateway ready');

    servers.push(await reverseProxy(tls, API_PORT, GATEWAY_PORT));
    servers.push(await staticPage(tls, APP_PORT, apiOrigin, 'app'));
    servers.push(await staticPage(tls, EVIL_PORT, apiOrigin, 'evil'));
    servers.push(await staticPage(tls, CROSS_PORT, apiOrigin, 'cross'));

    // Seed via loopback HTTP to the gateway (Node has no Playwright host-resolver-rules).
    // Browser traffic still uses https://api.parkio.test TLS front.
    const seedBase = `http://127.0.0.1:${GATEWAY_PORT}`;
    const email = `csrf-gw-${Date.now()}@example.com`;
    let reg = await httpJson(`${seedBase}/api/v1/auth/register`, {
      headers: { Origin: appOrigin },
      body: { email, password: PASSWORD },
    });
    if (reg.status !== 201 && reg.status !== 200) {
      fail(`register failed HTTP ${reg.status}: ${JSON.stringify(reg)} — see ${authLog}`);
    }

    await new Promise((r) => setTimeout(r, 2000));
    const authLogText = readFileSync(authLog, 'utf8');
    const tokenMatch =
      authLogText.match(/[?&]token=([A-Za-z0-9_-]+)/) ||
      authLogText.match(/rawToken=([A-Za-z0-9_-]+)/) ||
      authLogText.match(/token=([A-Za-z0-9_-]{20,})/);
    if (!tokenMatch) {
      fail(`verification token not found in ${authLog}`);
    }
    const verify = await httpJson(`${seedBase}/api/v1/auth/verify-email`, {
      headers: { Origin: appOrigin },
      body: { token: tokenMatch[1] },
    });
    if (verify.status >= 400) {
      fail(`verify-email failed: ${JSON.stringify(verify)}`);
    }

    console.log('Running Playwright...');
    const driver = spawn(process.execPath, [join(__dirname, 'playwright-gateway-driver.mjs')], {
      env: {
        ...process.env,
        PARKIO_CSRF_GATEWAY_BROWSER: '1',
        PARKIO_CSRF_APP_ORIGIN: appOrigin,
        PARKIO_CSRF_EVIL_ORIGIN: evilOrigin,
        PARKIO_CSRF_CROSS_ORIGIN: crossOrigin,
        PARKIO_CSRF_API_ORIGIN: apiOrigin,
        PARKIO_CSRF_EMAIL: email,
        PARKIO_CSRF_PASSWORD: PASSWORD,
        PARKIO_CSRF_RESULT_FILE: resultFile,
        PARKIO_CSRF_CAPTURE_FILE: captureFile,
        PARKIO_CSRF_PLAYWRIGHT_PACKAGE: playwrightPkg,
        NODE_TLS_REJECT_UNAUTHORIZED: '0',
      },
      stdio: 'inherit',
    });
    const code = await new Promise((resolve) => driver.on('exit', resolve));
    if (code !== 0) {
      if (existsSync(resultFile)) console.error(readFileSync(resultFile, 'utf8'));
      fail(`driver exited ${code}`, code || 1);
    }
    const result = JSON.parse(readFileSync(resultFile, 'utf8'));
    if (result.status !== 'passed') fail(`lab failed: ${JSON.stringify(result, null, 2)}`);

    const art = join(REPO, 'build', 'csrf-gateway-https-result.json');
    mkdirSync(dirname(art), { recursive: true });
    writeFileSync(art, JSON.stringify(result, null, 2));
    console.log('Gateway HTTPS CSRF lab PASSED');
    console.log(JSON.stringify({ layers: result.layers }, null, 2));
  } finally {
    await cleanup();
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
