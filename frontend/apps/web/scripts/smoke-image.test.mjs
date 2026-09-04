import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const smokeScript = fileURLToPath(new URL('./smoke-image.mjs', import.meta.url));
const placeholder = 'fixture-map-key-not-a-real-provider-key';
const docker = process.env.PARKIO_DOCKER_COMMAND ?? 'docker';
const runId = `${process.pid}-${Date.now()}`;
const builtImages = [];

function command(file, args, options = {}) {
  return spawnSync(file, args, { encoding: 'utf8', ...options });
}

function fixtureSource(scenario) {
  const bootstrap = scenario === 'white-screen'
    ? `throw new Error("fixture module-evaluation failure ${placeholder}")`
    : `document.getElementById("root").innerHTML="<main>Login<label>Email<input></label></main>"`;
  return `import { injected } from "./env.js";globalThis.__fixture=injected;${bootstrap};`;
}

function fixtureEnvSource(scenario) {
  const appEnv = scenario === 'wrong-app-env' ? 'development' : 'hosted-beta';
  const env = {
    VITE_APP_ENV: appEnv,
    VITE_API_BASE_URL: 'https://api.fixture.invalid/api/v1',
  };
  if (scenario !== 'missing-key') {
    env.VITE_MAPTILER_KEY = scenario === 'empty-key' ? '' : placeholder;
  }
  return `export const injected=${JSON.stringify(env)};`;
}

function buildFixtureImage(scenario) {
  const context = mkdtempSync(join(tmpdir(), `parkio-smoke-${scenario}-`));
  const image = `parkio/web-smoke-fixture:${runId}-${scenario}`;
  writeFileSync(
    join(context, 'Dockerfile'),
    'FROM nginx:1.27-alpine\nCOPY default.conf /etc/nginx/conf.d/default.conf\nCOPY public/ /usr/share/nginx/html/\n',
  );
  writeFileSync(
    join(context, 'default.conf'),
    'server { listen 80; root /usr/share/nginx/html; location = /privacy { return 302 https://parkio.dev/privacy/; } location = /terms { return 302 https://parkio.dev/terms/; } location = /explore { try_files /explore/index.html =404; } location / { try_files $uri $uri/ /index.html; } }\n',
  );
  const publicDir = join(context, 'public');
  mkdirSync(join(publicDir, 'assets'), { recursive: true });
  mkdirSync(join(publicDir, 'icons'), { recursive: true });
  mkdirSync(join(publicDir, 'explore'), { recursive: true });
  writeFileSync(
    join(publicDir, 'index.html'),
    '<!doctype html><html><body><div id="root"></div><script type="module" src="/assets/app.js"></script></body></html>',
  );
  writeFileSync(join(publicDir, 'assets', 'app.js'), fixtureSource(scenario));
  writeFileSync(join(publicDir, 'assets', 'env.js'), fixtureEnvSource(scenario));
  writeFileSync(join(publicDir, 'explore', 'index.html'), 'Live public parking explore');
  for (const path of [
    'robots.txt', 'sitemap.xml', 'og-parkio.png', 'social-preview.png',
    'manifest.webmanifest', 'sw.js', 'icons/favicon-32.png',
    'icons/parkio-icon-192.png', 'icons/parkio-icon-512.png',
  ]) {
    writeFileSync(join(publicDir, path), 'fixture');
  }
  const result = command(docker, ['build', '--quiet', '--tag', image, context]);
  rmSync(context, { recursive: true, force: true });
  assert.equal(result.status, 0, `fixture build failed: ${result.stderr}`);
  builtImages.push(image);
  return image;
}

function runSmoke(image, port) {
  return command(process.execPath, [
    smokeScript,
    '--image',
    image,
    '--app-env',
    'hosted-beta',
    '--port',
    String(port),
    '--docker',
    docker,
  ]);
}

test('production-shaped image smoke detects rc5 failure modes and accepts a mounted SPA', async (t) => {
  const scenarios = [
    ['valid', true, /smoke-image: OK/],
    ['missing-key', false, /VITE_MAPTILER_KEY is MISSING/],
    ['empty-key', false, /VITE_MAPTILER_KEY is EMPTY/],
    ['wrong-app-env', false, /build-arg wiring is wrong/],
    ['white-screen', false, /SPA white-screen|uncaught page error/],
  ];

  try {
    let port = 18180;
    for (const [scenario, succeeds, expected] of scenarios) {
      await t.test(scenario, () => {
        const image = buildFixtureImage(scenario);
        const result = runSmoke(image, port++);
        const output = `${result.stdout}${result.stderr}`;
        assert.equal(result.status === 0, succeeds, output);
        assert.match(output, expected);
        assert.doesNotMatch(output, new RegExp(placeholder));
      });
    }
  } finally {
    if (builtImages.length > 0) {
      command(docker, ['image', 'rm', '--force', ...builtImages]);
    }
  }
});
