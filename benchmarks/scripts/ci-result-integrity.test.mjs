import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  assertK6ExitAndSummary,
  validateK6SummaryFile,
  validateK6SummaryObject,
} from './validate-k6-summary.mjs';
import { assertTempoReadinessResult, waitForHttpReady } from './wait-http-ready.mjs';

function passingSummary() {
  return {
    metrics: {
      parkio_critical_login_ok: { rate: 1, passes: 10, fails: 0 },
      parkio_critical_refresh_ok: { rate: 1, passes: 10, fails: 0 },
      parkio_critical_login_samples: { count: 10 },
      parkio_critical_refresh_samples: { count: 10 },
    },
  };
}

test('valid summary with critical refresh pass succeeds', () => {
  const result = validateK6SummaryObject(passingSummary());
  assert.equal(result.ok, true);
  assert.equal(result.refreshSamples, 10);
});

test('critical positive refresh failure makes validation fail', () => {
  const summary = passingSummary();
  summary.metrics.parkio_critical_refresh_ok = { rate: 0, passes: 0, fails: 46 };
  const result = validateK6SummaryObject(summary);
  assert.equal(result.ok, false);
  assert.ok(result.errors.includes('critical_refresh_rate_failed'));
});

test('no critical refresh samples makes validation fail', () => {
  const summary = passingSummary();
  summary.metrics.parkio_critical_refresh_samples = { count: 0 };
  const result = validateK6SummaryObject(summary);
  assert.equal(result.ok, false);
  assert.ok(result.errors.includes('critical_refresh_samples_insufficient'));
});

test('missing k6 summary file makes validation fail', () => {
  const result = validateK6SummaryFile(path.join(os.tmpdir(), `missing-${Date.now()}.json`));
  assert.equal(result.ok, false);
  assert.ok(result.errors.includes('summary_file_missing'));
});

test('invalid k6 summary JSON makes validation fail', () => {
  const file = path.join(os.tmpdir(), `bad-k6-${Date.now()}.json`);
  fs.writeFileSync(file, '{not-json', 'utf8');
  try {
    const result = validateK6SummaryFile(file);
    assert.equal(result.ok, false);
    assert.ok(result.errors.includes('summary_invalid_json'));
  } finally {
    fs.unlinkSync(file);
  }
});

test('missing required metrics makes validation fail', () => {
  const result = validateK6SummaryObject({ metrics: { http_reqs: { count: 100 } } });
  assert.equal(result.ok, false);
  assert.ok(result.errors.some((e) => e.startsWith('metric_missing:')));
});

test('stale summary marker makes validation fail', () => {
  const summary = { ...passingSummary(), parkio_run_marker: 'old' };
  const result = validateK6SummaryObject(summary, { expectedRunMarker: 'new' });
  assert.equal(result.ok, false);
  assert.ok(result.errors.includes('stale_or_mismatched_run_marker'));
});

test('nonzero k6 exit with valid diagnostics still fails combined gate', () => {
  const file = path.join(os.tmpdir(), `ok-k6-${Date.now()}.json`);
  fs.writeFileSync(file, JSON.stringify(passingSummary()), 'utf8');
  try {
    const result = assertK6ExitAndSummary({ k6ExitCode: 99, summaryPath: file });
    assert.equal(result.ok, false);
    assert.ok(result.errors.includes('k6_nonzero_exit:99'));
  } finally {
    fs.unlinkSync(file);
  }
});

test('Tempo remains 503 while /status is 200 -> readiness fails', async () => {
  const result = await waitForHttpReady({
    readyUrl: 'http://tempo.test/ready',
    diagnosticUrl: 'http://tempo.test/status',
    timeoutMs: 20,
    intervalMs: 5,
    now: (() => {
      let t = 0;
      return () => {
        t += 10;
        return t;
      };
    })(),
    sleep: async () => {},
    fetchImpl: async (url) => {
      if (String(url).endsWith('/ready')) {
        return { ok: false, status: 503, text: async () => 'not ready' };
      }
      return { ok: true, status: 200, text: async () => 'status ok' };
    },
  });
  assert.equal(result.ok, false);
  assert.equal(result.reason, 'ready_timeout');
  assert.equal(result.diagnostics?.statusCode, 200);
  assert.equal(result.diagnostics?.usedAsReadinessFallback, false);
  const assertion = assertTempoReadinessResult(result);
  assert.equal(assertion.ok, false);
});

test('Tempo becomes ready within deadline -> readiness succeeds', async () => {
  let calls = 0;
  const result = await waitForHttpReady({
    readyUrl: 'http://tempo.test/ready',
    diagnosticUrl: 'http://tempo.test/status',
    timeoutMs: 1000,
    intervalMs: 1,
    sleep: async () => {},
    fetchImpl: async (url) => {
      if (String(url).endsWith('/status')) {
        return { ok: true, status: 200, text: async () => 'diag' };
      }
      calls += 1;
      if (calls < 3) {
        return { ok: false, status: 503, text: async () => 'not yet' };
      }
      return { ok: true, status: 200, text: async () => 'ready' };
    },
  });
  assert.equal(result.ok, true);
  assert.equal(result.readyStatus, 200);
  assert.equal(assertTempoReadinessResult(result).ok, true);
});

test('real k6 0.53 summary-export Rate/Counter shape validates', () => {
  // Captured from grafana/k6:0.53.0 --summary-export (engine regression positive fixture).
  const summary = {
    metrics: {
      parkio_critical_login_ok: { passes: 1, fails: 0, value: 1 },
      parkio_critical_refresh_ok: { passes: 1, fails: 0, value: 1 },
      parkio_critical_login_samples: { count: 1, rate: 10 },
      parkio_critical_refresh_samples: { count: 1, rate: 10 },
    },
  };
  const result = validateK6SummaryObject(summary);
  assert.equal(result.ok, true);
  assert.equal(result.loginSamples, 1);
  assert.equal(result.refreshSamples, 1);
});

test('nonfinite Rate value is not accepted as success', () => {
  const summary = passingSummary();
  summary.metrics.parkio_critical_refresh_ok = { value: Number.NaN };
  const result = validateK6SummaryObject(summary);
  assert.equal(result.ok, false);
  assert.ok(result.errors.includes('critical_refresh_rate_failed'));
});

test('diagnostic collection does not turn failed validation into success', () => {
  const failed = assertTempoReadinessResult({
    ok: false,
    reason: 'ready_timeout',
    diagnostics: { statusCode: 200, usedAsReadinessFallback: false },
  });
  assert.equal(failed.ok, false);
  const poisoned = assertTempoReadinessResult({
    ok: false,
    reason: 'ready_timeout',
    diagnostics: { statusCode: 200, usedAsReadinessFallback: true },
  });
  assert.equal(poisoned.ok, false);
  assert.ok(poisoned.errors.includes('status_used_as_readiness_fallback'));
});
