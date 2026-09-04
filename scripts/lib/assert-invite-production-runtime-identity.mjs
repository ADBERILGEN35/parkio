#!/usr/bin/env node
/**
 * Fail-closed gateway runtime-identity assertions for invite-production
 * resolved Compose models (PROD-DEPLOY-01B-02D).
 *
 * Smoke reads /actuator/info deployment.environment / deployment.gitSha, which
 * are driven by PARKIO_ENVIRONMENT / PARKIO_GIT_SHA on gateway-service.
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

function gatewayEnv(services) {
  const gateway = services['gateway-service'];
  if (!gateway || typeof gateway !== 'object') fail('gateway-service missing from resolved Compose model');
  const env = gateway.environment;
  if (!env || typeof env !== 'object' || Array.isArray(env)) {
    fail('gateway-service environment map missing from resolved Compose model');
  }
  return env;
}

/**
 * @param {object} model resolved `docker compose config --format json`
 * @param {{ expectedEnvironment?: string, expectedGitSha?: string, profileLabel?: string }} opts
 */
export function evaluateInviteRuntimeIdentity(model, opts = {}) {
  const expectedEnvironment = opts.expectedEnvironment ?? 'invite-production';
  const expectedGitSha = opts.expectedGitSha;
  const label = opts.profileLabel ?? 'invite-production';

  if (!expectedGitSha || typeof expectedGitSha !== 'string') {
    fail('expectedGitSha is required');
  }

  const services = normalizeServices(model);
  const env = gatewayEnv(services);

  const environment = env.PARKIO_ENVIRONMENT;
  const gitSha = env.PARKIO_GIT_SHA;

  if (environment === undefined || environment === null || environment === '') {
    fail(`${label}: gateway-service missing PARKIO_ENVIRONMENT`);
  }
  if (gitSha === undefined || gitSha === null || gitSha === '') {
    fail(`${label}: gateway-service missing PARKIO_GIT_SHA`);
  }
  if (environment === 'local') {
    fail(`${label}: gateway-service PARKIO_ENVIRONMENT resolved to 'local'`);
  }
  if (gitSha === 'unknown') {
    fail(`${label}: gateway-service PARKIO_GIT_SHA resolved to 'unknown'`);
  }
  if (environment !== expectedEnvironment) {
    fail(
      `${label}: gateway-service PARKIO_ENVIRONMENT='${environment}', expected '${expectedEnvironment}'`,
    );
  }
  if (gitSha !== expectedGitSha) {
    fail(
      `${label}: gateway-service PARKIO_GIT_SHA='${gitSha}', expected '${expectedGitSha}'`,
    );
  }

  return {
    profileLabel: label,
    environment,
    gitSha,
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
    expectedEnvironment: 'invite-production',
    expectedGitSha: '',
    profileLabel: 'invite-production',
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--expected-environment') {
      opts.expectedEnvironment = argv[++i] ?? '';
    } else if (arg === '--expected-git-sha') {
      opts.expectedGitSha = argv[++i] ?? '';
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
  const evidence = evaluateInviteRuntimeIdentity(model, opts);
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
