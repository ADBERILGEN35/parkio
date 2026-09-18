import { check } from 'k6';
import { originalBrokenRateCountThresholds } from '../critical-auth-gates.js';

/**
 * Temporary regression representing the original Rate/count mismatch.
 * Pinned k6 must reject this at init (unsupported aggregation).
 */
export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    ...originalBrokenRateCountThresholds(),
  },
};

export default function () {
  check(true, { 'critical refresh status 200': () => true }, { critical: 'refresh' });
}
