#!/usr/bin/env node
/**
 * Pre-build gate for the public values Vite will embed into the browser bundle.
 *
 * Keep all value reads inside this process. Referencing a build argument in a Dockerfile
 * RUN shell expression can cause BuildKit's progress renderer to echo the expanded command,
 * which would disclose the value in CI logs. This validator prints names and status only.
 */

const PRODUCTION_LIKE = new Set(['hosted-beta', 'production']);

const appEnv = process.env.VITE_APP_ENV ?? '';
const municipalRaw = (process.env.VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED ?? '').trim();
const municipalEnabled = municipalRaw === 'true';
const required = ['VITE_API_BASE_URL'];
if (PRODUCTION_LIKE.has(appEnv)) required.push('VITE_MAPTILER_KEY');

const failures = [];
for (const name of required) {
  const status = (process.env[name] ?? '').trim() === '' ? 'EMPTY' : 'PRESENT';
  console.log(`validate-build-env: ${name} = ${status}`);
  if (status !== 'PRESENT') failures.push(name);
}

// PROD-MUNI-01 / M3: production builds must never bake municipal discovery on.
const municipalStatus = municipalRaw === '' ? 'UNSET' : municipalEnabled ? 'true' : municipalRaw;
console.log(`validate-build-env: VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED = ${municipalStatus}`);
if (appEnv === 'production' && municipalEnabled) {
  failures.push('VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED');
  console.error(
    'validate-build-env: production builds must not set VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true (PROD-MUNI-01 bake guard)',
  );
}

if (failures.length > 0) {
  console.error(`validate-build-env: FAILED for VITE_APP_ENV=${appEnv || '(unset)'}`);
  for (const name of failures) {
    if (name === 'VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED') {
      console.error(
        'validate-build-env:   VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED must not be true when VITE_APP_ENV=production',
      );
    } else {
      console.error(`validate-build-env:   ${name} is required`);
    }
  }
  process.exit(1);
}

console.log(
  `validate-build-env: OK for VITE_APP_ENV=${appEnv || '(unset)'} production_like=${PRODUCTION_LIKE.has(appEnv)} municipal=${municipalStatus}`,
);
