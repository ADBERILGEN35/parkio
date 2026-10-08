/**
 * Credentials for the disposable browser tests. They are synthetic by construction: the mocked
 * suites never reach a backend, and the real-stack suite registers throwaway accounts on an
 * isolated stack. They are not the credentials of any hosted account; hosted/seeded accounts take
 * their password from the environment only (PARKIO_REAL_*_PASSWORD, never a committed default).
 */
export const E2E_PASSWORD = process.env.PARKIO_E2E_PASSWORD ?? 'e2e-disposable-Pass-11';
export const E2E_NEW_PASSWORD = process.env.PARKIO_E2E_NEW_PASSWORD ?? 'e2e-disposable-Pass-22';
