#!/usr/bin/env node
/**
 * Fail-closed host-port assertions for invite-production resolved Compose models.
 * Used by PROD-DEPLOY-01B edge resource certification.
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

function fail(message) {
  throw new Error(message);
}

const INTERNAL_SERVICES = new Set([
  'redis',
  'kafka',
  'minio',
  'minio-setup',
  'clamav',
  'prometheus',
  'grafana',
  'alertmanager',
  'loki',
  'promtail',
  'tempo',
  'node-exporter',
  'kafka-exporter',
  'blackbox-exporter',
  'auth-service',
  'user-service',
  'parking-service',
  'media-service',
  'gamification-service',
  'notification-service',
  'moderation-service',
  'ai-validation-service',
  'analytics-service',
  'gateway-service',
  'web',
  'postgres-auth',
  'postgres-gateway',
  'postgres-user',
  'postgres-parking',
  'postgres-media',
  'postgres-gamification',
  'postgres-notification',
  'postgres-moderation',
  'postgres-analytics',
  'postgres-ai-validation',
]);

function normalizeServices(model) {
  if (!model || typeof model !== 'object') fail('resolved Compose model is missing');
  let services = model.services;
  if (!services || typeof services !== 'object') fail('resolved Compose model has no services object');
  if (Array.isArray(services)) {
    services = Object.fromEntries(services.map((entry) => [entry.name, entry]));
  }
  return services;
}

function publishedPorts(services, name) {
  const ports = services[name]?.ports;
  if (!Array.isArray(ports)) return [];
  return ports
    .filter((entry) => entry && typeof entry === 'object')
    .map((entry) => ({
      hostIp: entry.host_ip ?? entry.hostIp ?? '',
      published: String(entry.published ?? ''),
      target: String(entry.target ?? ''),
      mode: entry.mode ?? 'ingress',
    }));
}

function assertNoHostPublish(services, name, label) {
  const ports = publishedPorts(services, name);
  if (ports.length > 0) {
    fail(`${label} must not publish host ports, got ${JSON.stringify(ports)}`);
  }
}

function assertGatewayLoopbackOnly(services) {
  const ports = publishedPorts(services, 'gateway-service');
  const expected = [{ hostIp: '127.0.0.1', published: '8080', target: '8080', mode: 'ingress' }];
  if (JSON.stringify(ports) !== JSON.stringify(expected)) {
    fail(
      `gateway-service published ports are ${JSON.stringify(ports)}, expected loopback 127.0.0.1:8080 only`,
    );
  }
}

function assertGatewayNoHostPublish(services) {
  assertNoHostPublish(services, 'gateway-service', 'gateway-service');
}

function assertInternalServicesNotPublished(services) {
  for (const name of INTERNAL_SERVICES) {
    if (!services[name]) continue;
    const ports = publishedPorts(services, name);
    for (const port of ports) {
      if (port.hostIp && port.hostIp !== '127.0.0.1') {
        fail(`internal service ${name} publishes ${port.published} on ${port.hostIp || '0.0.0.0'}`);
      }
      if (!port.hostIp && port.published) {
        fail(`internal service ${name} publishes ${port.published} on wildcard host`);
      }
    }
  }
}

function assertCaddyDefined(services) {
  if (!services.caddy) fail('public candidate must define caddy service');
}

function assertInviteDarkAbsent(composeFiles) {
  if (composeFiles.includes('invite-dark')) {
    fail('public candidate must not include invite-dark overlay');
  }
}

function assertInvitePublicPresent(composeFiles) {
  if (!composeFiles.includes('invite-public')) {
    fail('public candidate must include invite-public overlay');
  }
}

export function evaluateInviteComposePorts(model, options) {
  const {
    mode,
    composeFiles = '',
    acmeAuthorized = false,
  } = options;
  const services = normalizeServices(model);

  switch (mode) {
    case 'dark':
      assertGatewayLoopbackOnly(services);
      assertInternalServicesNotPublished(services);
      assertNoHostPublish(services, 'caddy', 'caddy');
      assertNoHostPublish(services, 'web', 'web');
      assertNoHostPublish(services, 'minio', 'minio');
      break;
    case 'public-candidate':
      assertInvitePublicPresent(composeFiles);
      assertInviteDarkAbsent(composeFiles);
      assertCaddyDefined(services);
      assertGatewayNoHostPublish(services);
      assertNoHostPublish(services, 'web', 'web');
      assertNoHostPublish(services, 'minio', 'minio');
      assertInternalServicesNotPublished(services);
      break;
    case 'public-staged':
      assertInvitePublicPresent(composeFiles);
      assertInviteDarkAbsent(composeFiles);
      if (composeFiles.includes('invite-public-staged')) {
        assertGatewayLoopbackOnly(services);
      } else if (!acmeAuthorized) {
        fail('public staged model must include invite-public-staged overlay when ACME is not authorized');
      }
      assertCaddyDefined(services);
      assertNoHostPublish(services, 'web', 'web');
      assertNoHostPublish(services, 'minio', 'minio');
      assertInternalServicesNotPublished(services);
      break;
    default:
      fail(`unknown port assertion mode '${mode}'`);
  }

  return {
    mode,
    gatewayHostBinding:
      mode === 'public-candidate'
        ? 'none'
        : '127.0.0.1:8080',
    caddyDefined: Boolean(services.caddy),
    inviteDarkAbsent: !composeFiles.includes('invite-dark'),
    invitePublicPresent: composeFiles.includes('invite-public'),
  };
}

async function main() {
  const args = process.argv.slice(2);
  let mode = '';
  let composeFiles = '';
  let acmeAuthorized = false;
  for (let index = 0; index < args.length; index += 1) {
    const key = args[index];
    if (key === '--mode') {
      mode = args[index + 1] ?? '';
      index += 1;
      continue;
    }
    if (key === '--compose-files') {
      composeFiles = args[index + 1] ?? '';
      index += 1;
      continue;
    }
    if (key === '--acme-authorized') {
      acmeAuthorized = args[index + 1] === 'true';
      index += 1;
      continue;
    }
    fail(`unknown argument '${key}'`);
  }
  if (!mode) fail('usage: assert-invite-production-compose-ports.mjs --mode dark|public-candidate|public-staged [--compose-files TEXT] [--acme-authorized true|false]');

  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  let model;
  try {
    model = JSON.parse(input);
  } catch {
    fail('model resolution did not produce valid Compose JSON');
  }

  const evidence = evaluateInviteComposePorts(model, { mode, composeFiles, acmeAuthorized });
  for (const [key, value] of Object.entries(evidence)) {
    process.stdout.write(`${key}=${value}\n`);
  }
  process.stdout.write('invite_compose_ports=PASS\n');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(`ERROR: compose ports: ${error.message}`);
    process.exit(4);
  });
}
