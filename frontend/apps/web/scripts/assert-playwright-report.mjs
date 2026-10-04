#!/usr/bin/env node
/**
 * Fails unless a Playwright JSON report proves real execution: at least
 * `--min` tests ran in `--project`, every one passed on its final attempt, and
 * nothing was skipped, flaky, unexpected or reported as a top-level error
 * (e.g. "No tests found"). A green exit from `playwright test` alone accepts
 * skipped-only runs; this guard does not.
 *
 * A known defect is a test.fail() test that failed as expected and whose reason (the `fail`
 * annotation's description) names its task as `Asana <task id>`. It is not a pass. `--known-defects <n>` (default 0) says how many
 * the run must have, exactly: a fix that makes one pass, or a new one, both fail the guard.
 *
 * Usage: node scripts/assert-playwright-report.mjs <report.json> --project <name> --min <n> [--known-defects <n>]
 */
import { readFileSync } from 'node:fs';

const TASK_REFERENCE = /\bAsana \d{16}\b/;

function isKnownDefect(t) {
  const result = t.results?.at(-1);
  const annotations = [...(t.annotations ?? []), ...(result?.annotations ?? [])];
  return (
    t.status === 'expected' &&
    t.expectedStatus === 'failed' &&
    result?.status === 'failed' &&
    annotations.some((a) => a.type === 'fail' && TASK_REFERENCE.test(a.description ?? ''))
  );
}

export function evaluateReport(report, { project, min, knownDefects = 0 }) {
  const failures = [];
  const tests = [];
  const visit = (suite) => {
    for (const spec of suite.specs ?? []) {
      for (const t of spec.tests ?? []) tests.push({ title: spec.title, ...t });
    }
    for (const child of suite.suites ?? []) visit(child);
  };
  for (const suite of report.suites ?? []) visit(suite);

  if ((report.errors ?? []).length > 0) {
    failures.push(`report has ${report.errors.length} top-level error(s)`);
  }
  const stats = report.stats ?? {};
  for (const key of ['skipped', 'unexpected', 'flaky']) {
    if ((stats[key] ?? 0) !== 0) failures.push(`stats.${key}=${stats[key]}`);
  }
  for (const t of tests) {
    const last = t.results?.at(-1)?.status;
    if (t.projectName !== project) failures.push(`"${t.title}" ran in project ${t.projectName}`);
    if (isKnownDefect(t)) continue;
    if (t.status !== 'expected' || last !== 'passed') {
      failures.push(`"${t.title}" status=${t.status} final=${last ?? 'none'}`);
    }
  }
  const defects = tests.filter(isKnownDefect);
  if (defects.length !== knownDefects) {
    failures.push(`${defects.length} known-defect test(s) failed as expected; the run declares exactly ${knownDefects}`);
  }
  const passed = tests.filter((t) => t.status === 'expected' && t.results?.at(-1)?.status === 'passed');
  if (passed.length < min) failures.push(`only ${passed.length} passed test(s); expected at least ${min}`);
  return { passed: passed.length, total: tests.length, knownDefects: defects.length, failures };
}

function main(argv) {
  const [file, ...rest] = argv;
  const arg = (name) => {
    const i = rest.indexOf(name);
    return i >= 0 ? rest[i + 1] : undefined;
  };
  const project = arg('--project');
  const min = Number(arg('--min'));
  const knownDefects = arg('--known-defects') === undefined ? 0 : Number(arg('--known-defects'));
  if (!file || !project || !Number.isInteger(min) || min < 1 || !Number.isInteger(knownDefects) || knownDefects < 0) {
    console.error('usage: assert-playwright-report.mjs <report.json> --project <name> --min <n> [--known-defects <n>]');
    return 2;
  }
  let report;
  try {
    report = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    console.error(`cannot read Playwright report ${file}: ${error.message}`);
    return 1;
  }
  const { passed, total, knownDefects: defects, failures } = evaluateReport(report, { project, min, knownDefects });
  if (failures.length > 0) {
    console.error(`Playwright acceptance NOT proven (${passed}/${total} passed):`);
    for (const f of failures) console.error(`  - ${f}`);
    return 1;
  }
  const defectNote = defects > 0 ? ` ${defects} known defect(s) failed as expected.` : '';
  console.log(`Playwright acceptance proven: ${passed}/${total} passed in ${project}, 0 skipped.${defectNote}`);
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
