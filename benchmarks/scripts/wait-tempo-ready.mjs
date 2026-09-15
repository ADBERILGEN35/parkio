#!/usr/bin/env node
/**
 * CLI: poll Tempo /ready until success or deadline.
 * Writes readiness and optional /status diagnostics to separate files.
 * Never treats /status as readiness success.
 */
import fs from 'node:fs';
import path from 'node:path';
import { waitForHttpReady, assertTempoReadinessResult } from './wait-http-ready.mjs';

async function main() {
  const readyUrl = process.env.TEMPO_READY_URL || 'http://127.0.0.1:3200/ready';
  const statusUrl = process.env.TEMPO_STATUS_URL || 'http://127.0.0.1:3200/status';
  const timeoutMs = Number(process.env.TEMPO_READY_TIMEOUT_MS || '180000');
  const intervalMs = Number(process.env.TEMPO_READY_INTERVAL_MS || '5000');
  const outDir = process.env.TEMPO_EVIDENCE_DIR || 'runtime-validation-artifacts';

  fs.mkdirSync(outDir, { recursive: true });

  const result = await waitForHttpReady({
    readyUrl,
    diagnosticUrl: statusUrl,
    timeoutMs,
    intervalMs,
  });

  fs.writeFileSync(
    path.join(outDir, 'tempo-readiness-result.json'),
    `${JSON.stringify(result, null, 2)}\n`,
    'utf8',
  );

  if (result.diagnostics) {
    fs.writeFileSync(
      path.join(outDir, 'tempo-status-diagnostics.json'),
      `${JSON.stringify(result.diagnostics, null, 2)}\n`,
      'utf8',
    );
  }

  if (result.ok && result.readyBodySnippet != null) {
    fs.writeFileSync(path.join(outDir, 'tempo-ready.txt'), `${result.readyBodySnippet}\n`, 'utf8');
  }

  const assertion = assertTempoReadinessResult(result);
  if (!assertion.ok) {
    console.error(`[wait-tempo-ready] FAIL ${assertion.errors.join(',')}`);
    process.exit(1);
  }
  console.log(`[wait-tempo-ready] PASS attempts=${result.attempts} status=${result.readyStatus}`);
}

main().catch((error) => {
  console.error(`[wait-tempo-ready] ERROR ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
});
