import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { isBackendProtectedPath, normalizeRepositoryPath } from './guardrail-lib.mjs';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, '../../..');

function runGit(argumentsList) {
  return execFileSync('git', argumentsList, {
    cwd: repositoryRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  })
    .split(/\r?\n/)
    .map((entry) => normalizeRepositoryPath(entry.trim()))
    .filter(Boolean);
}

export function collectChangedFiles(baseReference) {
  if (baseReference) {
    return runGit(['diff', '--name-only', '--diff-filter=ACMRD', `${baseReference}...HEAD`, '--']);
  }

  return [
    ...runGit(['diff', '--name-only', '--diff-filter=ACMRD', '--']),
    ...runGit(['diff', '--cached', '--name-only', '--diff-filter=ACMRD', '--']),
    ...runGit(['ls-files', '--others', '--exclude-standard']),
  ];
}

export function findBackendChanges(filePaths) {
  return [...new Set(filePaths.filter(isBackendProtectedPath))].sort();
}

const baseArgumentIndex = process.argv.indexOf('--base');
const baseReference = baseArgumentIndex >= 0 ? process.argv[baseArgumentIndex + 1] : undefined;
if (baseArgumentIndex >= 0 && !baseReference) {
  console.error('Missing git reference after --base.');
  process.exitCode = 2;
} else {
  const changedFiles = collectChangedFiles(baseReference);
  const backendChanges = findBackendChanges(changedFiles);

  if (backendChanges.length > 0) {
    console.error(
      `Backend-change detection failed. ${backendChanges.length} protected backend file(s) changed:`,
    );
    for (const file of backendChanges.slice(0, 50)) {
      console.error(`- ${file}`);
    }
    if (backendChanges.length > 50) {
      console.error(`- ... ${backendChanges.length - 50} additional protected file(s)`);
    }
    process.exitCode = 1;
  } else {
    const comparison = baseReference ? `against ${baseReference}...HEAD` : 'in the working tree';
    console.log(
      `Backend-change detection passed ${comparison} (${changedFiles.length} changed file(s) inspected).`,
    );
  }
}
