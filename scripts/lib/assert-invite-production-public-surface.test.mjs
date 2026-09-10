import assert from 'node:assert/strict';
import test from 'node:test';
import { evaluateInvitePublicSurface } from './assert-invite-production-public-surface.mjs';

function modelWithEnvs(authEnv, gatewayEnv) {
  return {
    services: {
      'auth-service': { environment: authEnv },
      'gateway-service': { environment: gatewayEnv },
    },
  };
}

test('accepts Level-B closed registration and disabled public actuator', () => {
  const evidence = evaluateInvitePublicSurface(
    modelWithEnvs(
      { PARKIO_REGISTRATION_MODE: 'closed' },
      { PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED: 'false' },
    ),
    { profileLabel: 'public-staged' },
  );
  assert.equal(evidence.registrationMode, 'closed');
  assert.equal(evidence.publicActuatorInfoEnabled, 'false');
});

test('rejects missing registration mode', () => {
  assert.throws(
    () => evaluateInvitePublicSurface(
      modelWithEnvs(
        {},
        { PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED: 'false' },
      ),
      { profileLabel: 'public-candidate' },
    ),
    /missing PARKIO_REGISTRATION_MODE/,
  );
});

test('rejects missing public actuator flag', () => {
  assert.throws(
    () => evaluateInvitePublicSurface(
      modelWithEnvs(
        { PARKIO_REGISTRATION_MODE: 'closed' },
        {},
      ),
      { profileLabel: 'dark' },
    ),
    /missing PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED/,
  );
});

test('rejects open registration for Level-B', () => {
  assert.throws(
    () => evaluateInvitePublicSurface(
      modelWithEnvs(
        { PARKIO_REGISTRATION_MODE: 'open' },
        { PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED: 'false' },
      ),
      { profileLabel: 'public-candidate' },
    ),
    /PARKIO_REGISTRATION_MODE='open'/,
  );
});

test('rejects public actuator enabled', () => {
  assert.throws(
    () => evaluateInvitePublicSurface(
      modelWithEnvs(
        { PARKIO_REGISTRATION_MODE: 'closed' },
        { PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED: 'true' },
      ),
      { profileLabel: 'public-staged' },
    ),
    /PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED='true'/,
  );
});
