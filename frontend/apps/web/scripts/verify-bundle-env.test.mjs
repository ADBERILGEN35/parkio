import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./verify-bundle-env.mjs', import.meta.url));
const placeholder = 'fixture-map-key-not-a-real-provider-key';

function runVerifier(env, expectedAppEnv = 'hosted-beta', extraArgs = []) {
  const fixture = mkdtempSync(join(tmpdir(), 'parkio-bundle-env-'));
  const assets = join(fixture, 'assets');
  mkdirSync(assets);
  // Vite 6 emits safe identifier keys without quotes in its minified object literal.
  const members = Object.entries(env)
    .map(([key, value]) => `${key}:${JSON.stringify(value)}`)
    .join(',');
  writeFileSync(
    join(assets, 'app.js'),
    `const injected={${members}};globalThis.__fixture=injected;`,
  );
  try {
    const args = [script, '--dist', fixture, '--app-env', expectedAppEnv, ...extraArgs];
    const result = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.ifError(result.error);
    return result;
  } finally {
    rmSync(fixture, { recursive: true, force: true });
  }
}

test('accepts a production-like bundle with all required public values', () => {
  const result = runVerifier({
    VITE_APP_ENV: 'hosted-beta',
    VITE_API_BASE_URL: 'https://api.fixture.invalid/api/v1',
    VITE_MAPTILER_KEY: placeholder,
  });

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /VITE_MAPTILER_KEY = PRESENT/);
  assert.doesNotMatch(`${result.stdout}${result.stderr}`, new RegExp(placeholder));
});

test('accepts an invite-production bundle with required public values', () => {
  const result = runVerifier(
    {
      VITE_APP_ENV: 'invite-production',
      VITE_API_BASE_URL: 'https://api.parkio.dev/api/v1',
      VITE_MAPTILER_KEY: placeholder,
    },
    'invite-production',
  );

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /production_like=true/);
  assert.match(result.stdout, /VITE_MAPTILER_KEY = PRESENT/);
});

test('rejects an invite-production bundle without MapTiler configuration', () => {
  const result = runVerifier(
    {
      VITE_APP_ENV: 'invite-production',
      VITE_API_BASE_URL: 'https://api.parkio.dev/api/v1',
    },
    'invite-production',
  );

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}${result.stderr}`, /VITE_MAPTILER_KEY is MISSING/);
});

test('rejects a missing required public value without printing any other value', () => {
  const result = runVerifier({
    VITE_APP_ENV: 'hosted-beta',
    VITE_API_BASE_URL: 'https://api.fixture.invalid/api/v1',
  });

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}${result.stderr}`, /VITE_MAPTILER_KEY is MISSING/);
});

test('rejects an empty required public value', () => {
  const result = runVerifier({
    VITE_APP_ENV: 'hosted-beta',
    VITE_API_BASE_URL: 'https://api.fixture.invalid/api/v1',
    VITE_MAPTILER_KEY: '',
  });

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}${result.stderr}`, /VITE_MAPTILER_KEY is EMPTY/);
});

test('rejects wrong VITE_APP_ENV build-argument wiring', () => {
  const result = runVerifier(
    {
      VITE_APP_ENV: 'development',
      VITE_API_BASE_URL: 'https://api.fixture.invalid/api/v1',
      VITE_MAPTILER_KEY: placeholder,
    },
    'hosted-beta',
  );

  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}${result.stderr}`, /build-arg wiring is wrong/);
  assert.doesNotMatch(`${result.stdout}${result.stderr}`, new RegExp(placeholder));
});

test('PROD-MUNI-01 rejects production bundle with municipal discovery enabled', () => {
  const result = runVerifier(
    {
      VITE_APP_ENV: 'production',
      VITE_API_BASE_URL: 'https://api.fixture.invalid/api/v1',
      VITE_MAPTILER_KEY: placeholder,
      VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'true',
    },
    'production',
  );
  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}${result.stderr}`, /PROD-MUNI-01 bake guard/);
});

test('PROD-MUNI-01 require-municipal false rejects municipal-on bundle', () => {
  const result = runVerifier(
    {
      VITE_APP_ENV: 'hosted-beta',
      VITE_API_BASE_URL: 'https://api.fixture.invalid/api/v1',
      VITE_MAPTILER_KEY: placeholder,
      VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'true',
    },
    'hosted-beta',
    ['--require-municipal', 'false'],
  );
  assert.notEqual(result.status, 0);
  assert.match(`${result.stdout}${result.stderr}`, /must be false/);
});

test('PROD-MUNI-01 require-municipal true accepts municipal-on bundle', () => {
  const result = runVerifier(
    {
      VITE_APP_ENV: 'hosted-beta',
      VITE_API_BASE_URL: 'https://api.fixture.invalid/api/v1',
      VITE_MAPTILER_KEY: placeholder,
      VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'true',
    },
    'hosted-beta',
    ['--require-municipal', 'true'],
  );
  assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
});
