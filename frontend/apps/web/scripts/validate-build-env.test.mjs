import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), 'validate-build-env.mjs');
const FIXTURE_KEY = 'fixture-public-map-key-never-use-in-production';

function run(overrides) {
  const env = {
    ...process.env,
    VITE_APP_ENV: 'hosted-beta',
    VITE_API_BASE_URL: 'https://api.fixture.invalid/api/v1',
    VITE_MAPTILER_KEY: FIXTURE_KEY,
    ...overrides,
  };
  const result = spawnSync(process.execPath, [SCRIPT], { env, encoding: 'utf8' });
  const output = `${result.stdout}${result.stderr}`;
  assert.equal(output.includes(FIXTURE_KEY), false, 'validator must never print the value');
  return { ...result, output };
}

test('accepts non-empty production-shaped public configuration without printing values', () => {
  const result = run({});
  assert.equal(result.status, 0, result.output);
  assert.match(result.output, /VITE_MAPTILER_KEY = PRESENT/);
});

test('rejects a missing MapTiler key for hosted-beta', () => {
  const result = run({ VITE_MAPTILER_KEY: undefined });
  assert.notEqual(result.status, 0);
  assert.match(result.output, /VITE_MAPTILER_KEY = EMPTY/);
});

test('rejects an explicitly empty MapTiler key for production', () => {
  const result = run({ VITE_APP_ENV: 'production', VITE_MAPTILER_KEY: '   ' });
  assert.notEqual(result.status, 0);
  assert.match(result.output, /VITE_MAPTILER_KEY is required/);
});

test('does not require the MapTiler key for a non-production development build', () => {
  const result = run({ VITE_APP_ENV: 'development', VITE_MAPTILER_KEY: undefined });
  assert.equal(result.status, 0, result.output);
  assert.doesNotMatch(result.output, /VITE_MAPTILER_KEY/);
});

test('PROD-MUNI-01 rejects production bake with municipal discovery enabled', () => {
  const result = run({
    VITE_APP_ENV: 'production',
    VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'true',
  });
  assert.notEqual(result.status, 0);
  assert.match(result.output, /PROD-MUNI-01 bake guard|must not be true when VITE_APP_ENV=production/);
});

test('PROD-MUNI-01 allows hosted-beta bake with municipal discovery enabled', () => {
  const result = run({
    VITE_APP_ENV: 'hosted-beta',
    VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'true',
  });
  assert.equal(result.status, 0, result.output);
});

test('PROD-MUNI-01 allows production bake with municipal discovery false', () => {
  const result = run({
    VITE_APP_ENV: 'production',
    VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED: 'false',
  });
  assert.equal(result.status, 0, result.output);
});
