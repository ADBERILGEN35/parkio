/**
 * Map-key values that the repository's example env files ship as placeholders (U17 CL-F05R).
 *
 * A bundle built from an example env passes every "non-empty" check and then fails at runtime
 * with a rejected key, so production-like builds refuse these at build and bundle time. CI's
 * synthetic fixture keys are deliberately not listed here: CI builds production-like bundles
 * with them on purpose, and the deploy guard (scripts/lib/web_bundle_map_config.py) refuses
 * those at deploy time. Callers report a status only, never the value.
 */

/** Exact example-env values, compared case-insensitively. */
export const EXAMPLE_ENV_PLACEHOLDERS = ['REPLACE_ME_maptiler_public_key', 'your_maptiler_key'];

/** Any value with this prefix is a placeholder, whatever follows it. */
export const PLACEHOLDER_PREFIX = 'REPLACE_ME_';

const exact = new Set(EXAMPLE_ENV_PLACEHOLDERS.map((value) => value.toLowerCase()));

export function isExampleEnvPlaceholder(value) {
  const normalized = String(value ?? '').trim().toLowerCase();
  return exact.has(normalized) || normalized.startsWith(PLACEHOLDER_PREFIX.toLowerCase());
}
