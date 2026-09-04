import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('CI invokes resolved-compose edge resource guard and uploads artifact', () => {
  const workflow = fs.readFileSync(
    path.join(root, '.github/workflows/invite-production-deploy.yml'),
    'utf8',
  );
  const buildStart = workflow.indexOf('  build-images:\n');
  const nextJob = workflow.indexOf('  runner-acceptance:\n');
  const buildJob = workflow.slice(buildStart, nextJob);
  assert.match(buildJob, /assert-invite-production-edge-resource-budget\.sh/);
  assert.match(buildJob, /invite-edge-resource-budget\.json/);
  assert.match(buildJob, /test-invite-production-edge-resource-budget\.sh/);
});

test('public staged overlay is wired in deploy-common', () => {
  const common = fs.readFileSync(path.join(root, 'scripts/lib/deploy-common.sh'), 'utf8');
  assert.match(common, /invite-public-staged\.yml/);
  assert.match(common, /Pre-cutover staging keeps loopback acceptance/);
});

test('release staging carries public edge overlays', () => {
  const stage = fs.readFileSync(path.join(root, 'scripts/stage-invite-production-release.sh'), 'utf8');
  assert.match(stage, /docker-compose\.invite-public\.yml/);
  assert.match(stage, /docker-compose\.invite-public-staged\.yml/);
});
