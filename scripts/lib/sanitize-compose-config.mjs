#!/usr/bin/env node

/**
 * Reduce a resolved Docker Compose JSON model to deployment evidence that can
 * never contain environment/build-argument values or healthcheck commands.
 *
 * The resolved model is read from stdin and the sanitized structure is written
 * to stdout. Callers must pipe directly into this process; never persist the
 * resolved input model when production secrets are loaded.
 */

let input = '';
for await (const chunk of process.stdin) input += chunk;

let compose;
try {
  compose = JSON.parse(input);
} catch {
  console.error('sanitize-compose-config: invalid Compose JSON on stdin');
  process.exit(2);
}

const cleanString = (value) => (typeof value === 'string' ? value : null);
const sortedKeys = (value) =>
  value && typeof value === 'object' && !Array.isArray(value)
    ? Object.keys(value).sort()
    : [];

const services = Object.entries(compose.services ?? {})
  .sort(([left], [right]) => left.localeCompare(right))
  .map(([name, service]) => {
    const ports = Array.isArray(service.ports)
      ? service.ports.map((port) => ({
          hostIp: cleanString(port.host_ip),
          published: port.published == null ? null : String(port.published),
          target: port.target == null ? null : String(port.target),
          protocol: cleanString(port.protocol) ?? 'tcp',
        }))
      : [];

    return {
      name,
      image: cleanString(service.image),
      profiles: Array.isArray(service.profiles) ? [...service.profiles].sort() : [],
      dependencies: sortedKeys(service.depends_on),
      environmentNames: sortedKeys(service.environment),
      buildArgumentNames: sortedKeys(service.build?.args),
      ports,
      healthcheck: service.healthcheck
        ? {
            configured: true,
            interval: cleanString(service.healthcheck.interval),
            timeout: cleanString(service.healthcheck.timeout),
            startPeriod: cleanString(service.healthcheck.start_period),
            retries:
              Number.isInteger(service.healthcheck.retries)
                ? service.healthcheck.retries
                : null,
          }
        : { configured: false },
    };
  });

process.stdout.write(
  `${JSON.stringify({ schemaVersion: 1, projectName: cleanString(compose.name), services }, null, 2)}\n`,
);
