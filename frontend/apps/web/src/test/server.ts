import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { frontendConfig } from '@/config/env';

/** Mirrors the application runtime's validated gateway base URL resolution. */
export const API_BASE = frontendConfig.apiBaseUrl;

/** Shared MSW server — tests register handlers per test via `server.use(...)`. */
export const server = setupServer(
  // Existing auth-page tests exercise OPEN behavior. Individual closed-mode tests override this.
  http.get(/\/api\/v1\/auth\/registration-mode$/, () => HttpResponse.json({ mode: 'OPEN' })),
);

export function apiErrorBody(code: string, message: string, traceId = 'trace-test') {
  return { code, message, traceId, timestamp: '2026-06-11T10:00:00Z' };
}
