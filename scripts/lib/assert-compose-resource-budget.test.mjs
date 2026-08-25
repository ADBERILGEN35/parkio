import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

import {
  evaluateResourceBudget,
  memoryToMiB,
  profiles,
} from './assert-compose-resource-budget.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const helper = path.join(here, 'assert-compose-resource-budget.mjs');

const inviteMemory = {
  'ai-validation-service': '640m',
  alertmanager: '128m',
  'analytics-service': '640m',
  'auth-service': '768m',
  'blackbox-exporter': '64m',
  caddy: '256m',
  clamav: '3g',
  'gamification-service': '640m',
  'gateway-service': '640m',
  grafana: '384m',
  kafka: '1280m',
  'kafka-exporter': '128m',
  loki: '512m',
  'media-service': '768m',
  minio: '512m',
  'minio-setup': null,
  'moderation-service': '640m',
  'node-exporter': '64m',
  'notification-service': '640m',
  'parking-service': '768m',
  prometheus: '1g',
  promtail: '128m',
  redis: '384m',
  tempo: '512m',
  'user-service': '640m',
  web: '128m',
};

const azureMemory = {
  ...inviteMemory,
  web: '64m',
  caddy: '96m',
  kafka: '1g',
  prometheus: '576m',
  grafana: '224m',
  'minio-setup': '64m',
  'postgres-auth': '320m',
  'postgres-gateway': '256m',
  'postgres-user': '256m',
  'postgres-parking': '384m',
  'postgres-media': '256m',
  'postgres-gamification': '256m',
  'postgres-notification': '256m',
  'postgres-moderation': '256m',
  'postgres-analytics': '320m',
  'postgres-ai-validation': '256m',
};
for (const name of ['alertmanager', 'loki', 'promtail', 'tempo']) delete azureMemory[name];

function modelFromMemory(memory) {
  const services = {};
  for (const [name, memLimit] of Object.entries(memory)) {
    services[name] = {};
    if (memLimit != null) services[name].mem_limit = memLimit;
  }
  services.grafana.depends_on = { prometheus: {} };
  if (services.loki) services.grafana.depends_on.loki = {};
  if (services.tempo) services.grafana.depends_on.tempo = {};
  services['media-service'].depends_on = {
    minio: {},
    'minio-setup': {},
    clamav: {},
  };
  services['minio-setup'].depends_on = { minio: {} };
  services.caddy && (services.caddy.depends_on = { 'gateway-service': {}, web: {} });
  services.promtail && (services.promtail.depends_on = { loki: {} });
  return { name: 'parkio', services };
}

function inviteModel() {
  return modelFromMemory(inviteMemory);
}

function runCli(model, profile = 'invite-production') {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'parkio-resource-budget-'));
  const runtimeFile = path.join(directory, 'runtime-services.txt');
  fs.writeFileSync(runtimeFile, `${profiles[profile].runtimeTargets.join('\r\n')}\r\n`);
  try {
    return spawnSync(
      process.execPath,
      [helper, '--profile', profile, '--runtime-services-file', runtimeFile],
      { input: typeof model === 'string' ? model : JSON.stringify(model), encoding: 'utf8' },
    );
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}

test('normalizes supported units to MiB and tolerates CRLF whitespace', () => {
  assert.equal(memoryToMiB('512m'), 512);
  assert.equal(memoryToMiB('1536m'), 1536);
  assert.equal(memoryToMiB('3072m\r'), 3072);
  assert.equal(memoryToMiB('1g'), 1024);
  assert.equal(memoryToMiB('3g'), 3072);
  assert.equal(memoryToMiB(String(3 * 1024 * 1024 * 1024)), 3072);
  assert.throws(() => memoryToMiB('1gb'), /unsupported or ambiguous unit/);
  assert.throws(() => memoryToMiB('1.5g'), /unsupported or ambiguous unit/);
});

test('reconciles the invite merged model and continuous runtime closure', () => {
  const evidence = evaluateResourceBudget(
    inviteModel(),
    'invite-production',
    profiles['invite-production'].runtimeTargets,
  );
  assert.equal(evidence.configuredMemoryMiB, 15360);
  assert.equal(evidence.continuousRuntimeMemoryMiB, 14976);
  assert.equal(evidence.configuredHeadroomMiB, 1024);
  assert.equal(evidence.clamavMemoryMiB, 3072);
  assert.equal(evidence.expectedContinuousServiceCount, 23);
  assert.equal(evidence.resolvedContinuousServiceCount, 23);
  assert.equal(evidence.oneShotServiceCount, 1);
  assert.equal(evidence.absentByProfileServiceCount, 2);
});

test('keeps the hosted-beta 15872 MiB contract in its own merged-model profile', () => {
  const evidence = evaluateResourceBudget(
    modelFromMemory(azureMemory),
    'azure-hosted-beta',
    profiles['azure-hosted-beta'].runtimeTargets,
  );
  assert.equal(evidence.configuredMemoryMiB, 15872);
  assert.equal(evidence.resourceCeilingMiB, 16384);
  assert.equal(evidence.oneShotServiceCount, 1);
});

test('fails closed when configured memory exceeds the ceiling', () => {
  const model = inviteModel();
  model.services.caddy.mem_limit = '2g';
  const result = runCli(model);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /exceeds 16384 MiB ceiling/);
});

test('fails closed when ClamAV regresses to 1536 MiB', () => {
  const model = inviteModel();
  model.services.clamav.mem_limit = '1536m';
  const result = runCli(model);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /clamav mem_limit must be 3072 MiB/);
});

test('fails closed when an expected continuous service disappears', () => {
  const model = inviteModel();
  delete model.services['auth-service'];
  const result = runCli(model);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /resolved service inventory drift: missing=\[auth-service\]/);
});

test('fails closed when an unexpected resource-bearing service appears', () => {
  const model = inviteModel();
  model.services.unclassified = { mem_limit: '512m' };
  const result = runCli(model);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /unexpected=\[unclassified\]/);
});

test('fails closed when a required mem_limit disappears', () => {
  const model = inviteModel();
  delete model.services.redis.mem_limit;
  const result = runCli(model);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /redis is missing required mem_limit/);
});

test('fails closed on unsupported units and invalid resolved JSON', () => {
  const model = inviteModel();
  model.services.redis.mem_limit = '384mb';
  const unitResult = runCli(model);
  assert.notEqual(unitResult.status, 0);
  assert.match(unitResult.stderr, /unsupported or ambiguous unit/);

  const invalidResult = runCli('not-json');
  assert.notEqual(invalidResult.status, 0);
  assert.match(invalidResult.stderr, /model resolution did not produce valid Compose JSON/);
});
