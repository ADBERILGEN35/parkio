import type { AxiosInstance } from 'axios';
import type {
  AuthResponse,
  ChangePasswordRequest,
  CsrfTokenResponse,
  ForgotPasswordRequest,
  LoginRequest,
  RegisterRequest,
  ResendVerificationRequest,
  ResetPasswordRequest,
  User,
  VerifyEmailRequest,
  RegistrationModeResponse,
} from '@parkio/types';
import { isParkioApiError } from './sdk-errors';

const CSRF_HEADER = 'X-XSRF-TOKEN';

/**
 * CSRF token for the cookie transport (web). The API lives on another origin, so the SPA cannot read
 * the HttpOnly XSRF-TOKEN cookie; the backend hands the token out in the login/refresh body and on
 * `GET /auth/csrf`. It is kept in memory only: a reload starts without it, the first cookie
 * refresh/logout is then refused with 403 CSRF_TOKEN_REQUIRED, the token is fetched and the call is
 * retried once. Native clients send the refresh token in the body and never need it.
 */
let csrfToken: string | null = null;

/** Tests and diagnostics: the token the client currently holds. */
export function currentCsrfToken(): string | null {
  return csrfToken;
}

/** Tests: forget the token (a page reload does this implicitly). */
export function resetCsrfToken(): void {
  csrfToken = null;
}

function rememberCsrf(response: AuthResponse): AuthResponse {
  if (response && typeof response.csrfToken === 'string' && response.csrfToken.length > 0) {
    csrfToken = response.csrfToken;
  }
  return response;
}

function isCsrfRejection(error: unknown): boolean {
  // The client's response interceptor rejects with the SDK error, never the raw axios error.
  return isParkioApiError(error) && error.status === 403 && error.code === 'CSRF_TOKEN_REQUIRED';
}

export function createAuthApi(client: AxiosInstance) {
  async function fetchCsrfToken(): Promise<string> {
    const response = await client.get<CsrfTokenResponse>('/auth/csrf', { withCredentials: true });
    csrfToken = response.data.token;
    return csrfToken;
  }

  /**
   * POST to a cookie-authenticated endpoint with the CSRF header. Without a token in memory the call
   * is made once without the header (mocked and legacy backends accept it); a 403 then triggers one
   * token fetch and one retry.
   */
  async function postCookieTransport<T>(path: string): Promise<T> {
    const attempt = (token: string | null) =>
      client.post<T>(path, undefined, {
        withCredentials: true,
        ...(token ? { headers: { [CSRF_HEADER]: token } } : {}),
      });
    try {
      return (await attempt(csrfToken)).data;
    } catch (error) {
      if (!isCsrfRejection(error)) {
        throw error;
      }
      const refreshed = await fetchCsrfToken();
      return (await attempt(refreshed)).data;
    }
  }

  return {
    getRegistrationMode(signal?: AbortSignal): Promise<RegistrationModeResponse> {
      return client
        .get<RegistrationModeResponse>('/auth/registration-mode', { signal })
        .then((response) => response.data);
    },

    register(body: RegisterRequest): Promise<AuthResponse> {
      return client
        .post<AuthResponse>('/auth/register', body, { withCredentials: true })
        .then((r) => rememberCsrf(r.data));
    },

    login(body: LoginRequest): Promise<AuthResponse> {
      return client
        .post<AuthResponse>('/auth/login', body, { withCredentials: true })
        .then((r) => rememberCsrf(r.data));
    },

    /** Fetches the cookie-transport CSRF token (web); native clients never need it. */
    csrf(): Promise<string> {
      return fetchCsrfToken();
    },

    /**
     * Rotate the refresh token. Web sends no body, relies on the HttpOnly refresh
     * cookie and repeats the CSRF token in the X-XSRF-TOKEN header. Native mobile
     * passes its stored refresh token, which the backend reads from the body when
     * the `X-Parkio-Client: mobile` header is set; no cookie, no CSRF header.
     */
    refresh(refreshToken?: string): Promise<AuthResponse> {
      if (refreshToken) {
        return client
          .post<AuthResponse>('/auth/refresh-token', { refreshToken }, { withCredentials: true })
          .then((r) => r.data);
      }
      return postCookieTransport<AuthResponse>('/auth/refresh-token').then(rememberCsrf);
    },

    logout(refreshToken?: string): Promise<void> {
      if (refreshToken) {
        return client
          .post<void>('/auth/logout', { refreshToken }, { withCredentials: true })
          .then(() => undefined);
      }
      return postCookieTransport<void>('/auth/logout').then(() => undefined);
    },

    logoutAll(): Promise<void> {
      return client
        .post<void>('/auth/logout-all', undefined, { withCredentials: true })
        .then(() => undefined);
    },

    verifyEmail(body: VerifyEmailRequest): Promise<User> {
      return client.post<User>('/auth/verify-email', body).then((r) => r.data);
    },

    resendVerification(body: ResendVerificationRequest): Promise<void> {
      return client.post<void>('/auth/resend-verification', body).then(() => undefined);
    },

    forgotPassword(body: ForgotPasswordRequest): Promise<void> {
      return client.post<void>('/auth/forgot-password', body).then(() => undefined);
    },

    resetPassword(body: ResetPasswordRequest): Promise<void> {
      return client.post<void>('/auth/reset-password', body, { withCredentials: true }).then(() => undefined);
    },

    changePassword(body: ChangePasswordRequest): Promise<void> {
      return client.post<void>('/auth/change-password', body, { withCredentials: true }).then(() => undefined);
    },

    me(): Promise<User> {
      return client.get<User>('/auth/me').then((r) => r.data);
    },
  };
}

export type AuthApi = ReturnType<typeof createAuthApi>;
