import assert from 'node:assert/strict';
import test from 'node:test';
import { evaluateInviteRuntimeIdentity } from './assert-invite-production-runtime-identity.mjs';

const TEST_SHA = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';

function modelWithGatewayEnv(env) {
  return {
    services: {
      'gateway-service': {
        environment: env,
      },
    },
  };
}

test('accepts invite-production identity for public-staged profile', () => {
  const evidence = evaluateInviteRuntimeIdentity(
    modelWithGatewayEnv({
      PARKIO_ENVIRONMENT: 'invite-production',
      PARKIO_GIT_SHA: TEST_SHA,
    }),
    { expectedGitSha: TEST_SHA, profileLabel: 'public-staged' },
  );
  assert.equal(evidence.environment, 'invite-production');
  assert.equal(evidence.gitSha, TEST_SHA);
});

test('reproduces 01B-02C failure: local/unknown defaults are rejected', () => {
  assert.throws(
    () => evaluateInviteRuntimeIdentity(
      modelWithGatewayEnv({
        PARKIO_ENVIRONMENT: 'local',
        PARKIO_GIT_SHA: 'unknown',
      }),
      { expectedGitSha: TEST_SHA, profileLabel: 'public-staged' },
    ),
    /PARKIO_ENVIRONMENT resolved to 'local'/,
  );
});

test('rejects missing PARKIO_GIT_SHA (01B-02C public overlay gap)', () => {
  assert.throws(
    () => evaluateInviteRuntimeIdentity(
      modelWithGatewayEnv({
        PARKIO_ENVIRONMENT: 'invite-production',
      }),
      { expectedGitSha: TEST_SHA, profileLabel: 'public-staged' },
    ),
    /missing PARKIO_GIT_SHA/,
  );
});

test('rejects missing PARKIO_ENVIRONMENT', () => {
  assert.throws(
    () => evaluateInviteRuntimeIdentity(
      modelWithGatewayEnv({
        PARKIO_GIT_SHA: TEST_SHA,
      }),
      { expectedGitSha: TEST_SHA, profileLabel: 'public-candidate' },
    ),
    /missing PARKIO_ENVIRONMENT/,
  );
});

test('rejects SHA mismatch against expected deploy SHA', () => {
  assert.throws(
    () => evaluateInviteRuntimeIdentity(
      modelWithGatewayEnv({
        PARKIO_ENVIRONMENT: 'invite-production',
        PARKIO_GIT_SHA: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
      }),
      { expectedGitSha: TEST_SHA, profileLabel: 'dark' },
    ),
    /PARKIO_GIT_SHA='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'/,
  );
});
