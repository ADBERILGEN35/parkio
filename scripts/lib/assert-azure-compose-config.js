#!/usr/bin/env node
/**
 * Deterministic Azure hosted-beta compose post-checks.
 * Used by validate-hosted-beta-compose.sh so Windows checkouts without jq still pass.
 *
 * Usage: node scripts/lib/assert-azure-compose-config.js <rendered-compose.json> <runtime-services-file>
 * runtime-services-file: one service name per line
 */
'use strict';

const fs = require('fs');

function fail(message) {
  console.error('ERROR: ' + message);
  process.exit(4);
}

function main() {
  const renderedPath = process.argv[2];
  const runtimePath = process.argv[3];
  if (!renderedPath || !runtimePath) {
    fail('usage: assert-azure-compose-config.js <rendered.json> <runtime-services.txt>');
  }

  const data = JSON.parse(fs.readFileSync(renderedPath, 'utf8'));
  const runtimeServices = fs
    .readFileSync(runtimePath, 'utf8')
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean);

  if (runtimeServices.length !== 32) {
    fail('Azure runtime service count must be 32, got ' + runtimeServices.length);
  }

  for (const svc of runtimeServices) {
    if (!data.services || data.services[svc] == null) {
      fail("Azure runtime service '" + svc + "' missing from rendered config");
    }
    if (data.services[svc].platform !== 'linux/amd64') {
      fail("Azure runtime service '" + svc + "' does not enforce linux/amd64");
    }
  }

  const jvm = [
    'gateway-service',
    'auth-service',
    'user-service',
    'parking-service',
    'media-service',
    'gamification-service',
    'notification-service',
    'moderation-service',
    'ai-validation-service',
    'analytics-service',
  ];
  for (const svc of jvm) {
    const tracing = data.services && data.services[svc] && data.services[svc].environment
      ? data.services[svc].environment.PARKIO_TRACING_ENABLED
      : undefined;
    if (String(tracing) !== 'false') {
      fail("tracing is not disabled for '" + svc + "'");
    }
  }

  const registryFlagsOff = [
    'PARKIO_MUNICIPAL_REGISTRY_CANDIDATE_GENERATION_ENABLED',
    'PARKIO_MUNICIPAL_REGISTRY_REVIEW_API_ENABLED',
    'PARKIO_MUNICIPAL_REGISTRY_REVIEWED_LINKING_ENABLED',
    'PARKIO_MUNICIPAL_REGISTRY_AUTOMATIC_LINKING_ENABLED',
  ];
  const parkingEnv = (data.services && data.services['parking-service'] && data.services['parking-service'].environment) || {};
  for (const flag of registryFlagsOff) {
    if (String(parkingEnv[flag] == null ? '' : parkingEnv[flag]) !== 'false') {
      fail('parking-service.' + flag + " must default to false in rendered Azure compose (got '" + parkingEnv[flag] + "')");
    }
  }
  // DATA-WP-11: public provenance publication is default-on for hosted-beta leave-on prep.
  const provenanceFlag = 'PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED';
  if (String(parkingEnv[provenanceFlag] == null ? '' : parkingEnv[provenanceFlag]) !== 'true') {
    fail(
      'parking-service.' +
        provenanceFlag +
        " must default to true in rendered Azure compose (DATA-WP-11; got '" +
        parkingEnv[provenanceFlag] +
        "')",
    );
  }
  // DATA-WP-12: nearby duplicate-presentation is default-on for hosted-beta leave-on prep.
  const duplicateFlag = 'PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED';
  if (String(parkingEnv[duplicateFlag] == null ? '' : parkingEnv[duplicateFlag]) !== 'true') {
    fail(
      'parking-service.' +
        duplicateFlag +
        " must default to true in rendered Azure compose (DATA-WP-12; got '" +
        parkingEnv[duplicateFlag] +
        "')",
    );
  }

  let totalMemory = 0;
  for (const svc of runtimeServices) {
    totalMemory += Number(data.services[svc].mem_limit == null ? 0 : data.services[svc].mem_limit);
  }
  const maxMemory = 14 * 1024 * 1024 * 1024;
  if (totalMemory > maxMemory) {
    fail('Azure configured memory total ' + totalMemory + ' exceeds 14 GiB target ' + maxMemory);
  }

  const badPorts = [];
  for (const [key, svc] of Object.entries(data.services || {})) {
    for (const p of svc.ports || []) {
      if ((p.host_ip || '') === '127.0.0.1') continue;
      const published = String(p.published);
      if (key === 'caddy' && (published === '80' || published === '443')) continue;
      badPorts.push(key + ':' + published);
    }
  }
  if (badPorts.length > 0) {
    fail('rendered Azure profile exposes a non-Caddy port beyond loopback (' + badPorts.join(', ') + ')');
  }

  const caddyPublic = [
    ...new Set(
      (data.services && data.services.caddy && data.services.caddy.ports ? data.services.caddy.ports : [])
        .filter((p) => (p.host_ip || '') !== '127.0.0.1')
        .map((p) => String(p.published)),
    ),
  ].sort();
  if (JSON.stringify(caddyPublic) !== JSON.stringify(['443', '80'])) {
    fail('Caddy must be the only public service and publish exactly 80 and 443');
  }

  const promCmd = data.services && data.services.prometheus ? data.services.prometheus.command : null;
  if (!Array.isArray(promCmd) || !promCmd.includes('--storage.tsdb.retention.time=7d')) {
    fail('prometheus retention command missing --storage.tsdb.retention.time=7d');
  }

  const grafanaDeps = Object.keys(
    (data.services && data.services.grafana && data.services.grafana.depends_on) || {},
  );
  if (JSON.stringify(grafanaDeps) !== JSON.stringify(['prometheus'])) {
    fail('grafana depends_on must be exactly [prometheus]');
  }

  console.log(
    'OK: Azure runtime services=32 disabled=4 memoryBytes=' +
      totalMemory +
      ' publicPorts=80,443 tracing=false registryLinkingFlags=false provenancePublication=true duplicatePresentation=true',
  );
}

main();