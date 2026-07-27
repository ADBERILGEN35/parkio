#!/usr/bin/env node
/**
 * Post-build gate: prove the emitted Vite bundle carries a non-empty value for every
 * public variable that `src/config/env.ts` requires for the target app environment.
 *
 * Why this exists (defect I11, v1.0.0-rc5): the release build baked
 * `VITE_MAPTILER_KEY:""`. `createFrontendConfig` validates during *module evaluation*, so
 * the SPA threw before React mounted and every route rendered a blank page - while nginx
 * still served index.html and every asset with HTTP 200. No HTTP-level check can see that.
 * This script inspects the built artifact itself, so an image with an empty required value
 * can never be produced, let alone published.
 *
 * Values are NEVER printed. Only variable names and PRESENT/EMPTY/MISSING statuses are
 * reported, so this is safe to run in CI logs.
 *
 * Usage:
 *   node scripts/verify-bundle-env.mjs [--dist <dir>] [--app-env <env>]
 *
 * --app-env defaults to the VITE_APP_ENV baked into the bundle. Passing it explicitly also
 * asserts the bundle was built for that environment, which catches build-arg mis-wiring.
 */

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

/** Mirrors `requireInProductionLike` in src/config/env.ts. */
const PRODUCTION_LIKE = new Set(['hosted-beta', 'production']);

/** Public vars that src/config/env.ts throws on when absent in a production-like build. */
const REQUIRED_IN_PRODUCTION_LIKE = ['VITE_API_BASE_URL', 'VITE_MAPTILER_KEY'];

function parseArgs(argv) {
  const out = { dist: 'dist', appEnv: undefined };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--dist') out.dist = argv[++i];
    else if (argv[i] === '--app-env') out.appEnv = argv[++i];
    else throw new Error(`unknown argument: ${argv[i]}`);
  }
  return out;
}

function collectJsFiles(dir) {
  const found = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) found.push(...collectJsFiles(full));
    else if (entry.endsWith('.js')) found.push(full);
  }
  return found;
}

/**
 * Vite replaces a whole-object `import.meta.env` reference with an inlined object literal.
 * We locate it by its VITE_APP_ENV member and read the flat `"KEY":"value"` pairs, without
 * evaluating any bundle code.
 */
function extractInjectedEnv(source) {
  const appEnvMember = /["']?\bVITE_APP_ENV["']?\s*:/.exec(source);
  if (!appEnvMember) return null;
  const anchor = appEnvMember.index;

  // Walk back to the opening brace of the enclosing object literal.
  let start = -1;
  for (let i = anchor; i >= 0; i -= 1) {
    if (source[i] === '{') {
      start = i;
      break;
    }
  }
  if (start === -1) return null;

  // Walk forward to its matching close brace, ignoring braces inside string literals.
  let depth = 0;
  let end = -1;
  let inString = false;
  let quote = '';
  for (let i = start; i < source.length; i += 1) {
    const ch = source[i];
    if (inString) {
      if (ch === '\\') i += 1;
      else if (ch === quote) inString = false;
      continue;
    }
    if (ch === '"' || ch === "'") {
      inString = true;
      quote = ch;
      continue;
    }
    if (ch === '{') depth += 1;
    else if (ch === '}') {
      depth -= 1;
      if (depth === 0) {
        end = i;
        break;
      }
    }
  }
  if (end === -1) return null;

  const literal = source.slice(start, end + 1);
  const env = {};
  // Only flat string members matter; booleans like DEV:!1 are irrelevant here.
  const pair = /["']?(VITE_[A-Z0-9_]+)["']?\s*:\s*"((?:[^"\\]|\\.)*)"/g;
  let match;
  while ((match = pair.exec(literal)) !== null) {
    env[match[1]] = match[2];
  }
  return Object.keys(env).length > 0 ? env : null;
}

function main() {
  const { dist, appEnv: expectedAppEnv } = parseArgs(process.argv.slice(2));
  const distDir = resolve(dist);

  if (!existsSync(distDir)) {
    console.error(`verify-bundle-env: dist directory not found: ${distDir}`);
    process.exit(1);
  }

  const files = collectJsFiles(distDir);
  if (files.length === 0) {
    console.error(`verify-bundle-env: no .js files under ${distDir}`);
    process.exit(1);
  }

  let env = null;
  let sourceFile = null;
  for (const file of files) {
    const found = extractInjectedEnv(readFileSync(file, 'utf8'));
    if (found) {
      env = found;
      sourceFile = file;
      break;
    }
  }

  if (!env) {
    console.error(
      'verify-bundle-env: could not locate the injected import.meta.env object in the bundle.\n' +
        'If the Vite/bundler output shape changed, update this script - do not delete the gate.',
    );
    process.exit(1);
  }

  const appEnv = env.VITE_APP_ENV ?? '';
  const failures = [];

  if (expectedAppEnv && appEnv !== expectedAppEnv) {
    failures.push(
      `VITE_APP_ENV is "${appEnv}" but the build was expected to target "${expectedAppEnv}" ` +
        '(build-arg wiring is wrong)',
    );
  }

  const productionLike = PRODUCTION_LIKE.has(appEnv);
  const checked = productionLike ? REQUIRED_IN_PRODUCTION_LIKE : [];

  for (const key of checked) {
    if (!(key in env)) failures.push(`${key} is MISSING from the bundle`);
    else if (env[key].trim() === '') failures.push(`${key} is EMPTY in the bundle`);
  }

  // Report names and statuses only - never values.
  console.log(`verify-bundle-env: bundle=${sourceFile.slice(distDir.length + 1)}`);
  console.log(`verify-bundle-env: VITE_APP_ENV=${appEnv || '(unset)'} production_like=${productionLike}`);
  for (const key of checked) {
    const status = !(key in env) ? 'MISSING' : env[key].trim() === '' ? 'EMPTY' : 'PRESENT';
    console.log(`verify-bundle-env:   ${key} = ${status}`);
  }
  if (!productionLike) {
    console.log(
      'verify-bundle-env: app env is not production-like; required-value checks are not applicable.',
    );
  }

  if (failures.length > 0) {
    console.error('\nverify-bundle-env: FAILED');
    for (const failure of failures) console.error(`  - ${failure}`);
    console.error(
      '\nA production-like bundle with an empty required value throws during module\n' +
        'evaluation and white-screens the SPA. Refusing to accept this artifact.',
    );
    process.exit(1);
  }

  console.log('verify-bundle-env: OK');
}

main();
