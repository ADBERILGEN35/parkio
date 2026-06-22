import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios';
import { CORRELATION_HEADER, createCorrelationId } from './correlation';
import { AccountNotActiveError, getAxiosParkioError, UnauthorizedError } from './errors';
import type { TokenStorage } from './token-storage';

export const DEFAULT_API_BASE_URL = 'http://localhost:8080/api/v1';

export interface ApiClientOptions {
  baseURL?: string;
  tokenStorage: TokenStorage;
  /** Called when refresh fails or is not implemented — app should hard-logout. */
  onAuthFailure?: () => void;
  /** Called on any 403 ACCOUNT_NOT_ACTIVE — app should enter the suspended state. */
  onAccountNotActive?: () => void;
}

type RefreshHandler = () => Promise<string | null>;

let refreshHandler: RefreshHandler | null = null;
let refreshPromise: Promise<string | null> | null = null;

/** Wire the auth refresh implementation from the app layer. */
export function setRefreshHandler(handler: RefreshHandler | null): void {
  refreshHandler = handler;
}

/**
 * Single-flight refresh coordinator shared by every caller — the 401 response
 * interceptor, AuthBootstrap (post-reload session restore), and any manual
 * refresh. While one POST /auth/refresh-token is in flight, all callers await
 * the same promise, so the rotated HttpOnly refresh cookie is presented to the
 * backend exactly once. Without this, two callers (e.g. React StrictMode's
 * double-invoked bootstrap effect, two tabs, or a bootstrap racing a 401) would
 * each replay the same cookie and the backend's reuse detection would revoke the
 * whole token family. Resolves the new access token, or `null` when refresh is
 * unavailable/failed. The in-flight promise is always cleared once settled so a
 * later refresh starts a fresh request.
 *
 * Scope: per-tab (per module instance). Cross-tab session-clear is coordinated
 * separately at the app layer via BroadcastChannel; access/refresh tokens are
 * never shared through storage.
 */
export function refreshSession(): Promise<string | null> {
  if (!refreshHandler) {
    return Promise.resolve(null);
  }
  refreshPromise ??= refreshHandler().finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
}

/** Diagnostics/tests: whether a shared refresh is currently in flight. */
export function isRefreshInFlight(): boolean {
  return refreshPromise !== null;
}

/**
 * 401s from these endpoints must never trigger a silent refresh: login/register
 * 401 means bad credentials, and refresh-token/logout 401 means the refresh
 * token itself is invalid (retrying would loop — or deadlock on the shared
 * in-flight refresh promise).
 */
const REFRESH_EXEMPT_PATHS = ['/auth/login', '/auth/register', '/auth/refresh-token', '/auth/logout'];

function isRefreshExempt(url: string | undefined): boolean {
  return Boolean(url && REFRESH_EXEMPT_PATHS.some((path) => url.includes(path)));
}

export function createApiClient(options: ApiClientOptions): AxiosInstance {
  const { tokenStorage, onAuthFailure, onAccountNotActive } = options;
  const baseURL = options.baseURL ?? DEFAULT_API_BASE_URL;

  const client = axios.create({
    baseURL,
    headers: { 'Content-Type': 'application/json' },
    timeout: 30_000,
  });

  client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    config.headers.set(CORRELATION_HEADER, createCorrelationId());

    const token = tokenStorage.getAccessToken();
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`);
    }

    return config;
  });

  client.interceptors.response.use(
    (response) => response,
    async (error) => {
      const original = error.config as InternalAxiosRequestConfig & { _retry?: boolean };
      const parkioError = getAxiosParkioError(error);

      if (parkioError instanceof AccountNotActiveError) {
        onAccountNotActive?.();
        throw parkioError;
      }

      if (
        error.response?.status === 401 &&
        original &&
        !original._retry &&
        refreshHandler &&
        !isRefreshExempt(original.url)
      ) {
        original._retry = true;

        let newToken: string | null = null;
        try {
          // Shared single-flight: concurrent 401s collapse into one refresh.
          newToken = await refreshSession();
        } catch {
          newToken = null;
        }

        if (newToken) {
          // Sync storage before retry — the request interceptor always stamps
          // Authorization from tokenStorage and would otherwise use the stale token.
          tokenStorage.setTokens({ accessToken: newToken });
          return client(original);
        }

        // Refresh tokens are rotated and reuse-revoked server-side, so any
        // refresh failure means the session is unrecoverable: hard logout.
        onAuthFailure?.();
        throw new UnauthorizedError({
          code: 'INVALID_TOKEN',
          message: 'Session expired. Please sign in again.',
          traceId: '',
          timestamp: new Date().toISOString(),
        });
      }

      throw parkioError;
    },
  );

  return client;
}
