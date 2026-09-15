/**
 * Bounded HTTP readiness polling for CI smoke gates.
 * /status (or any other diagnostic URL) must never be treated as readiness success.
 */
export async function waitForHttpReady(options) {
  const {
    readyUrl,
    timeoutMs = 180_000,
    intervalMs = 5_000,
    fetchImpl = globalThis.fetch,
    now = () => Date.now(),
    sleep = (ms) => new Promise((r) => setTimeout(r, ms)),
    diagnosticUrl = null,
  } = options;

  if (!readyUrl) {
    return { ok: false, reason: 'ready_url_missing', attempts: 0, diagnostics: null };
  }

  const started = now();
  let attempts = 0;
  let lastReadyStatus = null;
  let lastDiagnostic = null;

  while (now() - started <= timeoutMs) {
    attempts += 1;
    try {
      const response = await fetchImpl(readyUrl, { method: 'GET' });
      lastReadyStatus = response.status;
      if (response.ok) {
        const body = await response.text();
        return {
          ok: true,
          reason: 'ready',
          attempts,
          readyStatus: response.status,
          readyBodySnippet: body.slice(0, 200),
          diagnostics: lastDiagnostic,
        };
      }
    } catch (error) {
      lastReadyStatus = null;
      lastDiagnostic = {
        ...(lastDiagnostic || {}),
        readyError: error instanceof Error ? error.message : String(error),
      };
    }

    if (diagnosticUrl) {
      try {
        const diag = await fetchImpl(diagnosticUrl, { method: 'GET' });
        const diagBody = await diag.text();
        lastDiagnostic = {
          statusUrl: diagnosticUrl,
          statusCode: diag.status,
          // Explicitly NOT readiness evidence.
          usedAsReadinessFallback: false,
          bodySnippet: diagBody.slice(0, 200),
        };
      } catch (error) {
        lastDiagnostic = {
          statusUrl: diagnosticUrl,
          statusError: error instanceof Error ? error.message : String(error),
          usedAsReadinessFallback: false,
        };
      }
    }

    if (now() - started > timeoutMs) {
      break;
    }
    await sleep(intervalMs);
  }

  return {
    ok: false,
    reason: 'ready_timeout',
    attempts,
    readyStatus: lastReadyStatus,
    diagnostics: lastDiagnostic,
  };
}

export function assertTempoReadinessResult(result) {
  if (!result || typeof result !== 'object') {
    return { ok: false, errors: ['result_missing'] };
  }
  const errors = [];
  if (result.diagnostics?.usedAsReadinessFallback === true) {
    errors.push('status_used_as_readiness_fallback');
  }
  if (!result.ok) {
    errors.push(result.reason || 'not_ready');
  }
  return { ok: errors.length === 0, errors };
}
