#!/usr/bin/env node
/**
 * Deterministic invite-production NSG ingress assertions (PROD-DEPLOY-01B-03C1/03C2).
 * Parses the canonical modules/app-nsg.bicep securityRules without Azure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const modulePath = path.join(root, 'infra/azure/invite-production/modules/app-nsg.bicep');
const mainPath = path.join(root, 'infra/azure/invite-production/main.bicep');
const scopedPath = path.join(root, 'infra/azure/invite-production/nsg-only.bicep');
const scopedScriptPath = path.join(root, 'scripts/azure/provision-invite-production-nsg.sh');

const FORBIDDEN_PUBLIC_PORTS = [
  '22', '8080', '5432', '6379', '9092', '9000', '9001',
  '3000', '9090', '9093', '3100', '3200',
  '8081', '8082', '8083', '8084', '8085', '8086', '8087', '8088', '8089',
];

function extractSecurityRules(source) {
  const nsgMatch = source.match(
    /resource appNsg[\s\S]*?securityRules:\s*\[([\s\S]*?)\]\s*\n\s*\}\s*\n\}/,
  );
  if (!nsgMatch) {
    throw new Error('appNsg securityRules block not found');
  }
  const body = nsgMatch[1];
  const rules = [];
  const ruleRe = /\{\s*name:\s*'([^']+)'\s*properties:\s*\{([\s\S]*?)\}\s*\}/g;
  let match;
  while ((match = ruleRe.exec(body)) !== null) {
    const name = match[1];
    const props = match[2];
    const get = (key) => {
      const m = props.match(new RegExp(`${key}:\\s*'([^']+)'`))
        || props.match(new RegExp(`${key}:\\s*(\\d+)`));
      return m ? m[1] : undefined;
    };
    rules.push({
      name,
      priority: Number(get('priority')),
      access: get('access'),
      direction: get('direction'),
      protocol: get('protocol'),
      sourcePortRange: get('sourcePortRange'),
      destinationPortRange: get('destinationPortRange'),
      sourceAddressPrefix: get('sourceAddressPrefix'),
      destinationAddressPrefix: get('destinationAddressPrefix'),
    });
  }
  return rules;
}

function assertCanonicalRules(rules) {
  assert.equal(rules.length, 2, `expected exactly 2 custom rules, got ${rules.length}`);

  const https = rules.find((r) => r.name === 'Allow-Https-From-Internet');
  const http = rules.find((r) => r.name === 'Allow-Http-From-Internet');
  assert.ok(https, 'missing Allow-Https-From-Internet');
  assert.ok(http, 'missing Allow-Http-From-Internet');

  assert.deepEqual(
    {
      priority: https.priority,
      access: https.access,
      direction: https.direction,
      protocol: https.protocol,
      sourcePortRange: https.sourcePortRange,
      destinationPortRange: https.destinationPortRange,
      sourceAddressPrefix: https.sourceAddressPrefix,
      destinationAddressPrefix: https.destinationAddressPrefix,
    },
    {
      priority: 100,
      access: 'Allow',
      direction: 'Inbound',
      protocol: 'Tcp',
      sourcePortRange: '*',
      destinationPortRange: '443',
      sourceAddressPrefix: 'Internet',
      destinationAddressPrefix: '*',
    },
  );

  assert.deepEqual(
    {
      priority: http.priority,
      access: http.access,
      direction: http.direction,
      protocol: http.protocol,
      sourcePortRange: http.sourcePortRange,
      destinationPortRange: http.destinationPortRange,
      sourceAddressPrefix: http.sourceAddressPrefix,
      destinationAddressPrefix: http.destinationAddressPrefix,
    },
    {
      priority: 110,
      access: 'Allow',
      direction: 'Inbound',
      protocol: 'Tcp',
      sourcePortRange: '*',
      destinationPortRange: '80',
      sourceAddressPrefix: 'Internet',
      destinationAddressPrefix: '*',
    },
  );
}

test('canonical NSG module has exactly HTTPS 443 + HTTP 80 Internet allows', () => {
  const source = fs.readFileSync(modulePath, 'utf8');
  assertCanonicalRules(extractSecurityRules(source));
});

test('full main.bicep consumes canonical NSG module', () => {
  const source = fs.readFileSync(mainPath, 'utf8');
  assert.match(source, /module appNsg 'modules\/app-nsg\.bicep'/);
  assert.match(source, /appNsg\.outputs\.nsgId/);
  assert.doesNotMatch(source, /resource appNsg 'Microsoft\.Network\/networkSecurityGroups/);
});

test('scoped nsg-only.bicep deploys only canonical NSG module', () => {
  const source = fs.readFileSync(scopedPath, 'utf8');
  assert.match(source, /module appNsg 'modules\/app-nsg\.bicep'/);
  assert.doesNotMatch(source, /Microsoft\.Compute\/virtualMachines/);
  assert.doesNotMatch(source, /Microsoft\.DBforPostgreSQL/);
  assert.doesNotMatch(source, /Microsoft\.KeyVault/);
  assert.doesNotMatch(source, /Microsoft\.Storage/);
  assert.doesNotMatch(source, /Microsoft\.Authorization\/roleAssignments/);
  assert.doesNotMatch(source, /Microsoft\.Network\/virtualNetworks/);
});

test('invite-production NSG does not open forbidden public ports', () => {
  const source = fs.readFileSync(modulePath, 'utf8');
  const rules = extractSecurityRules(source);
  for (const rule of rules) {
    if (rule.access !== 'Allow' || rule.direction !== 'Inbound') continue;
    if (rule.sourceAddressPrefix !== 'Internet') continue;
    assert.ok(
      !FORBIDDEN_PUBLIC_PORTS.includes(String(rule.destinationPortRange)),
      `forbidden public ingress port ${rule.destinationPortRange} on ${rule.name}`,
    );
  }
});

test('HTTPS priority remains 100 and HTTP is adjacent unused 110', () => {
  const source = fs.readFileSync(modulePath, 'utf8');
  const rules = extractSecurityRules(source);
  const priorities = rules.map((r) => r.priority).sort((a, b) => a - b);
  assert.deepEqual(priorities, [100, 110]);
});

test('scoped provision script requires explicit apply confirmation', () => {
  const source = fs.readFileSync(scopedScriptPath, 'utf8');
  assert.match(source, /--apply/);
  assert.match(source, /--confirm/);
  assert.match(source, /PROD-DEPLOY-01B-03C3/);
  assert.match(source, /rg-parkio-invite-production-we/);
  assert.match(source, /nsg-parkio-invite-app/);
  assert.match(source, /validate_scoped_what_if_output/);
});
