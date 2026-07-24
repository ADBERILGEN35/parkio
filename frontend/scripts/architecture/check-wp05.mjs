import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { listSourceFiles, normalizeRepositoryPath } from './guardrail-lib.mjs';
import { findWp05CoreParkingViolations } from './wp05-core-parking-flows.mjs';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(scriptDirectory, '../..');
const webSrcRoot = path.join(frontendRoot, 'apps/web/src');

export async function checkWp05CoreParkingFlows() {
  const violations = [];
  const files = await listSourceFiles(webSrcRoot);

  for (const file of files) {
    const source = await readFile(file, 'utf8');
    const repositoryPath = normalizeRepositoryPath(path.relative(frontendRoot, file));
    for (const violation of findWp05CoreParkingViolations(source, repositoryPath)) {
      violations.push({ ...violation, file: repositoryPath });
    }
  }

  return { scannedFiles: files.length, violations };
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isMain) {
  const result = await checkWp05CoreParkingFlows();
  if (result.violations.length > 0) {
    console.error(`WP-05 core-parking guardrails failed (${result.violations.length}):`);
    for (const violation of result.violations) {
      console.error(`  ${violation.file}:${violation.line} [${violation.rule}] ${violation.detail}`);
    }
    process.exitCode = 1;
  } else {
    console.log(
      `WP-05 core-parking guardrails passed (${result.scannedFiles} Web source files).`,
    );
  }
}