export const AUTH_USER_STATUSES = [
  'PENDING_VERIFICATION',
  'ACTIVE',
  'SUSPENDED',
  'BANNED',
] as const;

export type AuthUserStatus = (typeof AUTH_USER_STATUSES)[number];

export const AUTH_ROLE_NAMES = ['USER', 'MODERATOR', 'ADMIN', 'SUPER_ADMIN'] as const;

export type AuthRoleName = (typeof AUTH_ROLE_NAMES)[number];

export interface User {
  id: string;
  email: string;
  status: AuthUserStatus;
  roles: AuthRoleName[];
}

export interface AuthResponse {
  accessToken: string | null;
  tokenType: 'Bearer';
  accessTokenExpiresAt: string | null;
  refreshTokenExpiresAt: string | null;
  /**
   * Raw refresh token. Present ONLY for native mobile clients (requests sent with
   * the `X-Parkio-Client: mobile` header); the backend omits this field entirely
   * for web responses, where the refresh token is carried by an HttpOnly cookie.
   */
  refreshToken?: string | null;
  user: User;
  /**
   * CSRF token for the cookie transport (web only): sent back as the `X-XSRF-TOKEN` header on
   * cookie refresh/logout and checked against the HttpOnly XSRF-TOKEN cookie. Absent for native
   * clients, which carry the refresh token in the body.
   */
  csrfToken?: string | null;
}

/** `GET /auth/csrf`: the current CSRF token for the cookie transport (the cookie is (re)issued too). */
export interface CsrfTokenResponse {
  token: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  locale?: 'tr' | 'en' | null;
  inviteToken?: string | null;
}

export interface VerifyEmailRequest {
  token: string;
}

export interface ResendVerificationRequest {
  email: string;
  locale?: 'tr' | 'en' | null;
}

export interface ForgotPasswordRequest {
  email: string;
  locale?: 'tr' | 'en' | null;
}

export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/** Native-only body for refresh and logout; browsers send no body and use the HttpOnly cookie. */
export interface MobileTokenRequest {
  refreshToken: string;
}

export type RefreshTokenRequest = MobileTokenRequest | undefined;

export type LogoutRequest = MobileTokenRequest | undefined;
export const REGISTRATION_MODES = ['CLOSED', 'INVITE', 'OPEN'] as const;
export type RegistrationMode = (typeof REGISTRATION_MODES)[number];
export interface RegistrationModeResponse {
  mode: RegistrationMode;
}
