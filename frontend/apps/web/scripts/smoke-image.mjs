#!/usr/bin/env node
/**
 * Production-shaped image smoke test for the Parkio web SPA.
 *
 * Runs the built container and asserts the application actually *mounts* - not merely that
 * nginx answers. Defect I11 (v1.0.0-rc5) passed every HTTP-level check: index.html and all
 * assets returned 200 while `config/env.ts` threw during module evaluation, so React never
 * mounted and every route rendered a blank page.
 *
 * Detects:
 *   1. missing VITE_MAPTILER_KEY in the served bundle
 *   2. empty VITE_MAPTILER_KEY in the served bundle
 *   3. wrong build-arg wiring (VITE_APP_ENV not the expected environment)
 *   4. SPA white-screen: #root never receives children, or a module-evaluation pageerror
 *
 * No configuration value is ever printed - only names and PRESENT/EMPTY/MISSING statuses.
 *
 * Usage:
 *   node scripts/smoke-image.mjs --image <ref> [--app-env hosted-beta] [--port 18080]
 *                               [--docker docker]
 *
 * Exit code 0 = smoke passed.
 */

import { execFileSync, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

function parseArgs(argv) {
  const out = { image: undefined, appEnv: 'hosted-beta', port: '18080', docker: 'docker' };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--image') out.image = argv[++i];
    else if (argv[i] === '--app-env') out.appEnv = argv[++i];
    else if (argv[i] === '--port') out.port = argv[++i];
    else if (argv[i] === '--docker') out.docker = argv[++i];
    else throw new Error(`unknown argument: ${argv[i]}`);
  }
  if (!out.image) throw new Error('--image is required');
  return out;
}

const { image, appEnv, port, docker } = parseArgs(process.argv.slice(2));
const containerName = `parkio-web-smoke-${process.pid}`;
const baseUrl = `http://127.0.0.1:${port}`;
const failures = [];

function sh(file, args, opts = {}) {
  return execFileSync(file, args, { encoding: 'utf8', ...opts });
}

function stopContainer() {
  spawnSync(docker, ['rm', '-f', containerName], { stdio: 'ignore' });
}

async function waitForHttp(url, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = 'no attempt made';
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.ok) return response;
      lastError = `HTTP ${response.status}`;
    } catch (error) {
      lastError = String(error);
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`timed out waiting for ${url}: ${lastError}`);
}

/** Reads the injected import.meta.env object out of the *served* JS assets. */
async function readServedEnv() {
  const indexHtml = await (await waitForHttp(`${baseUrl}/`)).text();
  const scriptPaths = [...indexHtml.matchAll(/src="([^"]+\.js)"/g)].map((m) => m[1]);
  if (scriptPaths.length === 0) throw new Error('index.html referenced no JS entry point');

  const seen = new Set();
  const queue = [...scriptPaths];
  while (queue.length > 0) {
    const path = queue.shift();
    if (seen.has(path)) continue;
    seen.add(path);
    const url = path.startsWith('http') ? path : `${baseUrl}${path.startsWith('/') ? '' : '/'}${path}`;
    const response = await fetch(url);
    if (!response.ok) continue;
    const source = await response.text();
    const appEnvMember = /["']?\bVITE_APP_ENV["']?\s*:/.exec(source);
    if (appEnvMember) {
      const anchor = appEnvMember.index;
      const window = source.slice(Math.max(0, anchor - 4000), anchor + 4000);
      const env = {};
      const pair = /["']?(VITE_[A-Z0-9_]+)["']?\s*:\s*"((?:[^"\\]|\\.)*)"/g;
      let match;
      while ((match = pair.exec(window)) !== null) env[match[1]] = match[2];
      if (Object.keys(env).length > 0) return env;
    }
    // Follow same-origin static asset references so Vite's relative imports (for example
    // `./env-ABC.js`) and lazy chunks are reachable from the entry module.
    for (const ref of source.matchAll(/["'`]([^"'`]+\.js)["'`]/g)) {
      const resolved = new URL(ref[1], url);
      if (resolved.origin === baseUrl && resolved.pathname.startsWith('/assets/')) {
        queue.push(resolved.href);
      }
    }
  }
  throw new Error('could not find the injected import.meta.env object in any served asset');
}

async function checkPublicStaticSurface() {
  const required = [
    '/robots.txt',
    '/sitemap.xml',
    '/icons/favicon-32.png',
    '/icons/parkio-icon-192.png',
    '/icons/parkio-icon-512.png',
    '/og-parkio.png',
    '/social-preview.png',
    '/manifest.webmanifest',
    '/sw.js',
  ];
  for (const path of required) {
    const response = await fetch(`${baseUrl}${path}`);
    console.log(`smoke-image: static ${path} = HTTP ${response.status}`);
    if (!response.ok) failures.push(`required static resource ${path} returned HTTP ${response.status}`);
  }

  const explore = await fetch(`${baseUrl}/explore`);
  const exploreHtml = await explore.text();
  if (!explore.ok || !exploreHtml.includes('Live public parking explore')) {
    failures.push('/explore did not return the crawler-readable public entry');
  }

  for (const [path, location] of [
    ['/privacy', 'https://parkio.dev/privacy/'],
    ['/terms', 'https://parkio.dev/terms/'],
  ]) {
    const response = await fetch(`${baseUrl}${path}`, { redirect: 'manual' });
    if (response.status !== 302 || response.headers.get('location') !== location) {
      failures.push(`${path} did not return the authoritative 302 redirect`);
    }
  }
}

async function checkMount() {
  let chromium;
  try {
    ({ chromium } = require('playwright'));
  } catch {
    try {
      ({ chromium } = require('@playwright/test'));
    } catch {
      return { skipped: 'playwright is not installed' };
    }
  }

  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    const pageErrors = [];
    page.on('pageerror', (error) => pageErrors.push(String(error)));

    await page.goto(`${baseUrl}/login`, { waitUntil: 'load', timeout: 45_000 });

    let rootChildren = 0;
    try {
      await page.waitForFunction(
        () => (document.getElementById('root')?.children.length ?? 0) > 0,
        undefined,
        { timeout: 20_000 },
      );
      rootChildren = await page.evaluate(
        () => document.getElementById('root')?.children.length ?? 0,
      );
    } catch {
      rootChildren = await page.evaluate(
        () => document.getElementById('root')?.children.length ?? 0,
      );
    }

    const textLength = await page.evaluate(() => document.body.innerText.trim().length);
    const inputCount = await page.locator('input').count();
    await browser.close();
    return { rootChildren, textLength, inputCount, pageErrors };
  } catch (error) {
    await browser.close();
    throw error;
  }
}

async function main() {
  console.log(`smoke-image: image=${image} app_env=${appEnv} port=${port}`);
  stopContainer();
  sh(docker, ['run', '-d', '--name', containerName, '-p', `127.0.0.1:${port}:80`, image]);

  try {
    // ---- 1-3: served bundle configuration ----
    const env = await readServedEnv();
    const publicConfigValues = Object.values(env).filter(
      (value) => typeof value === 'string' && value.length > 0,
    );
    const redact = (message) =>
      publicConfigValues.reduce(
        (safe, value) => safe.replaceAll(value, '[REDACTED_PUBLIC_CONFIG]'),
        String(message),
      );
    const servedAppEnv = env.VITE_APP_ENV ?? '';
    console.log(`smoke-image: VITE_APP_ENV=${servedAppEnv || '(unset)'}`);
    if (servedAppEnv !== appEnv) {
      failures.push(
        `VITE_APP_ENV is "${servedAppEnv}" but "${appEnv}" was expected (build-arg wiring is wrong)`,
      );
    }

    for (const key of ['VITE_API_BASE_URL', 'VITE_MAPTILER_KEY']) {
      const status = !(key in env) ? 'MISSING' : env[key].trim() === '' ? 'EMPTY' : 'PRESENT';
      console.log(`smoke-image:   ${key} = ${status}`);
      if (status !== 'PRESENT') failures.push(`${key} is ${status} in the served bundle`);
    }

    // ---- 4: crawler/static files and legal redirects must be readable in the image ----
    await checkPublicStaticSurface();

    // ---- 5: the SPA must actually mount ----
    const mount = await checkMount();
    if (mount.skipped) {
      failures.push(
        `white-screen guard could not run (${mount.skipped}); install playwright so this gate is enforced`,
      );
    } else {
      console.log(
        `smoke-image: #root children=${mount.rootChildren} bodyText=${mount.textLength} inputs=${mount.inputCount} pageErrors=${mount.pageErrors.length}`,
      );
      if (mount.rootChildren === 0) {
        failures.push('SPA white-screen: #root received no children (React never mounted)');
      }
      if (mount.textLength === 0) failures.push('SPA rendered no visible text');
      if (mount.inputCount === 0) failures.push('login page rendered no input fields');
      for (const error of mount.pageErrors) {
        failures.push(`uncaught page error: ${redact(error).slice(0, 300)}`);
      }
    }
  } finally {
    try {
      const logs = sh(docker, ['logs', '--tail', '20', containerName], {
        stdio: ['ignore', 'pipe', 'pipe'],
      });
      if (failures.length > 0 && logs.trim()) console.error(`\nsmoke-image: container logs:\n${logs}`);
    } catch {
      /* logs are best-effort diagnostics only */
    }
    stopContainer();
  }

  if (failures.length > 0) {
    console.error('\nsmoke-image: FAILED');
    for (const failure of failures) console.error(`  - ${failure}`);
    process.exit(1);
  }
  console.log('smoke-image: OK');
}

main().catch((error) => {
  stopContainer();
  console.error(`smoke-image: FAILED\n  - ${error.message ?? error}`);
  process.exit(1);
});
