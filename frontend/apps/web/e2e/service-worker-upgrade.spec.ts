import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';

/**
 * CL-F39.3 browser acceptance: the real public/sw.js in Chromium across two releases served from
 * one origin, with real Cache Storage and worker lifecycle. Each release is the app shell from
 * public/ plus a synthetic hashed bundle; its sw.js has the version placeholder replaced the way
 * the production build does. A worker without the placeholder is the same file in every release.
 */

const publicDir = resolve(dirname(fileURLToPath(import.meta.url)), '../public');
const offlineTitle = /<title>([^<]+)<\/title>/.exec(readFileSync(join(publicDir, 'offline.html'), 'utf8'))![1];

const contentTypes: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json',
};

interface Release {
  root: string;
  name: string;
  bundle: string;
}

/** Writes one release: the shell files the worker precaches, a hashed bundle and a stamped worker. */
function writeRelease(parent: string, name: string, version: string): Release {
  const root = join(parent, name);
  const bundle = `app-${name}${version.slice(0, 6)}.js`;
  mkdirSync(join(root, 'assets'), { recursive: true });
  mkdirSync(join(root, 'icons'), { recursive: true });
  for (const icon of ['parkio-icon.svg', 'parkio-maskable.svg']) {
    cpSync(join(publicDir, 'icons', icon), join(root, 'icons', icon));
  }
  cpSync(join(publicDir, 'offline.html'), join(root, 'offline.html'));
  writeFileSync(join(root, 'manifest.webmanifest'), JSON.stringify({ name: 'Parkio', release: name }));
  writeFileSync(
    join(root, 'assets', bundle),
    `document.getElementById('release').textContent = ${JSON.stringify(name)};\n` +
      "navigator.serviceWorker.register('/sw.js');\n",
  );
  writeFileSync(
    join(root, 'index.html'),
    `<!doctype html><html><head><meta charset="utf-8"><title>Parkio ${name}</title></head>` +
      `<body><p id="release"></p><script src="/assets/${bundle}"></script></body></html>`,
  );
  writeFileSync(
    join(root, 'sw.js'),
    readFileSync(join(publicDir, 'sw.js'), 'utf8').replaceAll('__PARKIO_SW_VERSION__', version),
  );
  return { root, name, bundle };
}

/** Serves one release at a time from a fixed origin; `down` drops every connection (offline). */
class ReleaseServer {
  root = '';
  down = false;
  private readonly server: Server = createServer((request, response) => {
    if (this.down) {
      request.socket.destroy();
      return;
    }
    const { pathname } = new URL(request.url ?? '/', 'http://127.0.0.1');
    const file = pathname === '/' || extname(pathname) === '' ? 'index.html' : pathname.slice(1);
    let body: Buffer;
    try {
      body = readFileSync(join(this.root, file));
    } catch {
      response.writeHead(404).end();
      return;
    }
    response.writeHead(200, {
      'Content-Type': contentTypes[extname(file)] ?? 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    response.end(body);
  });

  async start() {
    await new Promise<void>((done) => this.server.listen(0, '127.0.0.1', done));
    return `http://127.0.0.1:${(this.server.address() as AddressInfo).port}`;
  }

  async stop() {
    this.server.closeAllConnections();
    await new Promise<void>((done) => this.server.close(() => done()));
  }
}

let tmp: string;
let server: ReleaseServer;
let origin: string;
let releaseA: Release;
let releaseB: Release;

test.beforeEach(async () => {
  tmp = mkdtempSync(join(tmpdir(), 'parkio-sw-upgrade-'));
  releaseA = writeRelease(tmp, 'A', 'a1a1a1a1a1a1a1a1');
  releaseB = writeRelease(tmp, 'B', 'b2b2b2b2b2b2b2b2');
  server = new ReleaseServer();
  origin = await server.start();
});

test.afterEach(async () => {
  await server.stop();
  rmSync(tmp, { recursive: true, force: true });
});

async function cacheState(page: Page) {
  return page.evaluate(async () => {
    const names = await caches.keys();
    const paths: string[] = [];
    for (const name of names) {
      for (const request of await (await caches.open(name)).keys()) {
        paths.push(new URL(request.url).pathname);
      }
    }
    return { names, paths: paths.sort() };
  });
}

/** First visit: the page registers the worker, which installs, activates and claims the page. */
async function visit(page: Page, release: Release) {
  server.root = release.root;
  await page.goto(`${origin}/`);
  await expect(page.locator('#release')).toHaveText(release.name);
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
}

/** Deploys a release and runs the browser's update check; waits for a new worker if there is one. */
async function deploy(page: Page, release: Release) {
  server.root = release.root;
  return page.evaluate(async () => {
    const registration = await navigator.serviceWorker.getRegistration();
    if (!registration) throw new Error('no service worker registration');
    await registration.update();
    const next = registration.installing ?? registration.waiting;
    if (!next) return 'unchanged';
    await new Promise<void>((done) => {
      const settled = () => {
        if (next.state === 'activated' || next.state === 'redundant') done();
      };
      next.addEventListener('statechange', settled);
      settled();
    });
    return next.state;
  });
}

async function fetchText(page: Page, path: string) {
  return page.evaluate(async (target) => (await fetch(target, { cache: 'no-store' })).text(), path);
}

test('only the current release stays cached after an upgrade', async ({ page }) => {
  await visit(page, releaseA);
  await page.reload(); // the bundle is now requested through the worker
  await expect.poll(async () => (await cacheState(page)).paths).toContain(`/assets/${releaseA.bundle}`);

  await deploy(page, releaseB);
  await page.reload();
  await expect(page.locator('#release')).toHaveText('B');
  await expect.poll(async () => (await cacheState(page)).paths).toContain(`/assets/${releaseB.bundle}`);

  const state = await cacheState(page);
  expect(state.paths).not.toContain(`/assets/${releaseA.bundle}`);
  expect(state.names).toHaveLength(1);
});

test('serves the offline shell after an upgrade', async ({ page }) => {
  await visit(page, releaseA);
  await deploy(page, releaseB);

  server.down = true;
  await page.goto(`${origin}/explore`);

  await expect(page).toHaveTitle(offlineTitle);
});

test('fetches a changed manifest from the network and keeps it for offline use', async ({ page }) => {
  await visit(page, releaseA);
  const changed = JSON.stringify({ name: 'Parkio', release: 'A', revised: true });
  writeFileSync(join(releaseA.root, 'manifest.webmanifest'), changed);

  expect(await fetchText(page, '/manifest.webmanifest')).toBe(changed);

  server.down = true;
  expect(await fetchText(page, '/manifest.webmanifest')).toBe(changed);
});
