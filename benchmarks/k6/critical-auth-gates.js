/**
 * Shared critical-auth threshold definitions for Parkio k6 scripts.
 * Rate metrics support only `rate`; Counter metrics support `count`/`rate`.
 * Do not put count>0 on checks{...} or other Rate metrics (k6 rejects at init).
 */
export function criticalAuthThresholds() {
  return {
    parkio_critical_login_ok: ['rate==1'],
    parkio_critical_refresh_ok: ['rate==1'],
    parkio_critical_login_samples: ['count>0'],
    parkio_critical_refresh_samples: ['count>0'],
    'checks{critical:login}': ['rate==1'],
    'checks{critical:refresh}': ['rate==1'],
  };
}

/** Historical defect: count>0 on a Rate-typed tagged checks metric. */
export function originalBrokenRateCountThresholds() {
  return {
    'checks{critical:refresh}': ['rate==1', 'count>0'],
  };
}
