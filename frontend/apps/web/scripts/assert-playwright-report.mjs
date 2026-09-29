#!/usr/bin/env node
/**
 * Fails unless a Playwright JSON report proves real execution: at least
 * `--min` tests ran in `--project`, every one passed on its final attempt, and
 * nothing was skipped, flaky, unexpected or reported as a top-level error
 * (e.g. "No tests found"). A green exit from `playwright test` alone accepts
 * skipped-only runs; this guard does not.
 *
 * Usage: node scripts/assert-playwright-report.mjs <report.json> --project <name> --min <n>
 */
import { readFileSync } from 'node:fs';

export function evaluateReport(report, { project, min }) {
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
    if (t.status !== 'expected' || last !== 'passed') {
      failures.push(`"${t.title}" status=${t.status} final=${last ?? 'none'}`);
    }
  }
  const passed = tests.filter((t) => t.status === 'expected' && t.results?.at(-1)?.status === 'passed');
  if (passed.length < min) failures.push(`only ${passed.length} passed test(s); expected at least ${min}`);
  return { passed: passed.length, total: tests.length, failures };
}

function main(argv) {
  const [file, ...rest] = argv;
  const arg = (name) => {
    const i = rest.indexOf(name);
    return i >= 0 ? rest[i + 1] : undefined;
  };
  const project = arg('--project');
  const min = Number(arg('--min'));
  if (!file || !project || !Number.isInteger(min) || min < 1) {
    console.error('usage: assert-playwright-report.mjs <report.json> --project <name> --min <n>');
    return 2;
  }
  let report;
  try {
    report = JSON.parse(readFileSync(file, 'utf8'));
  } catch (error) {
    console.error(`cannot read Playwright report ${file}: ${error.message}`);
    return 1;
  }
  const { passed, total, failures } = evaluateReport(report, { project, min });
  if (failures.length > 0) {
    console.error(`Playwright acceptance NOT proven (${passed}/${total} passed):`);
    for (const f of failures) console.error(`  - ${f}`);
    return 1;
  }
  console.log(`Playwright acceptance proven: ${passed}/${total} passed in ${project}, 0 skipped.`);
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
