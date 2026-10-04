import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import { evaluateHardening, expectedTmpfs } from './assert-compose-hardening.mjs';

const helper = path.join(path.dirname(fileURLToPath(import.meta.url)), 'assert-compose-hardening.mjs');

const hardened = (extra = {}) => ({
  security_opt: ['no-new-privileges:true'],
  cap_drop: ['ALL'],
  read_only: true,
  ...extra,
});

// A hardened service with the tmpfs mounts recorded for its name.
const hardenedAs = (name, extra = {}) => ({ [name]: hardened({ tmpfs: expectedTmpfs(name), ...extra }) });

test('a hardened model passes, including recorded capabilities and exceptions', () => {
  const { services, failures } = evaluateHardening({
    services: {
      ...hardenedAs('auth-service'),
      ...hardenedAs('postgres-parking', { cap_add: ['CHOWN', 'DAC_OVERRIDE', 'FOWNER', 'SETGID', 'SETUID'] }),
      ...hardenedAs('caddy', { cap_add: ['NET_BIND_SERVICE'] }),
      ...hardenedAs('promtail', { volumes: [{ type: 'bind', source: '/var/run/docker.sock', target: '/var/run/docker.sock' }] }),
      ...hardenedAs('node-exporter', { pid: 'host' }),
      ...hardenedAs('clamav', { cap_add: ['CHOWN', 'DAC_OVERRIDE', 'FOWNER', 'SETGID', 'SETUID'] }),
    },
  });
  assert.equal(services, 6);
  assert.deepEqual(failures, []);
});

test('missing no-new-privileges or cap_drop ALL fails per service', () => {
  const { failures } = evaluateHardening({
    services: {
      redis: { cap_drop: ['ALL'], read_only: true, tmpfs: expectedTmpfs('redis') },
      kafka: { security_opt: ['no-new-privileges:true'], cap_drop: ['NET_RAW'], read_only: true, tmpfs: expectedTmpfs('kafka') },
      minio: { read_only: true, tmpfs: expectedTmpfs('minio') },
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
      ...hardenedAs('caddy', { cap_add: ['NET_BIND_SERVICE', 'SYS_ADMIN'] }),
      ...hardenedAs('grafana', { cap_add: ['CAP_CHOWN'] }),
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
      ...hardenedAs('grafana', { volumes: ['/var/run/docker.sock:/var/run/docker.sock:ro'] }),
      ...hardenedAs('prometheus', { pid: 'host' }),
      ...hardenedAs('minio', { privileged: true }),
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
      ...hardenedAs('media-service', { read_only: false, volumes: [{ type: 'volume', target: '/tmp' }] }),
      ...hardenedAs('kafka', { read_only: undefined }),
      ...hardenedAs('clamav', { read_only: false, cap_add: ['CHOWN', 'DAC_OVERRIDE', 'FOWNER', 'SETGID', 'SETUID'] }),
      ...hardenedAs('web', { read_only: undefined, cap_add: ['CHOWN', 'NET_BIND_SERVICE', 'SETGID', 'SETUID'] }),
    },
  });
  assert.deepEqual(failures.sort(), [
    'clamav: root filesystem is not read-only',
    'kafka: root filesystem is not read-only',
    'media-service: root filesystem is not read-only',
  ]);
});

test('tmpfs mounts need a size, no exec and the recorded set (B8)', () => {
  const { failures } = evaluateHardening({
    services: {
      ...hardenedAs('redis', { tmpfs: ['/tmp'] }),
      ...hardenedAs('minio', { tmpfs: ['/tmp:size=16m,exec'] }),
      ...hardenedAs('gateway-service', { tmpfs: ['/tmp:size=128m', '/var/cache:size=8m'] }),
      ...hardenedAs('postgres-auth', { tmpfs: '/tmp:size=16m' }),
      ...hardenedAs('caddy', { tmpfs: ['/tmp:size=64m'] }),
      ...hardenedAs('kafka'),
    },
  });
  assert.deepEqual(failures.sort(), [
    'caddy: tmpfs [/tmp:size=64m] differs from the recorded [/tmp:size=8m]',
    'gateway-service: tmpfs [/tmp:size=128m /var/cache:size=8m] differs from the recorded [/tmp:size=128m]',
    'minio: tmpfs /tmp allows exec',
    'minio: tmpfs [/tmp:size=16m,exec] differs from the recorded [/tmp:size=16m]',
    'postgres-auth: tmpfs [/tmp:size=16m] differs from the recorded [/tmp:size=16m /var/run/postgresql:size=1m]',
    'redis: tmpfs /tmp has no size',
    'redis: tmpfs [/tmp] differs from the recorded [/tmp:size=8m]',
  ]);
});

test('a long-form tmpfs volume is refused and counts against the recorded set (B8)', () => {
  const { failures } = evaluateHardening({
    services: {
      ...hardenedAs('redis', { volumes: [{ type: 'tmpfs', target: '/var/cache', tmpfs: { size: 1048576 } }] }),
      ...hardenedAs('minio', { tmpfs: [], volumes: [{ type: 'tmpfs', target: '/tmp' }] }),
    },
  });
  assert.deepEqual(failures.sort(), [
    'minio: tmpfs /tmp has no size',
    'minio: tmpfs /tmp is a long-form volume; declare it under tmpfs',
    'minio: tmpfs [/tmp] differs from the recorded [/tmp:size=16m]',
    'redis: tmpfs /var/cache is a long-form volume; declare it under tmpfs',
    'redis: tmpfs [/tmp:size=8m /var/cache:size=1048576] differs from the recorded [/tmp:size=8m]',
  ]);
});

test('anonymous volumes are limited to the recorded scratch mounts (B8)', () => {
  const scratch = (target) => ({ type: 'volume', target });
  const { failures } = evaluateHardening({
    services: {
      ...hardenedAs('media-service', { volumes: [scratch('/tmp')] }),
      ...hardenedAs('gateway-service', { volumes: [scratch('/var/cache')] }),
      ...hardenedAs('redis', { volumes: [{ type: 'volume', source: 'redis-data', target: '/data' }] }),
    },
  });
  assert.deepEqual(failures, ['gateway-service: anonymous volumes [/var/cache] differ from the recorded []']);

  const missing = evaluateHardening({ services: hardenedAs('media-service') });
  assert.deepEqual(missing.failures, ['media-service: anonymous volumes [] differ from the recorded [/tmp]']);
});

test('the CLI exits non-zero with the failures and passes a hardened model', () => {
  const bad = spawnSync(process.execPath, [helper, '--label', 'fixture'], {
    input: JSON.stringify({ services: { redis: {} } }),
    encoding: 'utf8',
  });
  assert.equal(bad.status, 1);
  assert.match(bad.stderr, /FAIL \[fixture\] redis: security_opt lacks no-new-privileges:true/);
  assert.match(bad.stderr, /compose_hardening_failed\[fixture\]=4/);

  const good = spawnSync(process.execPath, [helper, '--label', 'fixture'], {
    input: JSON.stringify({ services: hardenedAs('redis', { cap_add: ['DAC_OVERRIDE'] }) }),
    encoding: 'utf8',
  });
  assert.equal(good.status, 0, good.stderr);
  assert.match(good.stdout, /compose_hardening\[fixture\]=PASS services=1/);
});
