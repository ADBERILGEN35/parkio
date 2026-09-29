#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const MIB = 1024 * 1024;
const here = path.dirname(fileURLToPath(import.meta.url));
const profilesPath = path.join(here, 'compose-resource-budget-profiles.json');

export const profiles = JSON.parse(fs.readFileSync(profilesPath, 'utf8'));

function fail(message) {
  throw new Error(message);
}

export function memoryToMiB(raw, serviceName = 'service') {
  if (raw == null) fail(`${serviceName} is missing required mem_limit`);

  if (typeof raw === 'number') {
    if (!Number.isSafeInteger(raw) || raw <= 0 || raw % MIB !== 0) {
      fail(`${serviceName} mem_limit byte value must be a positive whole MiB`);
    }
    return raw / MIB;
  }

  if (typeof raw !== 'string') fail(`${serviceName} mem_limit has unsupported type`);
  const value = raw.trim();
  if (/^[1-9][0-9]*$/.test(value)) {
    const bytes = Number(value);
    if (!Number.isSafeInteger(bytes) || bytes % MIB !== 0) {
      fail(`${serviceName} mem_limit byte value must be a positive whole MiB`);
    }
    return bytes / MIB;
  }

  const match = /^([1-9][0-9]*)(m|g)$/i.exec(value);
  if (!match) fail(`${serviceName} mem_limit '${value}' uses an unsupported or ambiguous unit`);
  const amount = Number(match[1]);
  return match[2].toLowerCase() === 'g' ? amount * 1024 : amount;
}

function sortedUnique(values, label) {
  const result = [...new Set(values)].sort();
  if (result.length !== values.length) fail(`${label} contains duplicate services`);
  return result;
}

function sameSet(actual, expected) {
  return JSON.stringify([...actual].sort()) === JSON.stringify([...expected].sort());
}

function dependencyClosure(services, roots) {
  const resolved = new Set();
  const pending = [...roots];
  while (pending.length > 0) {
    const name = pending.pop();
    if (resolved.has(name)) continue;
    const service = services[name];
    if (!service) fail(`runtime target or dependency '${name}' is missing from resolved model`);
    resolved.add(name);
    const dependencies = service.depends_on ?? {};
    const names = Array.isArray(dependencies) ? dependencies : Object.keys(dependencies);
    for (const dependency of names) pending.push(dependency);
  }
  return [...resolved].sort();
}

export function evaluateResourceBudget(model, profileName, runtimeTargets) {
  const contract = profiles[profileName];
  if (!contract) fail(`unknown resource budget profile '${profileName}'`);
  if (!model || typeof model !== 'object' || !model.services || typeof model.services !== 'object') {
    fail('resolved Compose model has no services object');
  }

  const services = model.services;
  const expectedTargets = sortedUnique(contract.runtimeTargets, `${profileName} runtimeTargets`);
  const suppliedTargets = sortedUnique(runtimeTargets, 'supplied runtime services');
  if (!sameSet(suppliedTargets, expectedTargets)) {
    fail(`runtime target inventory drift: expected [${expectedTargets.join(', ')}], got [${suppliedTargets.join(', ')}]`);
  }

  const continuous = sortedUnique(contract.continuousRuntime, `${profileName} continuousRuntime`);
  const oneShot = contract.oneShot.map((entry) => entry.name);
  sortedUnique(oneShot, `${profileName} oneShot`);
  const absentByProfile = sortedUnique(contract.absentByProfile, `${profileName} absentByProfile`);
  const expectedModelServices = sortedUnique(
    [...continuous, ...oneShot, ...absentByProfile],
    `${profileName} classified services`,
  );
  const actualModelServices = Object.keys(services).sort();
  if (!sameSet(actualModelServices, expectedModelServices)) {
    const missing = expectedModelServices.filter((name) => !actualModelServices.includes(name));
    const unexpected = actualModelServices.filter((name) => !expectedModelServices.includes(name));
    fail(`resolved service inventory drift: missing=[${missing.join(', ')}] unexpected=[${unexpected.join(', ')}]`);
  }

  for (const name of contract.omittedFromResolvedModel) {
    if (services[name]) fail(`profile-disabled service '${name}' unexpectedly appears in resolved model`);
  }

  const closure = dependencyClosure(services, suppliedTargets);
  const expectedClosure = [...continuous, ...oneShot].sort();
  if (!sameSet(closure, expectedClosure)) {
    const missing = expectedClosure.filter((name) => !closure.includes(name));
    const unexpected = closure.filter((name) => !expectedClosure.includes(name));
    fail(`runtime dependency closure drift: missing=[${missing.join(', ')}] unexpected=[${unexpected.join(', ')}]`);
  }
  for (const name of absentByProfile) {
    if (closure.includes(name)) fail(`ABSENT_BY_PROFILE service '${name}' is reachable from runtime targets`);
  }

  const oneShotPolicy = new Map(contract.oneShot.map((entry) => [entry.name, entry]));
  const serviceMemoryMiB = new Map();
  for (const name of actualModelServices) {
    const raw = services[name].mem_limit;
    const policy = oneShotPolicy.get(name);
    if (raw == null && policy && !policy.memLimitRequired) {
      serviceMemoryMiB.set(name, 0);
      continue;
    }
    serviceMemoryMiB.set(name, memoryToMiB(raw, name));
  }

  const minimumServiceMemoryMiB = contract.minimumServiceMemoryMiB ?? {};
  if (!minimumServiceMemoryMiB || typeof minimumServiceMemoryMiB !== 'object' || Array.isArray(minimumServiceMemoryMiB)) {
    fail(`${profileName} minimumServiceMemoryMiB must be an object`);
  }
  for (const [name, minimumMiB] of Object.entries(minimumServiceMemoryMiB)) {
    if (!serviceMemoryMiB.has(name)) {
      fail(`minimum memory contract references unclassified service '${name}'`);
    }
    if (!Number.isSafeInteger(minimumMiB) || minimumMiB <= 0) {
      fail(`${name} minimum memory must be a positive whole MiB`);
    }
    const actualMiB = serviceMemoryMiB.get(name);
    if (actualMiB < minimumMiB) {
      fail(`${name} mem_limit must be at least ${minimumMiB} MiB, got ${actualMiB} MiB`);
    }
  }

  const configuredMemoryMiB = actualModelServices.reduce(
    (total, name) => total + serviceMemoryMiB.get(name),
    0,
  );
  const continuousRuntimeMemoryMiB = continuous.reduce(
    (total, name) => total + serviceMemoryMiB.get(name),
    0,
  );
  const clamavMemoryMiB = serviceMemoryMiB.get('clamav');
  const tempoMemoryMiB = serviceMemoryMiB.get('tempo') ?? null;
  const configuredHeadroomMiB = contract.resourceCeilingMiB - configuredMemoryMiB;
  const resourceBudgetWithinCeiling = configuredMemoryMiB <= contract.resourceCeilingMiB;

  if (clamavMemoryMiB !== contract.clamavRequiredMemoryMiB) {
    fail(`clamav mem_limit must be ${contract.clamavRequiredMemoryMiB} MiB, got ${clamavMemoryMiB} MiB`);
  }
  const caddyRequired = contract.caddyRequiredMemoryMiB;
  if (caddyRequired != null) {
    const caddyMemoryMiB = serviceMemoryMiB.get('caddy');
    if (caddyMemoryMiB !== caddyRequired) {
      fail(`caddy mem_limit must be ${caddyRequired} MiB, got ${caddyMemoryMiB} MiB`);
    }
  }
  if (!resourceBudgetWithinCeiling) {
    fail(`configured memory ${configuredMemoryMiB} MiB exceeds ${contract.resourceCeilingMiB} MiB ceiling`);
  }
  if (configuredMemoryMiB !== contract.configuredExpectedMemoryMiB) {
    fail(`configured memory drift: expected ${contract.configuredExpectedMemoryMiB} MiB, got ${configuredMemoryMiB} MiB`);
  }

  return {
    schemaVersion: 1,
    resourceBudgetProfile: profileName,
    configuredMemoryMiB,
    continuousRuntimeMemoryMiB,
    resourceCeilingMiB: contract.resourceCeilingMiB,
    configuredHeadroomMiB,
    resourceBudgetWithinCeiling,
    clamavMemoryMiB,
    tempoMemoryMiB,
    expectedContinuousServiceCount: continuous.length,
    resolvedContinuousServiceCount: closure.filter((name) => !oneShot.includes(name)).length,
    oneShotServiceCount: oneShot.length,
    absentByProfileServiceCount: absentByProfile.length,
  };
}

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    if (!['--profile', '--runtime-services-file', '--evidence'].includes(key)) {
      fail(`unknown argument '${key}'`);
    }
    const value = argv[index + 1];
    if (!value) fail(`${key} requires a value`);
    args[key.slice(2)] = value;
    index += 1;
  }
  if (!args.profile || !args['runtime-services-file']) {
    fail('usage: assert-compose-resource-budget.mjs --profile <name> --runtime-services-file <path> [--evidence <path>]');
  }
  return args;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  let model;
  try {
    model = JSON.parse(input);
  } catch {
    fail('model resolution did not produce valid Compose JSON');
  }
  const runtimeTargets = fs
    .readFileSync(args['runtime-services-file'], 'utf8')
    .split(/\r?\n/)
    .map((value) => value.trim())
    .filter(Boolean);
  const evidence = evaluateResourceBudget(model, args.profile, runtimeTargets);
  if (args.evidence) {
    fs.mkdirSync(path.dirname(args.evidence), { recursive: true });
    fs.writeFileSync(args.evidence, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 });
  }
  for (const [key, value] of Object.entries(evidence)) {
    if (key !== 'schemaVersion' && key !== 'continuousRuntimeMemoryMiB' && key !== 'absentByProfileServiceCount') {
      process.stdout.write(`${key}=${value}\n`);
    }
  }
  process.stdout.write(`continuousRuntimeMemoryMiB=${evidence.continuousRuntimeMemoryMiB}\n`);
  process.stdout.write(`absentByProfileServiceCount=${evidence.absentByProfileServiceCount}\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(`ERROR: resource budget: ${error.message}`);
    process.exit(4);
  });
}
