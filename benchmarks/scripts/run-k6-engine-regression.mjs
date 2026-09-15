/**
 * Real k6 engine regression against grafana/k6:0.53.0 (CI-pinned).
 * Shared critical-auth-gates + local auth stub on an isolated Docker network.
 * No production / third-party contact.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { assertK6ExitAndSummary, validateK6SummaryFile } from './validate-k6-summary.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../..');
const K6_IMAGE = process.env.PARKIO_K6_IMAGE || 'grafana/k6:0.53.0';
const NODE_IMAGE = process.env.PARKIO_NODE_IMAGE || 'node:20-alpine';
const K6_DIR = path.join(REPO_ROOT, 'benchmarks', 'k6');
const SCRIPTS_DIR = path.join(REPO_ROOT, 'benchmarks', 'scripts');
const STUB_PORT = 18080;

function dockerAvailable() {
  const probe = spawnSync('docker', ['version', '--format', '{{.Server.Version}}'], {
    encoding: 'utf8',
  });
  return probe.status === 0;
}

function docker(args, opts = {}) {
  return spawnSync('docker', args, {
    encoding: 'utf8',
    cwd: REPO_ROOT,
    env: process.env,
    ...opts,
  });
}

function mountPath(hostPath) {
  return hostPath.replace(/\\/g, '/');
}

function runK6OnNetwork({ network, scriptRel, baseUrl, summaryPath, withCredentials = true, extraEnv = {} }) {
  const outDir = path.dirname(summaryPath);
  fs.mkdirSync(outDir, { recursive: true });
  // grafana/k6 runs as UID 12345; only this disposable out dir needs write access.
  try {
    fs.chmodSync(outDir, 0o777);
  } catch {
    // Best-effort on platforms that ignore chmod.
  }
  const hostSummary = path.join(outDir, 'summary.json');
  if (fs.existsSync(hostSummary)) fs.unlinkSync(hostSummary);

  const envArgs = ['-e', `PARKIO_BASE_URL=${baseUrl}`, ...Object.entries(extraEnv).flatMap(([k, v]) => ['-e', `${k}=${v}`])];
  if (withCredentials) {
    envArgs.push('-e', 'PARKIO_K6_EMAIL=fixture@parkio.local', '-e', 'PARKIO_K6_PASSWORD=fixture-password');
  }

  const result = docker([
    'run',
    '--rm',
    '--network',
    network,
    '-v',
    `${mountPath(K6_DIR)}:/scripts:ro`,
    '-v',
    `${mountPath(outDir)}:/out`,
    ...envArgs,
    K6_IMAGE,
    'run',
    '--summary-export',
    '/out/summary.json',
    `/scripts/${scriptRel.replace(/\\/g, '/')}`,
  ]);

  return {
    status: result.status == null ? 1 : result.status,
    stdout: result.stdout || '',
    stderr: result.stderr || '',
    summaryPath: fs.existsSync(hostSummary) ? hostSummary : summaryPath,
  };
}

function classifyInitRejection(run) {
  const text = `${run.stdout}\n${run.stderr}`;
  if (/unsupported aggregation method count on metric of type rate/i.test(text)) {
    return 'threshold_init_rejected_rate_count';
  }
  if (/invalid threshold/i.test(text)) {
    return 'threshold_init_rejected';
  }
  return null;
}

function expectFailureCategory(run, allowed) {
  const init = classifyInitRejection(run);
  if (init && allowed.includes(init)) return init;
  if (run.status !== 0 && allowed.includes('k6_threshold_or_check_failed')) {
    return 'k6_threshold_or_check_failed';
  }
  if (run.status !== 0 && allowed.includes('k6_nonzero_exit')) {
    return `k6_nonzero_exit:${run.status}`;
  }
  throw new Error(
    `unexpected failure category status=${run.status} allowed=${allowed.join(',')} stderr=${run.stderr.slice(0, 500)}`,
  );
}

function withDockerStub(mode, fn) {
  const id = `parkio-k6-${process.pid}-${Date.now()}`;
  const network = `${id}-net`;
  const stubName = `${id}-stub`;
  docker(['network', 'create', network]);
  try {
    const runStub = docker([
      'run',
      '-d',
      '--rm',
      '--name',
      stubName,
      '--network',
      network,
      '-e',
      `PARKIO_STUB_MODE=${mode}`,
      '-e',
      `PARKIO_STUB_PORT=${STUB_PORT}`,
      '-v',
      `${mountPath(SCRIPTS_DIR)}:/stub:ro`,
      '-w',
      '/stub',
      NODE_IMAGE,
      'node',
      'auth-stub-server.mjs',
    ]);
    if (runStub.status !== 0) {
      throw new Error(`stub start failed: ${runStub.stderr || runStub.stdout}`);
    }
    // Brief wait for listen
    spawnSync(process.platform === 'win32' ? 'timeout' : 'sleep', process.platform === 'win32' ? ['/t', '2'] : ['2'], {
      shell: process.platform === 'win32',
    });
    const baseUrl = `http://${stubName}:${STUB_PORT}`;
    try {
      return fn({ network, baseUrl, stubName });
    } finally {
      docker(['rm', '-f', stubName]);
    }
  } finally {
    docker(['network', 'rm', network]);
  }
}

async function main() {
  if (!dockerAvailable()) {
    console.error('[k6-engine-regression] FAIL docker_unavailable');
    process.exit(1);
  }

  // Ensure images exist (pull once).
  docker(['pull', K6_IMAGE], { stdio: 'inherit' });
  docker(['pull', NODE_IMAGE], { stdio: 'inherit' });

  const workRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'parkio-k6-engine-'));
  const results = [];

  // 0) Candidate http-load.js threshold acceptance
  {
    const net = `parkio-k6-init-${process.pid}`;
    docker(['network', 'create', net]);
    try {
      const out = path.join(workRoot, 'candidate-init');
      fs.mkdirSync(out, { recursive: true });
      const run = runK6OnNetwork({
        network: net,
        scriptRel: 'http-load.js',
        baseUrl: 'http://127.0.0.1:9',
        summaryPath: path.join(out, 'summary.json'),
        withCredentials: false,
      });
      const init = classifyInitRejection(run);
      assert.equal(init, null, 'candidate http-load.js must not use unsupported Rate/count thresholds');
      assert.notEqual(run.status, 0, 'candidate without seed credentials should fail setup');
      assert.notEqual(run.status, 104, 'exit 104 indicates threshold init rejection');
      results.push({ case: 'candidate_threshold_init', ok: true, status: run.status });
      console.log(`[k6-engine-regression] PASS candidate_threshold_init status=${run.status}`);
    } finally {
      docker(['network', 'rm', net]);
    }
  }

  // 1) POSITIVE
  withDockerStub('positive', ({ network, baseUrl }) => {
    const summaryPath = path.join(workRoot, 'positive', 'summary.json');
    fs.mkdirSync(path.dirname(summaryPath), { recursive: true });
    const run = runK6OnNetwork({
      network,
      scriptRel: 'fixtures/positive-auth.js',
      baseUrl,
      summaryPath,
    });
    assert.equal(run.status, 0, `positive fixture k6 exit expected 0 got ${run.status}: ${run.stderr}`);
    const gate = assertK6ExitAndSummary({ k6ExitCode: run.status, summaryPath: run.summaryPath });
    assert.equal(gate.ok, true, `positive summary gate: ${gate.errors.join(',')}`);
    assert.ok(gate.loginSamples > 0);
    assert.ok(gate.refreshSamples > 0);
    const parsed = JSON.parse(fs.readFileSync(run.summaryPath, 'utf8'));
    const shapePath = path.join(workRoot, 'positive-metric-shape.json');
    fs.writeFileSync(
      shapePath,
      JSON.stringify(
        {
          login_ok: parsed.metrics?.parkio_critical_login_ok,
          refresh_ok: parsed.metrics?.parkio_critical_refresh_ok,
          login_samples: parsed.metrics?.parkio_critical_login_samples,
          refresh_samples: parsed.metrics?.parkio_critical_refresh_samples,
        },
        null,
        2,
      ),
    );
    results.push({ case: 'positive', ok: true, summaryPath: run.summaryPath, shapePath });
    console.log(
      `[k6-engine-regression] PASS positive loginSamples=${gate.loginSamples} refreshSamples=${gate.refreshSamples}`,
    );
  });

  // 2) CRITICAL REFRESH FAILURE
  withDockerStub('refresh_fail', ({ network, baseUrl }) => {
    const summaryPath = path.join(workRoot, 'refresh-fail', 'summary.json');
    fs.mkdirSync(path.dirname(summaryPath), { recursive: true });
    const run = runK6OnNetwork({
      network,
      scriptRel: 'fixtures/refresh-fail-auth.js',
      baseUrl,
      summaryPath,
    });
    const category = expectFailureCategory(run, ['k6_threshold_or_check_failed', 'k6_nonzero_exit']);
    assert.notEqual(run.status, 0);
    assert.ok(fs.existsSync(run.summaryPath), 'refresh_fail must produce a summary');
    const summary = validateK6SummaryFile(run.summaryPath);
    assert.equal(summary.ok, false);
    assert.ok(summary.refreshSamples > 0, 'refresh must have been exercised');
    assert.ok(
      summary.errors.includes('critical_refresh_rate_failed'),
      `expected critical_refresh_rate_failed, errors=${summary.errors.join(',')}`,
    );
    results.push({ case: 'refresh_fail', ok: true, category, status: run.status });
    console.log(`[k6-engine-regression] PASS refresh_fail category=${category} status=${run.status}`);
  });

  // 3) ZERO REFRESH
  withDockerStub('positive', ({ network, baseUrl }) => {
    const summaryPath = path.join(workRoot, 'zero-refresh', 'summary.json');
    fs.mkdirSync(path.dirname(summaryPath), { recursive: true });
    const run = runK6OnNetwork({
      network,
      scriptRel: 'fixtures/zero-refresh-auth.js',
      baseUrl,
      summaryPath,
    });
    const category = expectFailureCategory(run, ['k6_threshold_or_check_failed', 'k6_nonzero_exit']);
    assert.notEqual(run.status, 0);
    assert.ok(fs.existsSync(run.summaryPath), 'zero_refresh must produce a summary');
    const summary = validateK6SummaryFile(run.summaryPath);
    assert.equal(summary.ok, false);
    assert.equal(summary.refreshSamples, 0);
    assert.ok(summary.loginSamples > 0);
    assert.ok(
      summary.errors.includes('critical_refresh_samples_insufficient') ||
        summary.errors.includes('critical_refresh_rate_failed'),
      `expected zero-refresh rejection, errors=${summary.errors.join(',')}`,
    );
    results.push({ case: 'zero_refresh', ok: true, category, status: run.status });
    console.log(`[k6-engine-regression] PASS zero_refresh category=${category} status=${run.status}`);
  });

  // 4) CRITICAL LOGIN FAILURE
  withDockerStub('login_fail', ({ network, baseUrl }) => {
    const summaryPath = path.join(workRoot, 'login-fail', 'summary.json');
    fs.mkdirSync(path.dirname(summaryPath), { recursive: true });
    const run = runK6OnNetwork({
      network,
      scriptRel: 'fixtures/login-fail-auth.js',
      baseUrl,
      summaryPath,
    });
    const category = expectFailureCategory(run, ['k6_threshold_or_check_failed', 'k6_nonzero_exit']);
    assert.notEqual(run.status, 0);
    if (fs.existsSync(run.summaryPath)) {
      const summary = validateK6SummaryFile(run.summaryPath);
      assert.equal(summary.ok, false);
      assert.ok(summary.loginSamples > 0, 'login must have been exercised');
      assert.ok(
        summary.errors.includes('critical_login_rate_failed') ||
          summary.errors.includes('critical_refresh_samples_insufficient'),
        `expected login failure evidence, errors=${summary.errors.join(',')}`,
      );
    }
    results.push({ case: 'login_fail', ok: true, category, status: run.status });
    console.log(`[k6-engine-regression] PASS login_fail category=${category} status=${run.status}`);
  });

  // 5) ORIGINAL DEFECT
  {
    const net = `parkio-k6-broken-${process.pid}`;
    docker(['network', 'create', net]);
    try {
      const summaryPath = path.join(workRoot, 'broken-rate-count', 'summary.json');
      fs.mkdirSync(path.dirname(summaryPath), { recursive: true });
      const run = runK6OnNetwork({
        network: net,
        scriptRel: 'fixtures/broken-rate-count.js',
        baseUrl: 'http://127.0.0.1:9',
        summaryPath,
        withCredentials: false,
      });
      const category = expectFailureCategory(run, [
        'threshold_init_rejected_rate_count',
        'threshold_init_rejected',
      ]);
      assert.equal(category, 'threshold_init_rejected_rate_count');
      assert.notEqual(run.status, 0);
      results.push({ case: 'original_defect', ok: true, category, status: run.status });
      console.log(
        `[k6-engine-regression] PASS original_defect category=${category} status=${run.status}`,
      );
    } finally {
      docker(['network', 'rm', net]);
    }
  }

  console.log(`[k6-engine-regression] ALL_PASS cases=${results.length} image=${K6_IMAGE}`);
  console.log(`[k6-engine-regression] workRoot=${workRoot}`);
}

const isMain =
  process.argv[1] &&
  path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (isMain) {
  main().catch((error) => {
    console.error(`[k6-engine-regression] FAIL ${error instanceof Error ? error.message : String(error)}`);
    process.exit(1);
  });
}
