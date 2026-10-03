#!/usr/bin/env node
// CL-F29.3 container hardening guard. Reads a rendered Compose model
// (`docker compose config --format json`) and requires every service to run with
// no-new-privileges and cap_drop ALL, to add back only the capabilities recorded for it, and
// to mount the Docker socket or share the host PID namespace only where the inventory
// (docs/operations/container-hardening-inventory.md) documents an exception, and to run with a
// read-only root filesystem unless the inventory records why not (B8). Its tmpfs mounts must be
// exactly the recorded ones, each with a size and without exec. Privileged containers are
// refused outright.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// Root entrypoints that prepare their data directory and then drop to the service user.
const POSTGRES_CAPS = ['CHOWN', 'DAC_OVERRIDE', 'FOWNER', 'SETGID', 'SETUID'];

/** Capabilities a service may add back after cap_drop ALL, with the reason in the inventory. */
export const ALLOWED_CAP_ADD = {
  postgres: POSTGRES_CAPS,
  redis: ['DAC_OVERRIDE'],
  clamav: ['CHOWN', 'DAC_OVERRIDE', 'FOWNER', 'SETGID', 'SETUID'],
  web: ['CHOWN', 'NET_BIND_SERVICE', 'SETGID', 'SETUID'],
  caddy: ['NET_BIND_SERVICE'],
};

/** Services allowed to mount the Docker socket; see the inventory for why. */
export const DOCKER_SOCKET_EXCEPTIONS = new Set(['promtail']);

/** Services allowed to share the host PID namespace; see the inventory for why. */
export const HOST_PID_EXCEPTIONS = new Set(['node-exporter']);

/**
 * Services allowed a writable root filesystem; see the inventory for why (B8). web is temporary:
 * its read-only root needs a tmpfs over /etc/nginx/conf.d, which only works with the image that
 * renders that directory at start (B9, #198).
 */
export const WRITABLE_ROOT_EXCEPTIONS = new Set(['web']);

// tmpfs mounts of the read-only roots (B8), exactly as the Compose files declare them. Each has a
// size, because tmpfs pages count against the container's memory limit, and keeps Docker's
// noexec default. The inventory gives the reason and the measured use of each mount.
const JVM_TMPFS = ['/tmp:size=128m'];
const POSTGRES_TMPFS = ['/tmp:size=16m', '/var/run/postgresql:size=1m'];

/** Recorded tmpfs mounts by service; *-service and postgres-* use the shared lists above. */
export const TMPFS_MOUNTS = {
  'media-service': [],
  redis: ['/tmp:size=8m'],
  kafka: ['/etc/kafka:size=8m,mode=1777', '/tmp:size=16m', '/var/log/kafka:size=32m,mode=1777'],
  minio: ['/tmp:size=16m'],
  'minio-setup': ['/tmp:size=8m'],
  alertmanager: ['/tmp:size=8m'],
  loki: ['/tmp:size=16m'],
  tempo: ['/tmp:size=16m'],
  grafana: ['/tmp:size=32m'],
  caddy: ['/tmp:size=8m'],
  clamav: ['/run/clamav:size=1m', '/run/lock:size=1m', '/tmp:size=128m', '/var/log/clamav:size=16m'],
};

/**
 * Anonymous volumes a service may mount (B8): disk-backed scratch space where a tmpfs would charge
 * the memory limit. Compose keeps them across container recreation; the inventory describes their
 * lifecycle.
 */
export const SCRATCH_VOLUMES = { 'media-service': ['/tmp'] };

export function expectedTmpfs(service) {
  if (Object.hasOwn(TMPFS_MOUNTS, service)) return TMPFS_MOUNTS[service];
  if (service.startsWith('postgres-')) return POSTGRES_TMPFS;
  if (service.endsWith('-service')) return JVM_TMPFS;
  return [];
}

function tmpfsFailures(name, service) {
  const failures = [];
  const raw = service.tmpfs ?? [];
  const entries = (Array.isArray(raw) ? raw : [raw]).map(String);
  for (const entry of entries) {
    const separator = entry.indexOf(':');
    const target = separator < 0 ? entry : entry.slice(0, separator);
    const options = separator < 0 ? [] : entry.slice(separator + 1).split(',');
    if (!options.some((option) => /^size=\d+[kmg]?$/i.test(option))) {
      failures.push(`${name}: tmpfs ${target} has no size`);
    }
    if (options.includes('exec')) {
      failures.push(`${name}: tmpfs ${target} allows exec`);
    }
  }
  const actual = [...entries].sort();
  const expected = [...expectedTmpfs(name)].sort();
  if (actual.join(' ') !== expected.join(' ')) {
    failures.push(`${name}: tmpfs [${actual.join(' ')}] differs from the recorded [${expected.join(' ')}]`);
  }
  return failures;
}

function scratchVolumeFailures(name, service) {
  const anonymous = (service.volumes ?? [])
    .filter((volume) => typeof volume === 'object' && volume?.type === 'volume' && !volume.source)
    .map((volume) => volume.target)
    .sort();
  const expected = [...(SCRATCH_VOLUMES[name] ?? [])].sort();
  return anonymous.join(' ') === expected.join(' ')
    ? []
    : [`${name}: anonymous volumes [${anonymous.join(' ')}] differ from the recorded [${expected.join(' ')}]`];
}

function capabilityProfile(service) {
  return service.startsWith('postgres-') ? 'postgres' : service;
}

function upper(values) {
  return (values ?? []).map((value) => String(value).toUpperCase().replace(/^CAP_/, ''));
}

function mountsDockerSocket(volumes) {
  return (volumes ?? []).some((volume) => {
    const source = typeof volume === 'string' ? volume.split(':')[0] : volume?.source ?? '';
    return /(^|\/)docker\.sock$/.test(source);
  });
}

/** Returns the services checked and every rule a service breaks. */
export function evaluateHardening(config) {
  const failures = [];
  const services = Object.entries(config?.services ?? {});
  for (const [name, service] of services) {
    const securityOpt = (service.security_opt ?? []).map(String);
    if (!securityOpt.some((option) => /^no-new-privileges(?::true|=true)?$/.test(option))) {
      failures.push(`${name}: security_opt lacks no-new-privileges:true`);
    }
    if (!upper(service.cap_drop).includes('ALL')) {
      failures.push(`${name}: cap_drop must include ALL`);
    }
    const allowed = new Set(ALLOWED_CAP_ADD[capabilityProfile(name)] ?? []);
    for (const capability of upper(service.cap_add)) {
      if (!allowed.has(capability)) {
        failures.push(`${name}: cap_add ${capability} is not recorded for this service`);
      }
    }
    if (service.privileged === true) {
      failures.push(`${name}: privileged containers are not allowed`);
    }
    if (mountsDockerSocket(service.volumes) && !DOCKER_SOCKET_EXCEPTIONS.has(name)) {
      failures.push(`${name}: mounts the Docker socket`);
    }
    if (service.pid === 'host' && !HOST_PID_EXCEPTIONS.has(name)) {
      failures.push(`${name}: shares the host PID namespace`);
    }
    if (service.read_only !== true && !WRITABLE_ROOT_EXCEPTIONS.has(name)) {
      failures.push(`${name}: root filesystem is not read-only`);
    }
    failures.push(...tmpfsFailures(name, service));
    failures.push(...scratchVolumeFailures(name, service));
  }
  return { services: services.length, failures };
}

function main(argv) {
  const labelIndex = argv.indexOf('--label');
  const label = labelIndex >= 0 ? argv[labelIndex + 1] : 'compose';
  const file = argv.find((arg, index) => !arg.startsWith('--') && argv[index - 1] !== '--label');
  const config = JSON.parse(readFileSync(file ?? 0, 'utf8'));
  const { services, failures } = evaluateHardening(config);
  if (failures.length > 0) {
    failures.forEach((failure) => process.stderr.write(`FAIL [${label}] ${failure}\n`));
    process.stderr.write(`compose_hardening_failed[${label}]=${failures.length}\n`);
    process.exit(1);
  }
  process.stdout.write(`compose_hardening[${label}]=PASS services=${services}\n`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main(process.argv.slice(2));
}
