#!/usr/bin/env node
/**
 * Asserts hosted-beta web bake profiles, effective release-pin image (not comments),
 * and Compose overlay precedence for services.web.image.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  assertPinDigestEquals,
  assertWebReleasePinContract,
  extractDigest,
  parseComposeServiceImage,
  resolveEffectiveWebImage,
} from './lib/web-release-pin.mjs';

const root = fileURLToPath(new URL('../../../../', import.meta.url));

function read(relPath) {
  return readFileSync(join(root, relPath), 'utf8');
}

function readKv(relPath) {
  const text = read(relPath);
  const out = {};
  for (const line of text.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq === -1) continue;
    out[line.slice(0, eq)] = line.slice(eq + 1);
  }
  return out;
}

function productionComposeContents() {
  const listed = read('docker/compose.production.files')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'));
  return listed.map((path) => ({ path, text: read(path) }));
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

test('parses effective services.web.image and ignores comment digests', () => {
  const pin = read('docker/docker-compose.web-release-pin.yml');
  const image = parseComposeServiceImage(pin, 'web');
  assert.ok(image);
  assert.match(image, /@sha256:[a-f0-9]{64}$/i);
  assert.equal(extractDigest(image), extractDigest(image).toLowerCase());

  const commentDigest = 'sha256:d9999a020376cc89b86a92410ceb784a7290258f4d1c0c0d968a6a78d62c443a';
  const wrongImage =
    'ghcr.io/adberilgen35/parkio/web@sha256:0000000000000000000000000000000000000000000000000000000000000001';
  const spoofed = [
    `# Correct digest only in a comment: ${commentDigest}`,
    'name: parkio',
    'services:',
    '  web:',
    `    image: ${wrongImage}`,
    '',
  ].join('\n');

  assert.equal(parseComposeServiceImage(spoofed, 'web'), wrongImage);
  assert.notEqual(extractDigest(parseComposeServiceImage(spoofed, 'web')), commentDigest);
  assert.notEqual(parseComposeServiceImage(spoofed, 'web'), parseComposeServiceImage(pin, 'web'));
});

test('negative: comment has live digest but effective image is wrong', () => {
  const livePin = read('docker/docker-compose.web-release-pin.yml');
  const liveImage = parseComposeServiceImage(livePin, 'web');
  const liveDigest = extractDigest(liveImage);
  const wrongImage =
    'ghcr.io/adberilgen35/parkio/web@sha256:1111111111111111111111111111111111111111111111111111111111111111';

  const badPin = [
    `# Live digest mentioned only here: ${liveDigest}`,
    'name: parkio',
    'services:',
    '  web:',
    `    image: ${wrongImage}`,
  ].join('\n');

  // Substring/grep against the file would still "find" the live digest.
  assert.ok(badPin.includes(liveDigest));
  assert.equal(parseComposeServiceImage(badPin, 'web'), wrongImage);
  assert.notEqual(extractDigest(parseComposeServiceImage(badPin, 'web')), liveDigest);

  assert.throws(
    () => assertPinDigestEquals(badPin, liveDigest),
    /effective pin digest/,
  );
  assert.doesNotThrow(() => assertPinDigestEquals(livePin, liveDigest));

  // Practical update path: change only services.web.image; expected digest is derived.
  const ok = assertWebReleasePinContract({
    pinText: livePin,
    composeFilesListText: read('docker/compose.production.files'),
    composeFileContents: productionComposeContents(),
  });
  assert.equal(ok.pinImage, liveImage);
  assert.equal(ok.pinDigest, liveDigest);
  assert.equal(ok.effective.path, 'docker/docker-compose.web-release-pin.yml');
});

test('compose overlay precedence: last web.image wins', () => {
  const earlier = {
    path: 'docker/docker-compose.hosted-beta.yml',
    text: 'services:\n  web:\n    image: parkio/web:local\n',
  };
  const pin = {
    path: 'docker/docker-compose.web-release-pin.yml',
    text: read('docker/docker-compose.web-release-pin.yml'),
  };
  const effective = resolveEffectiveWebImage([earlier, pin]);
  assert.equal(effective.path, pin.path);
  assert.equal(effective.image, parseComposeServiceImage(pin.text, 'web'));
});

test('azure hosted-beta example documents Explore on for live rebuilds', () => {
  const env = readKv('docker/.env.azure-hosted-beta.example');
  assert.equal(env.VITE_API_BASE_URL, 'https://api.parkio.dev/api/v1');
  assert.equal(env.VITE_PUBLIC_EXPLORE_ENABLED, 'true');
  assert.equal(env.VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED, 'false');
});
