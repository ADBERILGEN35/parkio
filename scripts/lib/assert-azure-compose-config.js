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

  // PROVIDER-ISTANBUL-01C: Smart Parking rollout booleans must default false (env may enable).
  for (const spaFlag of ['PARKIO_SPA_RECOMMENDATIONS_ENABLED', 'PARKIO_SPA_RANKING_ENABLED']) {
    if (String(parkingEnv[spaFlag] == null ? '' : parkingEnv[spaFlag]) !== 'false') {
      fail(
        'parking-service.' +
          spaFlag +
          " must default to false in rendered Azure compose (got '" +
          parkingEnv[spaFlag] +
          "')",
      );
    }
  }
  // WP-SPA-14: AI ranking shadow defaults off / sample 0.
  if (String(parkingEnv.PARKIO_SPA_RANKING_SHADOW_ENABLED == null ? '' : parkingEnv.PARKIO_SPA_RANKING_SHADOW_ENABLED) !== 'false') {
    fail(
      "parking-service.PARKIO_SPA_RANKING_SHADOW_ENABLED must default to false (got '" +
        parkingEnv.PARKIO_SPA_RANKING_SHADOW_ENABLED +
        "')",
    );
  }
  const shadowRate = String(
    parkingEnv.PARKIO_SPA_RANKING_SHADOW_SAMPLE_RATE == null
      ? ''
      : parkingEnv.PARKIO_SPA_RANKING_SHADOW_SAMPLE_RATE,
  );
  if (shadowRate !== '0.0' && shadowRate !== '0') {
    fail(
      "parking-service.PARKIO_SPA_RANKING_SHADOW_SAMPLE_RATE must default to 0.0 (got '" +
        shadowRate +
        "')",
    );
  }

  const webArgs =
    (data.services && data.services.web && data.services.web.build && data.services.web.build.args) || {};
  const spaWebArg = 'VITE_SMART_PARKING_ASSISTANT_ENABLED';
  if (String(webArgs[spaWebArg] == null ? '' : webArgs[spaWebArg]) !== 'false') {
    fail(
      'web.build.args.' +
        spaWebArg +
        " must default to false in rendered Azure compose (got '" +
        webArgs[spaWebArg] +
        "')",
    );
  }

  let totalMemory = 0;
  for (const svc of runtimeServices) {
    totalMemory += Number(data.services[svc].mem_limit == null ? 0 : data.services[svc].mem_limit);
  }
  // ClamAV's certified 3 GiB limit brings the resolved model to 15.5 GiB.
  const maxMemory = 16 * 1024 * 1024 * 1024;
  if (totalMemory > maxMemory) {
    fail('Azure configured memory total ' + totalMemory + ' exceeds 16 GiB ceiling ' + maxMemory);
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
      ' publicPorts=80,443 tracing=false registryLinkingFlags=false provenancePublication=true duplicatePresentation=true spaFlags=false',
  );
}

main();
