import { setupServer } from 'msw/node';
import { frontendConfig } from '@/config/env';

/** Mirrors the api singleton's validated base URL resolution (`apps/web/src/api/index.ts`). */
export const API_BASE = frontendConfig.apiBaseUrl;

/** Shared MSW server — tests register handlers per test via `server.use(...)`. */
export const server = setupServer();

export function apiErrorBody(code: string, message: string, traceId = 'trace-test') {
  return { code, message, traceId, timestamp: '2026-06-11T10:00:00Z' };
}
