import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { evaluateReport } from './assert-playwright-report.mjs';

const script = fileURLToPath(new URL('./assert-playwright-report.mjs', import.meta.url));

function spec(title, status = 'expected', final = 'passed', projectName = 'ownership-chromium') {
  return { title, tests: [{ projectName, status, results: [{ status: final }] }] };
}

function report(specs, stats = {}, errors = []) {
  return {
    suites: [{ title: 'file', specs: [], suites: [{ title: 'inner', specs }] }],
    stats: { expected: specs.length, skipped: 0, unexpected: 0, flaky: 0, ...stats },
    errors,
  };
}

const opts = { project: 'ownership-chromium', min: 2 };

test('accepts a run where every test executed and passed', () => {
  assert.deepEqual(evaluateReport(report([spec('a'), spec('b')]), opts).failures, []);
});

test('rejects a skipped-green run', () => {
  const r = report([spec('a'), spec('b', 'skipped', 'skipped')], { skipped: 1 });
  assert.ok(evaluateReport(r, opts).failures.length > 0);
});

test('rejects zero discovered tests and top-level errors', () => {
  assert.ok(evaluateReport(report([]), opts).failures.some((f) => f.includes('only 0')));
  const noTests = report([], {}, [{ message: 'No tests found' }]);
  assert.ok(evaluateReport(noTests, opts).failures.some((f) => f.includes('top-level')));
});

test('rejects flaky, failed, wrong-project and too-few results', () => {
  assert.ok(evaluateReport(report([spec('a'), spec('b', 'flaky')], { flaky: 1 }), opts).failures.length);
  assert.ok(evaluateReport(report([spec('a'), spec('b', 'unexpected', 'failed')]), opts).failures.length);
  assert.ok(evaluateReport(report([spec('a'), spec('b', 'expected', 'passed', 'chromium')]), opts).failures.length);
  assert.ok(evaluateReport(report([spec('a')]), opts).failures.length);
});

test('CLI exits non-zero for a skipped-only report and zero for a proven one', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pw-report-'));
  const bad = join(dir, 'bad.json');
  const good = join(dir, 'good.json');
  writeFileSync(bad, JSON.stringify(report([spec('a', 'skipped', 'skipped')], { skipped: 1 })));
  writeFileSync(good, JSON.stringify(report([spec('a'), spec('b')])));
  const run = (file) =>
    spawnSync(process.execPath, [script, file, '--project', 'ownership-chromium', '--min', '2']);
  assert.equal(run(bad).status, 1);
  assert.equal(run(good).status, 0);
  assert.equal(run(join(dir, 'missing.json')).status, 1);
});
