import type { AxiosInstance } from 'axios';
import type {
  AuthResponse,
  ChangePasswordRequest,
  ForgotPasswordRequest,
  LoginRequest,
  RegisterRequest,
  ResendVerificationRequest,
  ResetPasswordRequest,
  User,
  VerifyEmailRequest,
} from '@parkio/types';

export function createAuthApi(client: AxiosInstance) {
  return {
    register(body: RegisterRequest): Promise<AuthResponse> {
      return client
        .post<AuthResponse>('/auth/register', body, { withCredentials: true })
        .then((r) => r.data);
    },

    login(body: LoginRequest): Promise<AuthResponse> {
      return client
        .post<AuthResponse>('/auth/login', body, { withCredentials: true })
        .then((r) => r.data);
    },

    /**
     * Rotate the refresh token. Web sends no body and relies on the HttpOnly
     * refresh cookie. Native mobile passes its stored refresh token, which the
     * backend reads from the body when the `X-Parkio-Client: mobile` header is set.
     */
    refresh(refreshToken?: string): Promise<AuthResponse> {
      const body = refreshToken ? { refreshToken } : undefined;
      return client
        .post<AuthResponse>('/auth/refresh-token', body, { withCredentials: true })
        .then((r) => r.data);
    },

    logout(refreshToken?: string): Promise<void> {
      const body = refreshToken ? { refreshToken } : undefined;
      return client
        .post<void>('/auth/logout', body, { withCredentials: true })
        .then(() => undefined);
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
