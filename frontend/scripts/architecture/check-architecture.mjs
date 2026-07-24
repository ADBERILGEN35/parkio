import { runBoundaryCheck } from './check-boundaries.mjs';
import { runPublicExportCheck } from './check-public-exports.mjs';

const boundaryResult = await runBoundaryCheck();
if (boundaryResult.violations.length === 0) {
  await runPublicExportCheck();
}
