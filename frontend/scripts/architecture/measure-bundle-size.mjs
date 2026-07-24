import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { measureJavaScriptBundle, normalizeRepositoryPath } from './guardrail-lib.mjs';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(scriptDirectory, '../..');
const requestedTarget = process.argv[2] ?? 'apps/web/dist';
const absoluteTarget = path.resolve(frontendRoot, requestedTarget);

try {
  const measurement = await measureJavaScriptBundle(absoluteTarget);
  console.log(
    JSON.stringify(
      {
        target: normalizeRepositoryPath(path.relative(frontendRoot, absoluteTarget)),
        ...measurement,
      },
      null,
      2,
    ),
  );
} catch (error) {
  console.error(`Unable to measure JavaScript bundle at '${requestedTarget}': ${error.message}`);
  process.exitCode = 1;
}
