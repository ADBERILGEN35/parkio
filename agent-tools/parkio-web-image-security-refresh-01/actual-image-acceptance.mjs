#!/usr/bin/env node

import { execFileSync, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../../frontend/apps/web/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const image = process.argv[2];
const port = process.argv[3] ?? '18085';
if (!image) throw new Error('usage: actual-image-acceptance.mjs IMAGE [PORT]');

const name = `parkio-web-security-acceptance-${process.pid}`;
const base = `http://127.0.0.1:${port}`;
const checks = [];

function docker(args, options = {}) {
  return execFileSync('docker', args, { encoding: 'utf8', ...options }).trim();
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
  checks.push(message);
}

async function waitForHealthy() {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    try {
      const health = docker(['inspect', '--format', '{{.State.Health.Status}}', name]);
      if (health === 'healthy') return health;
    } catch {
      // Container may still be starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error('container did not become healthy within 60 seconds');
}

async function main() {
  spawnSync('docker', ['rm', '-f', name], { stdio: 'ignore' });
  docker(['run', '-d', '--name', name, '-p', `127.0.0.1:${port}:80`, image]);

  try {
    assert((await waitForHealthy()) === 'healthy', 'container health is healthy');

    const index = await fetch(`${base}/index.html`);
    const indexText = await index.text();
    assert(index.status === 200, 'index returns HTTP 200');
    assert(index.headers.get('content-type')?.startsWith('text/html'), 'index is text/html');
    assert(index.headers.get('cache-control') === 'no-cache', 'index cache is no-cache');
    for (const header of [
      'content-security-policy',
      'referrer-policy',
      'x-content-type-options',
      'x-frame-options',
      'permissions-policy',
    ]) {
      assert(Boolean(index.headers.get(header)), `index includes ${header}`);
    }

    const assetPaths = [...indexText.matchAll(/(?:src|href)="([^"]+\.(?:js|css))"/g)].map(
      (match) => match[1],
    );
    const jsPath = assetPaths.find((path) => path.endsWith('.js'));
    const cssPath = docker([
      'exec',
      name,
      'sh',
      '-c',
      "find /usr/share/nginx/html/assets -maxdepth 1 -type f -name '*.css' | head -n 1",
    ]).replace('/usr/share/nginx/html', '');
    assert(Boolean(jsPath), 'index references a JS asset');
    assert(Boolean(cssPath), 'image contains a compiled CSS asset');
    for (const [path, expectedType] of [
      [jsPath, 'javascript'],
      [cssPath, 'text/css'],
    ]) {
      const response = await fetch(new URL(path, base));
      assert(response.status === 200, `${path} returns HTTP 200`);
      assert(response.headers.get('content-type')?.includes(expectedType), `${path} has ${expectedType} MIME`);
      assert(
        response.headers.get('cache-control') === 'public, max-age=31536000, immutable',
        `${path} has immutable cache policy`,
      );
    }

    const browser = await chromium.launch();
    try {
      for (const route of ['/login', '/explore', '/map', '/verify-email?token=synthetic']) {
        const page = await browser.newPage();
        const errors = [];
        page.on('pageerror', (error) => errors.push(String(error)));
        await page.route('**/*', async (requestRoute) => {
          const requestUrl = new URL(requestRoute.request().url());
          if (requestUrl.origin === base) return requestRoute.continue();
          if (requestUrl.origin === 'https://api.parkio.dev') {
            return requestRoute.fulfill({
              status: 401,
              contentType: 'application/json',
              body: JSON.stringify({ code: 'SYNTHETIC_UNAUTHORIZED', message: 'synthetic acceptance' }),
            });
          }
          return requestRoute.abort('blockedbyclient');
        });
        const response = await page.goto(`${base}${route}`, { waitUntil: 'load' });
        await page.waitForFunction(() => (document.querySelector('#root')?.children.length ?? 0) > 0);
        assert(response?.status() === 200, `${route} deep link returns HTTP 200`);
        assert((await page.locator('#root').count()) === 1, `${route} mounts the SPA root`);
        assert((await page.locator('body').innerText()).trim().length > 0, `${route} renders visible text`);
        assert(errors.length === 0, `${route} has no browser page error`);
        await page.close();
      }
    } finally {
      await browser.close();
    }

    const top = docker(['top', name, '-eo', 'pid,user,args']);
    assert(/^\s*\d+\s+root\s+nginx: master process/m.test(top), 'nginx master runs as root (current design)');
    assert(
      /^\s*\d+\s+(?!root\s)\S+\s+nginx: worker process/m.test(top),
      'nginx workers run as non-root UID 101 (host username mapping may differ)',
    );
    const permissions = docker([
      'exec',
      name,
      'sh',
      '-c',
      "stat -c '%a %U:%G' /usr/share/nginx/html /usr/share/nginx/html/index.html",
    ]);
    assert(/^755 root:root\n644 root:root$/.test(permissions), 'static files retain 0755/0644 root-owned permissions');
  } finally {
    spawnSync('docker', ['rm', '-f', name], { stdio: 'ignore' });
  }

  console.log(`actual-image-acceptance: PASS (${checks.length}/${checks.length})`);
  for (const check of checks) console.log(`PASS ${check}`);
  console.log('browser-network: production API mocked; all other external origins blocked');
}

main().catch((error) => {
  spawnSync('docker', ['rm', '-f', name], { stdio: 'ignore' });
  console.error(`actual-image-acceptance: FAIL: ${error.message}`);
  process.exit(1);
});
