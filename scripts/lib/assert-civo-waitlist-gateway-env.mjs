#!/usr/bin/env node
/**
 * Assert gateway-service waitlist env mappings on a resolved Compose JSON model.
 * Reads stdin; never prints secret values — only names and non-secret allowlisted values.
 */
import { createInterface } from 'node:readline';

const REQUIRED_NAMES = [
  'PARKIO_WAITLIST_HASH_SECRET',
  'PARKIO_WAITLIST_ADMISSIONS_ENABLED',
  'PARKIO_WAITLIST_EMAIL_PROVIDER',
  'PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER',
  'PARKIO_WAITLIST_EMAIL_FROM',
  'PARKIO_WAITLIST_EMAIL_REPLY_TO',
  'PARKIO_WAITLIST_CONFIRM_URL',
  'PARKIO_WAITLIST_WITHDRAW_URL',
  'PARKIO_WAITLIST_RESEND_API_KEY',
  'PARKIO_CORS_ALLOWED_ORIGINS',
];

const NON_SECRET_EXPECTED = {
  PARKIO_WAITLIST_ADMISSIONS_ENABLED: 'false',
  PARKIO_WAITLIST_EMAIL_PROVIDER: 'resend',
  PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER: 'false',
  PARKIO_WAITLIST_EMAIL_FROM: 'Parkio <info@parkio.dev>',
  PARKIO_WAITLIST_EMAIL_REPLY_TO: 'info@parkio.dev',
  PARKIO_WAITLIST_CONFIRM_URL: 'https://parkio.dev/waitlist/confirm/',
  PARKIO_WAITLIST_WITHDRAW_URL: 'https://parkio.dev/waitlist/unsubscribe/',
};

let raw = '';
for await (const chunk of process.stdin) raw += chunk;

let model;
try {
  model = JSON.parse(raw);
} catch {
  console.error('FAIL: invalid Compose JSON on stdin');
  process.exit(2);
}

const gateway = model?.services?.['gateway-service'];
if (!gateway?.environment || typeof gateway.environment !== 'object') {
  console.error('FAIL: gateway-service.environment missing');
  process.exit(1);
}

const env = gateway.environment;
const errors = [];
const present = [];

for (const name of REQUIRED_NAMES) {
  if (!(name in env)) {
    errors.push(`missing ${name}`);
  } else {
    present.push(name);
  }
}

for (const [key, expected] of Object.entries(NON_SECRET_EXPECTED)) {
  const actual = env[key] == null ? '' : String(env[key]);
  if (actual !== expected) {
    errors.push(`${key} expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

const cors = String(env.PARKIO_CORS_ALLOWED_ORIGINS || '');
const corsOrigins = new Set(
  cors
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean),
);
if (!corsOrigins.has('https://parkio.dev') || !corsOrigins.has('https://app.parkio.dev')) {
  errors.push('PARKIO_CORS_ALLOWED_ORIGINS must include app.parkio.dev and parkio.dev');
}

// Secret presence only — never print values
for (const secret of ['PARKIO_WAITLIST_HASH_SECRET', 'PARKIO_WAITLIST_RESEND_API_KEY']) {
  const v = env[secret];
  if (v == null || String(v).trim() === '') {
    errors.push(`${secret} must be present and non-empty in resolved model`);
  }
}

if (errors.length) {
  console.error('FAIL: gateway waitlist compose wiring');
  for (const e of errors) console.error(`  - ${e}`);
  process.exit(1);
}

const evidence = {
  schemaVersion: 1,
  service: 'gateway-service',
  waitlistEnvNamesPresent: present.sort(),
  nonSecretValues: NON_SECRET_EXPECTED,
  corsIncludesParkioDev: true,
  secretsPresentNotPrinted: ['PARKIO_WAITLIST_HASH_SECRET', 'PARKIO_WAITLIST_RESEND_API_KEY'],
};
console.log('PASS: gateway waitlist environment mappings present on effective Compose model');
console.log(JSON.stringify(evidence, null, 2));
