import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios';
import { CORRELATION_HEADER, createCorrelationId } from './correlation';
import { AccountNotActiveError, getAxiosParkioError, UnauthorizedError } from './errors';
import type { TokenStorage } from './token-storage';

export interface ApiClientOptions {
  baseURL: string;
  tokenStorage: TokenStorage;
  /** Called when refresh fails or is not implemented — app should hard-logout. */
  onAuthFailure?: () => void;
  /** Called on any 403 ACCOUNT_NOT_ACTIVE — app should enter the suspended state. */
  onAccountNotActive?: () => void;
  /**
   * Static headers stamped on every request. The native app uses this to send
   * `X-Parkio-Client: mobile`, which switches the backend to the body-based
   * refresh-token transport. Web omits it and keeps the cookie flow.
   */
  defaultHeaders?: Record<string, string>;
}

type RefreshHandler = () => Promise<string | null>;

/**
 * Opt-in identity binding for a single request whose payload belongs to one
 * specific account (e.g. registration-captured profile fields). Unbound
 * requests keep the default behaviour: Authorization is read from token
 * storage when the request interceptor runs, and a 401 is retried once with
 * whatever token the shared refresh returns.
 *
 * A bound request instead:
 * - is stamped with `accessToken` (captured when the caller verified the
 *   identity), never with a token that replaced it in storage meanwhile;
 * - after a 401 and a successful shared refresh, is retried only when
 *   `allowRetry(refreshedAccessToken)` confirms the refreshed session is still
 *   the same identity. Without `allowRetry` it is never retried.
 */
export interface RequestAuthBinding {
  readonly accessToken: string;
  readonly allowRetry?: (refreshedAccessToken: string) => boolean;
}

type AuthBoundConfig = { authBinding?: RequestAuthBinding };

/** Axios config carrying a {@link RequestAuthBinding} through the interceptors. */
export function withAuthBinding(
  config: AxiosRequestConfig,
  authBinding: RequestAuthBinding | undefined,
): AxiosRequestConfig {
  return authBinding ? ({ ...config, authBinding } as AxiosRequestConfig) : config;
}

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
const REFRESH_EXEMPT_PATHS = [
  '/auth/login',
  '/auth/register',
  '/auth/refresh-token',
  '/auth/logout',
  // The CSRF token read is public at the gateway; a 401 from it must not wait on the refresh promise.
  '/auth/csrf',
];

function isRefreshExempt(url: string | undefined): boolean {
  return Boolean(url && REFRESH_EXEMPT_PATHS.some((path) => url.includes(path)));
}

export function createApiClient(options: ApiClientOptions): AxiosInstance {
  const { tokenStorage, onAuthFailure, onAccountNotActive, defaultHeaders } = options;
  const { baseURL } = options;

  const client = axios.create({
    baseURL,
    headers: { 'Content-Type': 'application/json', ...defaultHeaders },
    timeout: 30_000,
  });

  client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    config.headers.set(CORRELATION_HEADER, createCorrelationId());

    const binding = (config as InternalAxiosRequestConfig & AuthBoundConfig).authBinding;
    const token = binding ? binding.accessToken : tokenStorage.getAccessToken();
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`);
    }

    return config;
  });

  client.interceptors.response.use(
    (response) => response,
    async (error) => {
      const original = error.config as InternalAxiosRequestConfig &
        AuthBoundConfig & { _retry?: boolean };
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
          const binding = original.authBinding;
          if (binding) {
            // The refreshed session may belong to another identity (e.g. another
            // tab signed in): never replay this payload unless the caller confirms.
            if (!binding.allowRetry?.(newToken)) {
              throw parkioError;
            }
            original.authBinding = { ...binding, accessToken: newToken };
          }
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
