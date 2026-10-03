#!/usr/bin/env node
// CL-F29.3 container hardening guard. Reads a rendered Compose model
// (`docker compose config --format json`) and requires every service to run with
// no-new-privileges and cap_drop ALL, to add back only the capabilities recorded for it, and
// to mount the Docker socket or share the host PID namespace only where the inventory
// (docs/operations/container-hardening-inventory.md) documents an exception, and to run with a
// read-only root filesystem unless the inventory records why not (B8). Privileged containers
// are refused outright.
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
export const WRITABLE_ROOT_EXCEPTIONS = new Set(['clamav', 'web']);

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
