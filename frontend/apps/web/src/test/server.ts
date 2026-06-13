import { DEFAULT_API_BASE_URL } from '@parkio/api-client';
import { setupServer } from 'msw/node';

/** Mirrors the api singleton's base URL resolution (`apps/web/src/api/index.ts`). */
export const API_BASE = import.meta.env.VITE_API_BASE_URL ?? DEFAULT_API_BASE_URL;

/** Shared MSW server — tests register handlers per test via `server.use(...)`. */
export const server = setupServer();

export function apiErrorBody(code: string, message: string, traceId = 'trace-test') {
  return { code, message, traceId, timestamp: '2026-06-11T10:00:00Z' };
}
