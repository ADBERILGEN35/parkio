import assert from 'node:assert/strict';
import test from 'node:test';
import { evaluateInviteComposePorts } from './assert-invite-production-compose-ports.mjs';

function modelWithPorts(servicePorts) {
  const services = {};
  for (const [name, ports] of Object.entries(servicePorts)) {
    services[name] = { ports };
  }
  return { services };
}

test('dark mode keeps gateway on loopback only', () => {
  const evidence = evaluateInviteComposePorts(
    modelWithPorts({
      'gateway-service': [{ host_ip: '127.0.0.1', published: '8080', target: 8080 }],
    }),
    { mode: 'dark', composeFiles: 'invite-dark' },
  );
  assert.equal(evidence.gatewayHostBinding, '127.0.0.1:8080');
});

test('public candidate rejects gateway host publish', () => {
  assert.throws(
    () => evaluateInviteComposePorts(
      modelWithPorts({
        caddy: [{ host_ip: '', published: '443', target: 443 }],
        'gateway-service': [{ host_ip: '127.0.0.1', published: '8080', target: 8080 }],
      }),
      { mode: 'public-candidate', composeFiles: 'invite-public' },
    ),
    /gateway-service must not publish host ports/,
  );
});

test('public staged requires staged overlay when ACME is false', () => {
  assert.throws(
    () => evaluateInviteComposePorts(
      modelWithPorts({
        caddy: [],
        'gateway-service': [],
      }),
      { mode: 'public-staged', composeFiles: 'invite-public', acmeAuthorized: false },
    ),
    /invite-public-staged overlay/,
  );
});
