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
const required = ['VITE_API_BASE_URL'];
if (PRODUCTION_LIKE.has(appEnv)) required.push('VITE_MAPTILER_KEY');

const failures = [];
for (const name of required) {
  const status = (process.env[name] ?? '').trim() === '' ? 'EMPTY' : 'PRESENT';
  console.log(`validate-build-env: ${name} = ${status}`);
  if (status !== 'PRESENT') failures.push(name);
}

if (failures.length > 0) {
  console.error(
    `validate-build-env: required public build configuration is empty for VITE_APP_ENV=${appEnv || '(unset)'}`,
  );
  for (const name of failures) console.error(`validate-build-env:   ${name} is required`);
  process.exit(1);
}

console.log(
  `validate-build-env: OK for VITE_APP_ENV=${appEnv || '(unset)'} production_like=${PRODUCTION_LIKE.has(appEnv)}`,
);
