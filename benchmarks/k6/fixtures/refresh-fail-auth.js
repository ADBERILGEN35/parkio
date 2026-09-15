import { criticalAuthThresholds } from '../critical-auth-gates.js';
import { criticalLogin, criticalRefresh } from './auth-fixture-lib.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    ...criticalAuthThresholds(),
  },
};

export default function () {
  const login = criticalLogin();
  // Stub returns unexpected refresh failure; samples still increment.
  criticalRefresh(login.refreshToken);
}
