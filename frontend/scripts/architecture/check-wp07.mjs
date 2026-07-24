import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { listSourceFiles, normalizeRepositoryPath } from './guardrail-lib.mjs';
import { findWp07MobileFoundationViolations } from './wp07-mobile-foundation.mjs';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(scriptDirectory, '../..');
const mobileRoots = [
  path.join(frontendRoot, 'apps/mobile-v2/src'),
  path.join(frontendRoot, 'apps/mobile-v2/app'),
];

export async function checkWp07MobileFoundation() {
  const violations = [];
  let scannedFiles = 0;

  for (const absoluteRoot of mobileRoots) {
    const files = await listSourceFiles(absoluteRoot);
    scannedFiles += files.length;
    for (const file of files) {
      const source = await readFile(file, 'utf8');
      const repositoryPath = normalizeRepositoryPath(path.relative(frontendRoot, file));
      for (const violation of findWp07MobileFoundationViolations(source, repositoryPath)) {
        violations.push({ ...violation, file: repositoryPath });
      }
    }
  }

  return { scannedFiles, violations };
}

const isMain =
  process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;

if (isMain) {
  const result = await checkWp07MobileFoundation();
  if (result.violations.length > 0) {
    console.error(`WP-07 mobile-foundation guardrails failed (${result.violations.length}):`);
    for (const violation of result.violations) {
      console.error(`- ${violation.file}:${violation.line} [${violation.rule}] ${violation.detail}`);
    }
    process.exitCode = 1;
  } else {
    console.log(
      `WP-07 mobile-foundation guardrails passed (${result.scannedFiles} mobile-v2 source files).`,
    );
  }
}