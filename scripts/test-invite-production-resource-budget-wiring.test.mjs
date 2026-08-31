import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('GitHub-hosted pre-reviewer job invokes and uploads the canonical guard', () => {
  const workflow = fs.readFileSync(
    path.join(root, '.github/workflows/invite-production-deploy.yml'),
    'utf8',
  );
  const buildStart = workflow.indexOf('  build-images:\n');
  const nextJob = workflow.indexOf('  runner-acceptance:\n');
  assert.notEqual(buildStart, -1);
  assert.notEqual(nextJob, -1);
  const buildJob = workflow.slice(buildStart, nextJob);
  assert.match(buildJob, /runs-on: ubuntu-latest/);
  assert.match(buildJob, /- name: Verify invite-production resource budget/);
  assert.match(buildJob, /\.\/scripts\/assert-invite-production-resource-budget\.sh/);
  assert.match(buildJob, /deploy-artifacts\/invite-production\/resource-budget\.json/);

  const deployStart = workflow.indexOf('  deploy:\n');
  const deployJob = workflow.slice(deployStart);
  assert.match(deployJob, /needs: build-images/);
  assert.match(deployJob, /environment: invite-production/);
});

test('legacy source-only Azure arithmetic cannot return despite the new invite total', () => {
  const sourceTest = fs.readFileSync(
    path.join(root, 'scripts/test-azure-deployment-profile.sh'),
    'utf8',
  );
  const mergedValidator = fs.readFileSync(
    path.join(root, 'scripts/validate-hosted-beta-compose.sh'),
    'utf8',
  );
  assert.doesNotMatch(sourceTest, /END \{ print sum \+ 64 \}/);
  assert.match(mergedValidator, /assert-compose-resource-budget\.mjs/);
  assert.match(mergedValidator, /--profile azure-hosted-beta/);
});

test('invite manifest disabled inventory agrees with the runtime dependency closure', () => {
  const common = fs.readFileSync(path.join(root, 'scripts/lib/deploy-common.sh'), 'utf8');
  const inviteCase = common.slice(
    common.indexOf('    invite-production)'),
    common.indexOf('    *)', common.indexOf('    invite-production)')),
  );
  assert.match(inviteCase, /disabled_common=\(/);
  assert.match(inviteCase, /postgres-ai-validation promtail/);
  assert.match(inviteCase, /PARKIO_DISABLED_SERVICES=\("\$\{disabled_common\[@\]\}" caddy\)/);
  assert.match(inviteCase, /PARKIO_INVITE_EDGE_MODE/);
  assert.doesNotMatch(inviteCase, /PARKIO_DISABLED_SERVICES=\([^\n]*\bloki\b/);
  assert.doesNotMatch(inviteCase, /PARKIO_DISABLED_SERVICES=\([^\n]*\btempo\b/);
});
