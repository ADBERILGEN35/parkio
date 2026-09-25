import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function read(rel) {
  return fs.readFileSync(path.join(root, rel), 'utf8');
}

test('invite-public maps fail-closed registration and public actuator (01B-03B)', () => {
  const publicOverlay = read('docker/docker-compose.invite-public.yml');
  assert.match(publicOverlay, /PARKIO_REGISTRATION_MODE:\s*\$\{PARKIO_REGISTRATION_MODE:\?/);
  assert.match(
    publicOverlay,
    /PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED:\s*\$\{PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED:\?/,
  );
});

test('invite-dark maps fail-closed registration and public actuator (01B-03B)', () => {
  const dark = read('docker/docker-compose.invite-dark.yml');
  assert.match(dark, /PARKIO_REGISTRATION_MODE:\s*\$\{PARKIO_REGISTRATION_MODE:\?/);
  assert.match(
    dark,
    /PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED:\s*\$\{PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED:\?/,
  );
});

test('invite-public-staged inherits surface policy (ports only)', () => {
  const staged = read('docker/docker-compose.invite-public-staged.yml');
  assert.doesNotMatch(staged, /^\s+PARKIO_REGISTRATION_MODE:/m);
  assert.doesNotMatch(staged, /^\s+PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED:/m);
  assert.doesNotMatch(staged, /^\s+environment:/m);
});

test('example Level-B template sets closed + public actuator false', () => {
  const example = read('docker/.env.invite-production.example');
  assert.match(example, /^PARKIO_REGISTRATION_MODE=closed$/m);
  assert.match(example, /^PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED=false$/m);
});

test('edge mode CI invokes public-surface assertion', () => {
  const edge = read('scripts/test-invite-production-edge-mode.sh');
  assert.match(edge, /assert-invite-production-public-surface\.sh/);
});

test('deploy workflow build job runs public-surface unit tests', () => {
  const wf = read('.github/workflows/invite-production-deploy.yml');
  assert.match(wf, /assert-invite-production-public-surface\.test\.mjs/);
  assert.match(wf, /test-invite-production-public-surface-wiring\.test\.mjs/);
});
