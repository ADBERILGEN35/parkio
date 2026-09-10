import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function read(rel) {
  return fs.readFileSync(path.join(root, rel), 'utf8');
}

test('invite-public maps gateway identity env (01B-02D)', () => {
  const publicOverlay = read('docker/docker-compose.invite-public.yml');
  assert.match(publicOverlay, /PARKIO_ENVIRONMENT:\s*\$\{PARKIO_ENVIRONMENT:\?/);
  assert.match(publicOverlay, /PARKIO_GIT_SHA:\s*\$\{PARKIO_GIT_SHA:\?/);
});

test('invite-public-staged inherits identity from invite-public (ports only)', () => {
  const staged = read('docker/docker-compose.invite-public-staged.yml');
  assert.match(staged, /ports:\s*!override/);
  // Comment may mention the env names; the service must not redeclare them.
  assert.doesNotMatch(staged, /^\s+PARKIO_ENVIRONMENT:/m);
  assert.doesNotMatch(staged, /^\s+PARKIO_GIT_SHA:/m);
  assert.doesNotMatch(staged, /^\s+environment:/m);
});

test('invite-dark identity wiring remains soft-defaulted', () => {
  const dark = read('docker/docker-compose.invite-dark.yml');
  assert.match(dark, /PARKIO_ENVIRONMENT:\s*\$\{PARKIO_ENVIRONMENT:-local\}/);
  assert.match(dark, /PARKIO_GIT_SHA:\s*\$\{PARKIO_GIT_SHA:-unknown\}/);
});

test('edge mode CI invokes runtime-identity assertion', () => {
  const edge = read('scripts/test-invite-production-edge-mode.sh');
  assert.match(edge, /assert-invite-production-runtime-identity\.sh/);
});

test('deploy workflow build job runs runtime-identity unit tests', () => {
  const wf = read('.github/workflows/invite-production-deploy.yml');
  assert.match(wf, /assert-invite-production-runtime-identity\.test\.mjs/);
  assert.match(wf, /test-invite-production-runtime-identity-wiring\.test\.mjs/);
  assert.match(wf, /test-invite-production-edge-mode\.sh/);
});
