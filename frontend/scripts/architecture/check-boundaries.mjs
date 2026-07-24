import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import {
  findDependencyBoundaryViolations,
  findDirectHttpViolations,
  findCredentialPersistenceViolations,
  findCrossTabSecurityViolations,
  findPackageManifestBoundaryViolations,
  findWebRoutingOwnershipViolations,
  findWebRuntimeOwnershipViolations,
  listSourceFiles,
  normalizeRepositoryPath,
} from './guardrail-lib.mjs';

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(scriptDirectory, '../..');

const SCAN_ZONES = [
  { zone: 'application', root: 'apps/web/src', checkDirectHttp: true },
  { zone: 'application', root: 'apps/mobile-v2/src', checkDirectHttp: true },
  { zone: 'application', root: 'apps/mobile-v2/app', checkDirectHttp: true },
  { zone: 'api-client', root: 'packages/api-client/src', checkDirectHttp: false },
  { zone: 'types', root: 'packages/types/src', checkDirectHttp: false },
  { zone: 'validation', root: 'packages/validation/src', checkDirectHttp: false },
];

export async function checkBoundaries() {
  const violations = [];
  let scannedFiles = 0;

  for (const scanZone of SCAN_ZONES) {
    const absoluteRoot = path.join(frontendRoot, scanZone.root);
    const files = await listSourceFiles(absoluteRoot);
    scannedFiles += files.length;

    for (const file of files) {
      const source = await readFile(file, 'utf8');
      const fileViolations = findDependencyBoundaryViolations(source, scanZone.zone);
      if (scanZone.checkDirectHttp) {
        fileViolations.push(...findDirectHttpViolations(source));
      }
      if (scanZone.root === 'apps/web/src') {
        fileViolations.push(
          ...findWebRuntimeOwnershipViolations(
            source,
            normalizeRepositoryPath(path.relative(frontendRoot, file)),
          ),
          ...findWebRoutingOwnershipViolations(
            source,
            normalizeRepositoryPath(path.relative(frontendRoot, file)),
          ),
        );
        if (!/\.(?:test|spec)\.[jt]sx?$/.test(file)) {
          fileViolations.push(...findCredentialPersistenceViolations(source));
          fileViolations.push(
            ...findCrossTabSecurityViolations(
              source,
              normalizeRepositoryPath(path.relative(frontendRoot, file)),
            ),
          );
        }
      }

      for (const violation of fileViolations) {
        violations.push({
          ...violation,
          file: normalizeRepositoryPath(path.relative(frontendRoot, file)),
        });
      }
    }

    if (scanZone.zone !== 'application') {
      const packagePath = path.join(path.dirname(absoluteRoot), 'package.json');
      const packageJson = JSON.parse(await readFile(packagePath, 'utf8'));
      for (const violation of findPackageManifestBoundaryViolations(packageJson, scanZone.zone)) {
        violations.push({
          ...violation,
          file: normalizeRepositoryPath(path.relative(frontendRoot, packagePath)),
        });
      }
    }
  }

  for (const packagePath of ['apps/web/package.json', 'apps/mobile-v2/package.json']) {
    const absolutePackagePath = path.join(frontendRoot, packagePath);
    const packageJson = JSON.parse(await readFile(absolutePackagePath, 'utf8'));
    for (const violation of findPackageManifestBoundaryViolations(packageJson, 'application')) {
      violations.push({ ...violation, file: packagePath });
    }
  }

  return { scannedFiles, violations };
}

export async function runBoundaryCheck() {
  const result = await checkBoundaries();
  if (result.violations.length > 0) {
    console.error(`Architecture boundary check failed with ${result.violations.length} violation(s):`);
    for (const violation of result.violations) {
      console.error(
        `- ${violation.file}:${violation.line} [${violation.rule}] ${violation.detail}`,
      );
    }
    process.exitCode = 1;
    return result;
  }

  console.log(
    `Architecture boundary check passed (${result.scannedFiles} source files; direct HTTP and dependency boundaries verified).`,
  );
  return result;
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  await runBoundaryCheck();
}
