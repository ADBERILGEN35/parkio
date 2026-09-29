import { criticalAuthThresholds } from '../critical-auth-gates.js';
import { criticalLogin } from './auth-fixture-lib.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    ...criticalAuthThresholds(),
    // Noncritical checks can pass without masking critical login failure.
    checks: ['rate>0.5'],
  },
};

export default function () {
  // Stub forces login failure; critical gate must reject.
  criticalLogin();
}
