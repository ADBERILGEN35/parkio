import type { AxiosInstance } from 'axios';
import type {
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  ResendVerificationRequest,
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

    refresh(): Promise<AuthResponse> {
      return client
        .post<AuthResponse>('/auth/refresh-token', undefined, { withCredentials: true })
        .then((r) => r.data);
    },

    logout(): Promise<void> {
      return client
        .post<void>('/auth/logout', undefined, { withCredentials: true })
        .then(() => undefined);
    },

    verifyEmail(body: VerifyEmailRequest): Promise<User> {
      return client.post<User>('/auth/verify-email', body).then((r) => r.data);
    },

    resendVerification(body: ResendVerificationRequest): Promise<void> {
      return client.post<void>('/auth/resend-verification', body).then(() => undefined);
    },

    me(): Promise<User> {
      return client.get<User>('/auth/me').then((r) => r.data);
    },
  };
}

export type AuthApi = ReturnType<typeof createAuthApi>;
