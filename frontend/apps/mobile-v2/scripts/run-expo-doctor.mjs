#!/usr/bin/env node
/**
 * Runs expo-doctor. The CNG sync check cannot represent committed android/
 * plus a retained app.json prebuild recipe (NATIVE-BUILD.md). That single
 * identified diagnostic is replaced by assert-native-ownership.mjs.
 *
 * Fail closed on: spawn errors, unexpected exit codes, unparsable output,
 * extra failed checks, missing summary, or a different CNG wording.
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const CNG_CHECK = 'Check for app config fields that may not be synced in a non-CNG project';

const appRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function stripAnsi(text) {
  return String(text).replace(/\u001b\[[0-9;]*m/g, '');
}

export function parseDoctorOutput(raw) {
  const output = stripAnsi(raw);
  const failed = [];
  for (const line of output.split(/\r?\n/)) {
    const match = line.match(/^\u2716\s+(.+)$/);
    if (match) {
      failed.push(match[1].trim());
    }
  }
  const summary = output.match(/(\d+)\/(\d+) checks passed\.\s+(\d+) checks failed/);
  return {
    output,
    failed,
    passed: summary ? Number(summary[1]) : null,
    total: summary ? Number(summary[2]) : null,
    failedCount: summary ? Number(summary[3]) : null,
    mentionsNativeFolders: /native project folders/i.test(output),
    mentionsPrebuild: /configured to use Prebuild/i.test(output),
  };
}

export function evaluateDoctorResult({ status, output, spawnError }) {
  if (spawnError) {
    return { ok: false, reason: `expo-doctor spawn failed: ${spawnError.message || spawnError}` };
  }
  if (status === 0) {
    const parsed = parseDoctorOutput(output);
    if (parsed.failed.length > 0) {
      return { ok: false, reason: 'exit 0 but failed checks were parsed; failing closed' };
    }
    return { ok: true, reason: 'expo-doctor passed all checks', parsed };
  }
  if (status !== 1) {
    return { ok: false, reason: `unexpected expo-doctor exit ${status}; failing closed` };
  }

  const parsed = parseDoctorOutput(output);
  if (parsed.passed === null || parsed.total === null || parsed.failedCount === null) {
    return { ok: false, reason: 'expo-doctor exited 1 but the N/M summary was not parsed; failing closed' };
  }
  if (parsed.passed + parsed.failedCount !== parsed.total) {
    return { ok: false, reason: `inconsistent summary ${parsed.passed}/${parsed.total} failed=${parsed.failedCount}` };
  }
  if (parsed.failedCount !== 1 || parsed.failed.length !== 1) {
    return {
      ok: false,
      reason: `blocking expo-doctor failures (${parsed.failedCount} reported, ${parsed.failed.length} parsed): ${parsed.failed.join('; ')}`,
    };
  }
  if (parsed.failed[0] !== CNG_CHECK) {
    return { ok: false, reason: `unrecognized failed check: ${parsed.failed[0]}` };
  }
  if (!parsed.mentionsNativeFolders || !parsed.mentionsPrebuild) {
    return {
      ok: false,
      reason: 'CNG check name matched but the native-folder/Prebuild diagnostic text was missing; failing closed',
    };
  }
  return { ok: true, reason: 'CNG-only diagnostic replaced by native ownership', parsed };
}

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

function main() {
  if (process.env.PARKIO_DOCTOR_EVAL_STATUS !== undefined) {
    const result = evaluateDoctorResult({
      status: Number(process.env.PARKIO_DOCTOR_EVAL_STATUS),
      output: process.env.PARKIO_DOCTOR_EVAL_OUTPUT ?? '',
      spawnError: process.env.PARKIO_DOCTOR_EVAL_SPAWN_ERROR
        ? new Error(process.env.PARKIO_DOCTOR_EVAL_SPAWN_ERROR)
        : null,
    });
    process.stdout.write((process.env.PARKIO_DOCTOR_EVAL_OUTPUT ?? '') + '\n');
    console.log(`[run-expo-doctor] ${result.reason}`);
    process.exit(result.ok ? 0 : 1);
  }

  const spawned = spawnSync(resolveDoctor(), [], {
    cwd: appRoot,
    encoding: 'utf8',
    env: process.env,
    shell: process.platform === 'win32',
  });
  process.stdout.write(spawned.stdout ?? '');
  process.stderr.write(spawned.stderr ?? '');

  const result = evaluateDoctorResult({
    status: spawned.status,
    output: `${spawned.stdout ?? ''}${spawned.stderr ?? ''}`,
    spawnError: spawned.error ?? null,
  });
  if (result.ok && result.parsed?.failed?.[0] === CNG_CHECK) {
    console.log('');
    console.log('[run-expo-doctor] expo-doctor failed only the CNG sync check.');
    console.log('That check cannot represent committed android/ plus a retained');
    console.log('app.json prebuild recipe. See NATIVE-BUILD.md.');
    console.log('Replacement gate: scripts/assert-native-ownership.mjs (required in Mobile CI).');
    console.log('Any other expo-doctor failure still fails this wrapper.');
    process.exit(0);
  }
  if (result.ok) {
    console.log('[run-expo-doctor] expo-doctor passed all checks.');
    process.exit(0);
  }
  console.error(`[run-expo-doctor] ${result.reason}`);
  process.exit(1);
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  main();
}
