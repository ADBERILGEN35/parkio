import { z } from 'zod';
import {
  AUTH_ROLE_NAMES,
  AUTH_USER_STATUSES,
  SUPPORTED_LOCALES,
  type AuthResponse,
  type ChangePasswordRequest,
  type ForgotPasswordRequest,
  type LoginRequest,
  type LogoutRequest,
  type MobileTokenRequest,
  type RefreshTokenRequest,
  type RegisterRequest,
  type ResendVerificationRequest,
  type ResetPasswordRequest,
  type User,
  type VerifyEmailRequest,
} from '@parkio/types';
import { instantSchema, uuidSchema } from './primitives';

const localeSchema = z.enum(SUPPORTED_LOCALES);
const nonBlankStringSchema = z.string().min(1);

export const loginRequestSchema = z
  .object({
    email: nonBlankStringSchema,
    password: nonBlankStringSchema,
  })
  .strict() satisfies z.ZodType<LoginRequest>;

export const registerRequestSchema = z
  .object({
    email: z.string().min(1).email().max(255),
    password: z.string().min(12).max(100),
    locale: localeSchema.nullable().optional(),
    inviteToken: z.string().min(1).max(512).nullable().optional(),
  })
  .strict() satisfies z.ZodType<RegisterRequest>;

export const verifyEmailRequestSchema = z
  .object({ token: nonBlankStringSchema })
  .strict() satisfies z.ZodType<VerifyEmailRequest>;

export const resendVerificationRequestSchema = z
  .object({
    email: z.string().min(1).email().max(255),
    locale: localeSchema.nullable().optional(),
  })
  .strict() satisfies z.ZodType<ResendVerificationRequest>;

export const forgotPasswordRequestSchema = z
  .object({
    email: z.string().min(1).email(),
    locale: localeSchema.nullable().optional(),
  })
  .strict() satisfies z.ZodType<ForgotPasswordRequest>;

export const resetPasswordRequestSchema = z
  .object({
    token: nonBlankStringSchema,
    newPassword: nonBlankStringSchema,
  })
  .strict() satisfies z.ZodType<ResetPasswordRequest>;

export const changePasswordRequestSchema = z
  .object({
    currentPassword: nonBlankStringSchema,
    newPassword: nonBlankStringSchema,
  })
  .strict() satisfies z.ZodType<ChangePasswordRequest>;

export const mobileTokenRequestSchema = z
  .object({ refreshToken: nonBlankStringSchema })
  .strict() satisfies z.ZodType<MobileTokenRequest>;

export const refreshTokenRequestSchema =
  mobileTokenRequestSchema.optional() satisfies z.ZodType<RefreshTokenRequest>;

export const logoutRequestSchema =
  mobileTokenRequestSchema.optional() satisfies z.ZodType<LogoutRequest>;

export const userResponseSchema = z
  .object({
    id: uuidSchema,
    email: z.string().email(),
    status: z.enum(AUTH_USER_STATUSES),
    roles: z.array(z.enum(AUTH_ROLE_NAMES)),
  })
  .strip() satisfies z.ZodType<User>;

export const authResponseSchema = z
  .object({
    accessToken: z.string().nullable(),
    tokenType: z.literal('Bearer'),
    accessTokenExpiresAt: instantSchema.nullable(),
    refreshTokenExpiresAt: instantSchema.nullable(),
    refreshToken: z.string().nullable().optional(),
    user: userResponseSchema,
  })
  .strip() satisfies z.ZodType<AuthResponse>;
