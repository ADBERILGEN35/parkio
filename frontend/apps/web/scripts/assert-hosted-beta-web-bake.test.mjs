#!/usr/bin/env node
/**
 * Asserts canonical hosted-beta web bake profiles and the source-controlled
 * web release pin stay coherent (P01F Explore recurrence prevention).
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../../../', import.meta.url));
const EXPECTED_DIGEST =
  'sha256:d9999a020376cc89b86a92410ceb784a7290258f4d1c0c0d968a6a78d62c443a';

function readKv(relPath) {
  const text = readFileSync(join(root, relPath), 'utf8');
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq === -1) continue;
    out[line.slice(0, eq)] = line.slice(eq + 1);
  }
  return out;
}

test('release bake keeps live API + Explore on + municipal off', () => {
  const bake = readKv('docker/web-hosted-beta.release-bake.env');
  assert.equal(bake.VITE_APP_ENV, 'hosted-beta');
  assert.equal(bake.VITE_API_BASE_URL, 'https://api.parkio.dev/api/v1');
  assert.equal(bake.VITE_PUBLIC_EXPLORE_ENABLED, 'true');
  assert.equal(bake.VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED, 'false');
  assert.equal(bake.PARKIO_PUBLIC_EXPLORE_ENABLED, 'true');
});

test('municipal-on bake prepares Explore+municipal without dropping API base', () => {
  const bake = readKv('docker/web-hosted-beta.municipal-on.bake.env');
  assert.equal(bake.VITE_API_BASE_URL, 'https://api.parkio.dev/api/v1');
  assert.equal(bake.VITE_PUBLIC_EXPLORE_ENABLED, 'true');
  assert.equal(bake.VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED, 'true');
});

test('web release pin and compose.production.files reconcile live digest', () => {
  const pin = readFileSync(join(root, 'docker/docker-compose.web-release-pin.yml'), 'utf8');
  assert.match(pin, new RegExp(EXPECTED_DIGEST.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
  const files = readFileSync(join(root, 'docker/compose.production.files'), 'utf8');
  assert.match(files, /docker\/docker-compose\.web-release-pin\.yml/);
});

test('azure hosted-beta example documents Explore on for live rebuilds', () => {
  const env = readKv('docker/.env.azure-hosted-beta.example');
  assert.equal(env.VITE_API_BASE_URL, 'https://api.parkio.dev/api/v1');
  assert.equal(env.VITE_PUBLIC_EXPLORE_ENABLED, 'true');
  assert.equal(env.VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED, 'false');
});
