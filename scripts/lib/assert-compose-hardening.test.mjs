import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { evaluateHardening } from './assert-compose-hardening.mjs';

const helper = path.join(path.dirname(fileURLToPath(import.meta.url)), 'assert-compose-hardening.mjs');

const hardened = (extra = {}) => ({
  security_opt: ['no-new-privileges:true'],
  cap_drop: ['ALL'],
  read_only: true,
  ...extra,
});

test('a hardened model passes, including recorded capabilities and exceptions', () => {
  const { services, failures } = evaluateHardening({
    services: {
      'auth-service': hardened(),
      'postgres-parking': hardened({ cap_add: ['CHOWN', 'DAC_OVERRIDE', 'FOWNER', 'SETGID', 'SETUID'] }),
      caddy: hardened({ cap_add: ['NET_BIND_SERVICE'] }),
      promtail: hardened({ volumes: [{ type: 'bind', source: '/var/run/docker.sock', target: '/var/run/docker.sock' }] }),
      'node-exporter': hardened({ pid: 'host' }),
    },
  });
  assert.equal(services, 5);
  assert.deepEqual(failures, []);
});

test('missing no-new-privileges or cap_drop ALL fails per service', () => {
  const { failures } = evaluateHardening({
    services: {
      redis: { cap_drop: ['ALL'], read_only: true },
      kafka: { security_opt: ['no-new-privileges:true'], cap_drop: ['NET_RAW'], read_only: true },
      minio: { read_only: true },
    },
  });
  assert.deepEqual(failures.sort(), [
    'kafka: cap_drop must include ALL',
    'minio: cap_drop must include ALL',
    'minio: security_opt lacks no-new-privileges:true',
    'redis: security_opt lacks no-new-privileges:true',
  ]);
});

test('capabilities that are not recorded for the service fail', () => {
  const { failures } = evaluateHardening({
    services: {
      caddy: hardened({ cap_add: ['NET_BIND_SERVICE', 'SYS_ADMIN'] }),
      grafana: hardened({ cap_add: ['CAP_CHOWN'] }),
    },
  });
  assert.deepEqual(failures.sort(), [
    'caddy: cap_add SYS_ADMIN is not recorded for this service',
    'grafana: cap_add CHOWN is not recorded for this service',
  ]);
});

test('Docker socket, host PID and privileged are refused outside the documented exceptions', () => {
  const { failures } = evaluateHardening({
    services: {
      grafana: hardened({ volumes: ['/var/run/docker.sock:/var/run/docker.sock:ro'] }),
      prometheus: hardened({ pid: 'host' }),
      minio: hardened({ privileged: true }),
    },
  });
  assert.deepEqual(failures.sort(), [
    'grafana: mounts the Docker socket',
    'minio: privileged containers are not allowed',
    'prometheus: shares the host PID namespace',
  ]);
});

test('a writable root filesystem fails outside the recorded exception (B8)', () => {
  const { failures } = evaluateHardening({
    services: {
      'media-service': hardened({ read_only: false }),
      kafka: hardened({ read_only: undefined }),
      clamav: hardened({ read_only: false, cap_add: ['CHOWN', 'DAC_OVERRIDE', 'FOWNER', 'SETGID', 'SETUID'] }),
    },
  });
  assert.deepEqual(failures.sort(), [
    'kafka: root filesystem is not read-only',
    'media-service: root filesystem is not read-only',
  ]);
});

test('the CLI exits non-zero with the failures and passes a hardened model', () => {
  const bad = spawnSync(process.execPath, [helper, '--label', 'fixture'], {
    input: JSON.stringify({ services: { redis: {} } }),
    encoding: 'utf8',
  });
  assert.equal(bad.status, 1);
  assert.match(bad.stderr, /FAIL \[fixture\] redis: security_opt lacks no-new-privileges:true/);
  assert.match(bad.stderr, /compose_hardening_failed\[fixture\]=3/);

  const good = spawnSync(process.execPath, [helper, '--label', 'fixture'], {
    input: JSON.stringify({ services: { redis: hardened({ cap_add: ['DAC_OVERRIDE'] }) } }),
    encoding: 'utf8',
  });
  assert.equal(good.status, 0, good.stderr);
  assert.match(good.stdout, /compose_hardening\[fixture\]=PASS services=1/);
});
