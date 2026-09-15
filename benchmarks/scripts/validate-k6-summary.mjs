/**
 * Validate a k6 --summary-export JSON for Parkio performance-smoke.
 * Critical auth metrics must exist and pass; a successful upload of unrelated
 * diagnostics must not count as evidence that the required summary exists.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REQUIRED_METRICS = [
  'parkio_critical_login_ok',
  'parkio_critical_refresh_ok',
  'parkio_critical_login_samples',
  'parkio_critical_refresh_samples',
];

export function validateK6SummaryObject(summary, options = {}) {
  const errors = [];
  const requireCriticalPass = options.requireCriticalPass !== false;
  const minRefreshSamples = options.minRefreshSamples ?? 1;
  const minLoginSamples = options.minLoginSamples ?? 1;
  const expectedRunMarker = options.expectedRunMarker ?? null;

  if (summary == null || typeof summary !== 'object' || Array.isArray(summary)) {
    return { ok: false, errors: ['summary_not_object'], loginSamples: 0, refreshSamples: 0 };
  }

  if (expectedRunMarker != null) {
    const marker = summary.parkio_run_marker ?? summary.run_marker ?? null;
    if (marker !== expectedRunMarker) {
      errors.push('stale_or_mismatched_run_marker');
    }
  }

  const metrics = summary.metrics;
  if (metrics == null || typeof metrics !== 'object') {
    return { ok: false, errors: ['metrics_missing'], loginSamples: 0, refreshSamples: 0 };
  }

  for (const name of REQUIRED_METRICS) {
    if (metrics[name] == null || typeof metrics[name] !== 'object') {
      errors.push(`metric_missing:${name}`);
    }
  }

  const loginSamples = metricCount(metrics.parkio_critical_login_samples);
  const refreshSamples = metricCount(metrics.parkio_critical_refresh_samples);
  if (loginSamples < minLoginSamples) {
    errors.push('critical_login_samples_insufficient');
  }
  if (refreshSamples < minRefreshSamples) {
    errors.push('critical_refresh_samples_insufficient');
  }

  if (requireCriticalPass) {
    const loginRate = metricRate(metrics.parkio_critical_login_ok);
    const refreshRate = metricRate(metrics.parkio_critical_refresh_ok);
    if (loginRate == null || loginRate < 1) {
      errors.push('critical_login_rate_failed');
    }
    if (refreshRate == null || refreshRate < 1) {
      errors.push('critical_refresh_rate_failed');
    }
  }

  return { ok: errors.length === 0, errors, loginSamples, refreshSamples };
}

export function validateK6SummaryFile(filePath, options = {}) {
  if (!filePath || typeof filePath !== 'string') {
    return { ok: false, errors: ['path_missing'], loginSamples: 0, refreshSamples: 0 };
  }
  if (!fs.existsSync(filePath)) {
    return { ok: false, errors: ['summary_file_missing'], loginSamples: 0, refreshSamples: 0 };
  }
  const stat = fs.statSync(filePath);
  if (!stat.isFile() || stat.size <= 0) {
    return { ok: false, errors: ['summary_file_empty'], loginSamples: 0, refreshSamples: 0 };
  }
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch {
    return { ok: false, errors: ['summary_invalid_json'], loginSamples: 0, refreshSamples: 0 };
  }
  return validateK6SummaryObject(parsed, options);
}

export function assertK6ExitAndSummary({ k6ExitCode, summaryPath, options } = {}) {
  const summary = validateK6SummaryFile(summaryPath, options);
  const errors = [...summary.errors];
  if (typeof k6ExitCode !== 'number' || !Number.isFinite(k6ExitCode)) {
    errors.push('k6_exit_code_missing');
  } else if (k6ExitCode !== 0) {
    errors.push(`k6_nonzero_exit:${k6ExitCode}`);
  }
  return {
    ok: errors.length === 0,
    errors,
    k6ExitCode,
    summaryPath,
    loginSamples: summary.loginSamples,
    refreshSamples: summary.refreshSamples,
  };
}

function metricCount(metric) {
  if (!metric || typeof metric !== 'object') return 0;
  if (typeof metric.count === 'number') return metric.count;
  if (typeof metric.values?.count === 'number') return metric.values.count;
  return 0;
}

function metricRate(metric) {
  if (!metric || typeof metric !== 'object') return null;
  if (typeof metric.rate === 'number') return metric.rate;
  if (typeof metric.values?.rate === 'number') return metric.values.rate;
  if (typeof metric.passes === 'number' && typeof metric.fails === 'number') {
    const total = metric.passes + metric.fails;
    return total === 0 ? null : metric.passes / total;
  }
  return null;
}

function main(argv) {
  const file = argv[2];
  const k6ExitRaw = argv[3];
  const k6ExitCode = k6ExitRaw === undefined ? 0 : Number(k6ExitRaw);
  const result = assertK6ExitAndSummary({
    k6ExitCode: Number.isFinite(k6ExitCode) ? k6ExitCode : NaN,
    summaryPath: file,
  });
  if (!result.ok) {
    console.error(`[validate-k6-summary] FAIL ${result.errors.join(',')}`);
    process.exit(1);
  }
  console.log(
    `[validate-k6-summary] PASS file=${path.basename(file)} loginSamples=${result.loginSamples} refreshSamples=${result.refreshSamples}`,
  );
}

const isMain =
  process.argv[1] &&
  path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (isMain) {
  main(process.argv);
}
