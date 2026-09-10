#!/usr/bin/env node
/**
 * Fail-closed registration + public-actuator assertions for invite-production
 * resolved Compose models (PROD-DEPLOY-01B-03B).
 *
 * Level-B requires auth-service PARKIO_REGISTRATION_MODE=closed and
 * gateway-service PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED=false explicitly
 * in the resolved container environment (not application defaults alone).
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

function fail(message) {
  throw new Error(message);
}

function normalizeServices(model) {
  if (!model || typeof model !== 'object') fail('resolved Compose model is missing');
  let services = model.services;
  if (!services || typeof services !== 'object') fail('resolved Compose model has no services object');
  if (Array.isArray(services)) {
    services = Object.fromEntries(services.map((entry) => [entry.name, entry]));
  }
  return services;
}

function serviceEnv(services, serviceName) {
  const service = services[serviceName];
  if (!service || typeof service !== 'object') {
    fail(`${serviceName} missing from resolved Compose model`);
  }
  const env = service.environment;
  if (!env || typeof env !== 'object' || Array.isArray(env)) {
    fail(`${serviceName} environment map missing from resolved Compose model`);
  }
  return env;
}

/**
 * @param {object} model resolved `docker compose config --format json`
 * @param {{ profileLabel?: string, expectedRegistrationMode?: string, expectedPublicActuatorInfo?: string }} opts
 */
export function evaluateInvitePublicSurface(model, opts = {}) {
  const label = opts.profileLabel ?? 'invite-production';
  const expectedRegistrationMode = opts.expectedRegistrationMode ?? 'closed';
  const expectedPublicActuatorInfo = opts.expectedPublicActuatorInfo ?? 'false';

  const services = normalizeServices(model);
  const authEnv = serviceEnv(services, 'auth-service');
  const gatewayEnv = serviceEnv(services, 'gateway-service');

  const registrationMode = authEnv.PARKIO_REGISTRATION_MODE;
  const publicActuator = gatewayEnv.PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED;

  if (registrationMode === undefined || registrationMode === null || registrationMode === '') {
    fail(`${label}: auth-service missing PARKIO_REGISTRATION_MODE`);
  }
  if (publicActuator === undefined || publicActuator === null || publicActuator === '') {
    fail(`${label}: gateway-service missing PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED`);
  }
  if (registrationMode !== expectedRegistrationMode) {
    fail(
      `${label}: auth-service PARKIO_REGISTRATION_MODE='${registrationMode}', expected '${expectedRegistrationMode}'`,
    );
  }
  if (String(publicActuator) !== expectedPublicActuatorInfo) {
    fail(
      `${label}: gateway-service PARKIO_GATEWAY_PUBLIC_ACTUATOR_INFO_ENABLED='${publicActuator}', expected '${expectedPublicActuatorInfo}'`,
    );
  }

  return {
    profileLabel: label,
    registrationMode,
    publicActuatorInfoEnabled: String(publicActuator),
  };
}

function readStdin() {
  return new Promise((resolve, reject) => {
    const chunks = [];
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => chunks.push(chunk));
    process.stdin.on('end', () => resolve(chunks.join('')));
    process.stdin.on('error', reject);
  });
}

function parseArgs(argv) {
  const opts = {
    expectedRegistrationMode: 'closed',
    expectedPublicActuatorInfo: 'false',
    profileLabel: 'invite-production',
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--expected-registration-mode') {
      opts.expectedRegistrationMode = argv[++i] ?? '';
    } else if (arg === '--expected-public-actuator-info') {
      opts.expectedPublicActuatorInfo = argv[++i] ?? '';
    } else if (arg === '--profile-label') {
      opts.profileLabel = argv[++i] ?? '';
    } else {
      fail(`unknown argument '${arg}'`);
    }
  }
  return opts;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const raw = await readStdin();
  let model;
  try {
    model = JSON.parse(raw);
  } catch (err) {
    fail(`invalid Compose JSON on stdin: ${err.message}`);
  }
  const evidence = evaluateInvitePublicSurface(model, opts);
  process.stdout.write(`${JSON.stringify(evidence)}\n`);
}

const isMain = process.argv[1]
  && path.resolve(fileURLToPath(import.meta.url)) === path.resolve(process.argv[1]);

if (isMain) {
  main().catch((err) => {
    console.error(err instanceof Error ? err.message : String(err));
    process.exit(1);
  });
}
