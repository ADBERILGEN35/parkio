/**
 * Test helper: the VITE_MAPTILER_KEY values the repository's example env files carry, so the
 * placeholder tests follow the files instead of a copy of their values (U17 CL-F05R). Commented
 * lines count, because an operator who uncomments the example line gets that value.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', '..');
export const EXAMPLE_ENV_FILES = [
  'docker/.env.invite-production.example',
  'docker/.env.hosted-beta.example',
  'frontend/apps/web/.env.example',
];

export function exampleMapKeys() {
  const found = [];
  for (const file of EXAMPLE_ENV_FILES) {
    for (const line of readFileSync(join(ROOT, file), 'utf8').split('\n')) {
      const match = /^\s*#?\s*VITE_MAPTILER_KEY=["']?([^"'\s]*)["']?\s*$/.exec(line);
      if (match && match[1] !== '') found.push({ file, value: match[1] });
    }
  }
  return found;
}
