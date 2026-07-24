import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { extractPublicExports } from './guardrail-lib.mjs';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(scriptDirectory, '../..');
const packageRoot = path.join(frontendRoot, 'packages/api-client');
const baselinePath = path.join(
  frontendRoot,
  'architecture/sprint-2.3/public-exports.baseline.json',
);

function stableJson(value) {
  return JSON.stringify(value, null, 2);
}

export async function checkPublicExports() {
  const [packageJsonSource, entrypointSource, baselineSource] = await Promise.all([
    readFile(path.join(packageRoot, 'package.json'), 'utf8'),
    readFile(path.join(packageRoot, 'src/index.ts'), 'utf8'),
    readFile(baselinePath, 'utf8'),
  ]);

  const packageJson = JSON.parse(packageJsonSource);
  const baseline = JSON.parse(baselineSource);
  const actual = {
    packageName: packageJson.name,
    packageVersion: packageJson.version,
    packageExports: packageJson.exports,
    symbols: extractPublicExports(entrypointSource),
  };

  return {
    matches: stableJson(actual) === stableJson(baseline),
    actual,
    baseline,
  };
}

export async function runPublicExportCheck() {
  const result = await checkPublicExports();
  if (!result.matches) {
    console.error('Public export inventory does not match the frozen WP-01 baseline.');
    console.error('Expected:');
    console.error(stableJson(result.baseline));
    console.error('Actual:');
    console.error(stableJson(result.actual));
    process.exitCode = 1;
    return result;
  }

  console.log(`Public export inventory check passed (${result.actual.symbols.length} exports).`);
  return result;
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  await runPublicExportCheck();
}
