export interface User {
  id: string;
  email: string;
  status: string;
  roles: string[];
}

export interface AuthResponse {
  accessToken: string | null;
  tokenType: string;
  accessTokenExpiresAt: string | null;
  refreshTokenExpiresAt: string | null;
  /**
   * Raw refresh token. Present ONLY for native mobile clients (requests sent with
   * the `X-Parkio-Client: mobile` header); the backend omits this field entirely
   * for web responses, where the refresh token is carried by an HttpOnly cookie.
   */
  refreshToken?: string | null;
  user: User;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
}

export interface VerifyEmailRequest {
  token: string;
}

export interface ResendVerificationRequest {
  email: string;
}

export interface ForgotPasswordRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export type RefreshTokenRequest = Record<string, never>;

export type LogoutRequest = Record<string, never>;
