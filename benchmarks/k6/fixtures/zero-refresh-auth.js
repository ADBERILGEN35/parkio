import { criticalAuthThresholds } from '../critical-auth-gates.js';
import { criticalLogin } from './auth-fixture-lib.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    ...criticalAuthThresholds(),
  },
};

export default function () {
  // Deliberately skip refresh so refresh sample count stays 0.
  criticalLogin();
}
