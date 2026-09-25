#!/usr/bin/env node
/**
 * Runs expo-doctor. The CNG sync check cannot represent committed android/
 * plus a retained app.json prebuild recipe (NATIVE-BUILD.md). That single
 * finding is replaced by assert-native-ownership.mjs. Every other doctor
 * failure remains blocking. Unknown non-zero exits fail closed.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const CNG_CHECK = 'Check for app config fields that may not be synced in a non-CNG project';
const appRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function resolveDoctor() {
  const names = process.platform === 'win32' ? ['expo-doctor.cmd', 'expo-doctor'] : ['expo-doctor'];
  const roots = [
    path.join(appRoot, 'node_modules', '.bin'),
    path.join(appRoot, '..', '..', 'node_modules', '.bin'),
  ];
  for (const root of roots) {
    for (const name of names) {
      const candidate = path.join(root, name);
      if (fs.existsSync(candidate)) {
        return candidate;
      }
    }
  }
  return 'expo-doctor';
}

const result = spawnSync(resolveDoctor(), [], {
  cwd: appRoot,
  encoding: 'utf8',
  env: process.env,
  shell: process.platform === 'win32',
});

process.stdout.write(result.stdout ?? '');
process.stderr.write(result.stderr ?? '');

const output = `${result.stdout ?? ''}${result.stderr ?? ''}`.replace(
  // Strip ANSI so colored CI logs still parse.
  /\u001b\[[0-9;]*m/g,
  '',
);
const failed = [];
for (const line of output.split(/\r?\n/)) {
  const match = line.match(/^(?:\u2716|x)\s+(.+)$/i);
  if (match) {
    failed.push(match[1].trim());
  }
}

if (result.status === 0) {
  console.log('[run-expo-doctor] expo-doctor passed all checks.');
  process.exit(0);
}

if (failed.length === 1 && failed[0] === CNG_CHECK) {
  console.log('');
  console.log('[run-expo-doctor] expo-doctor failed only the CNG sync check.');
  console.log('That check cannot represent committed android/ plus a retained');
  console.log('app.json prebuild recipe. See NATIVE-BUILD.md.');
  console.log('Replacement gate: scripts/assert-native-ownership.mjs (required in Mobile CI).');
  console.log('Any other expo-doctor failure still fails this wrapper.');
  process.exit(0);
}

if (failed.length === 0) {
  console.error(
    '[run-expo-doctor] expo-doctor exited non-zero but no failed checks were parsed. Failing closed.',
  );
} else {
  console.error(`[run-expo-doctor] blocking expo-doctor failures: ${failed.join('; ')}`);
}
process.exit(result.status ?? 1);
