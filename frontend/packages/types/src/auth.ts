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

export type RefreshTokenRequest = Record<string, never>;

export type LogoutRequest = Record<string, never>;
