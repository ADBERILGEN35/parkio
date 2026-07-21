#!/usr/bin/env node
/**
 * Fails fast when required EXPO_PUBLIC_* release vars are missing.
 * Must run before Gradle/Expo JS bundle embedding so release APKs cannot
 * ship without an API base URL (avoids the env.ts startup crash).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const appRoot = path.resolve(__dirname, '..');

const REQUIRED = ['EXPO_PUBLIC_APP_ENV', 'EXPO_PUBLIC_API_BASE_URL'];
const ALLOWED_ENVS = new Set(['development', 'hosted-beta', 'production']);

function fail(message) {
  console.error(`[validate-release-env] ${message}`);
  process.exit(1);
}

function loadEasProfile(profileName) {
  const easPath = path.join(appRoot, 'eas.json');
  if (!fs.existsSync(easPath)) {
    return null;
  }
  const eas = JSON.parse(fs.readFileSync(easPath, 'utf8'));
  return eas?.build?.[profileName]?.env ?? null;
}

function loadDotEnvFile(filePath) {
  if (!fs.existsSync(filePath)) {
    return;
  }
  const text = fs.readFileSync(filePath, 'utf8');
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) {
      continue;
    }
    const eq = line.indexOf('=');
    if (eq <= 0) {
      continue;
    }
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (process.env[key] === undefined || process.env[key] === '') {
      process.env[key] = value;
    }
  }
}

function applyEnv(envMap) {
  if (!envMap) {
    return;
  }
  for (const [key, value] of Object.entries(envMap)) {
    if (process.env[key] === undefined || process.env[key] === '') {
      process.env[key] = String(value);
    }
  }
}

const profile = process.argv[2] ?? 'hosted-beta';

// Precedence: existing process env > .env.<profile> > eas.json profile env
loadDotEnvFile(path.join(appRoot, `.env.${profile}`));
applyEnv(loadEasProfile(profile));

for (const key of REQUIRED) {
  const value = process.env[key]?.trim();
  if (!value) {
    fail(
      `${key} is required for release profile "${profile}". Set it in eas.json, the environment, or .env.${profile}.`,
    );
  }
}

if (!ALLOWED_ENVS.has(process.env.EXPO_PUBLIC_APP_ENV)) {
  fail(
    `EXPO_PUBLIC_APP_ENV must be one of ${[...ALLOWED_ENVS].join(', ')}; got "${process.env.EXPO_PUBLIC_APP_ENV}".`,
  );
}

try {
  // eslint-disable-next-line no-new
  new URL(process.env.EXPO_PUBLIC_API_BASE_URL);
} catch {
  fail(
    `EXPO_PUBLIC_API_BASE_URL must be a valid URL; got "${process.env.EXPO_PUBLIC_API_BASE_URL}".`,
  );
}

// Export applied env for parent scripts (print non-secret values only).
console.log(
  `[validate-release-env] profile=${profile} APP_ENV=${process.env.EXPO_PUBLIC_APP_ENV} API_BASE_URL=${process.env.EXPO_PUBLIC_API_BASE_URL}`,
);
