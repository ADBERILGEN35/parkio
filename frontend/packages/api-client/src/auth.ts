import type { AxiosInstance } from 'axios';
import type {
  AuthResponse,
  LoginRequest,
  LogoutRequest,
  RefreshTokenRequest,
  RegisterRequest,
  User,
} from '@parkio/types';

export function createAuthApi(client: AxiosInstance) {
  return {
    register(body: RegisterRequest): Promise<AuthResponse> {
      return client.post<AuthResponse>('/auth/register', body).then((r) => r.data);
    },

    login(body: LoginRequest): Promise<AuthResponse> {
      return client.post<AuthResponse>('/auth/login', body).then((r) => r.data);
    },

    refresh(body: RefreshTokenRequest): Promise<AuthResponse> {
      return client.post<AuthResponse>('/auth/refresh-token', body).then((r) => r.data);
    },

    logout(body: LogoutRequest): Promise<void> {
      return client.post<void>('/auth/logout', body).then(() => undefined);
    },

    me(): Promise<User> {
      return client.get<User>('/auth/me').then((r) => r.data);
    },
  };
}

export type AuthApi = ReturnType<typeof createAuthApi>;
