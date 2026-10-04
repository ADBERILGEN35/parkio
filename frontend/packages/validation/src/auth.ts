import { z } from 'zod';

export const weakPasswordDenyList = new Set([
  'password',
  'password1',
  'password12',
  'password123',
  'password1234',
  'password12345',
  'qwerty',
  'qwerty123',
  'qwerty1234',
  'admin',
  'admin123',
  'admin1234',
  'letmein',
  'welcome',
  'welcome123',
  '12345678',
  '123456789',
  '1234567890',
  '11111111',
  '00000000',
]);

export const passwordRequirements = [
  { id: 'length', label: 'At least 12 characters' },
  { id: 'lowercase', label: 'One lowercase letter' },
  { id: 'uppercase', label: 'One uppercase letter' },
  { id: 'digit', label: 'One number' },
  { id: 'notCommon', label: 'Not a common password' },
] as const;

export function passwordRequirementState(password: string) {
  const normalized = password.toLowerCase().replace(/[^a-z0-9]/g, '');
  return {
    length: password.length >= 12,
    lowercase: /[a-z]/.test(password),
    uppercase: /[A-Z]/.test(password),
    digit: /\d/.test(password),
    notCommon: password.length > 0 && !weakPasswordDenyList.has(normalized),
  };
}

/**
 * BCrypt uses at most 72 bytes of a password and the auth service rejects longer ones with
 * PASSWORD_TOO_LONG (CL-F36). Letters such as ş, ğ or ü take two bytes in UTF-8, so 100
 * characters can be far more than 72 bytes.
 */
export const PASSWORD_MAX_BYTES = 72;

/** UTF-8 length of a string, computed per code point (no TextEncoder dependency). */
export function utf8ByteLength(value: string): number {
  let bytes = 0;
  for (const char of value) {
    const code = char.codePointAt(0) ?? 0;
    bytes += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
  }
  return bytes;
}

export function isWithinPasswordByteLimit(password: string) {
  return utf8ByteLength(password) <= PASSWORD_MAX_BYTES;
}

export function isStrongPassword(password: string) {
  const state = passwordRequirementState(password);
  return Object.values(state).every(Boolean);
}

const passwordSchema = z
  .string()
  .min(12, 'Password must be at least 12 characters')
  .max(100)
  .refine(isWithinPasswordByteLimit, {
    message: 'Password must be at most 72 bytes; letters such as ş or ğ count as two',
  })
  .refine(isStrongPassword, {
    message: 'Password must be at least 12 characters and include lowercase, uppercase, and a number',
  });

export const loginSchema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
});

export const registerSchema = z.object({
  email: z.string().email('Enter a valid email').max(255),
  password: passwordSchema,
});

export const forgotPasswordSchema = z.object({
  email: z.string().email('Enter a valid email').max(255),
});

export const resetPasswordSchema = z
  .object({
    password: passwordSchema,
    confirmPassword: z.string().min(1, 'Confirm your password'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

export const changePasswordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Current password is required'),
    password: passwordSchema,
    confirmPassword: z.string().min(1, 'Confirm your password'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

/**
 * Extended registration form. Only `email`/`password` are sent to
 * `POST /auth/register` (see `registerSchema`); the remaining fields are captured
 * in the UI and persisted after provisioning via `PATCH /users/me`. `phoneNumber`
 * is optional for beta and is NOT SMS-verified. Constraints mirror the user-service
 * profile DTO (displayName 2–50, phoneNumber ≤32).
 */
export const registerProfileSchema = z
  .object({
    displayName: z
      .string()
      .trim()
      .min(2, 'Enter your name (at least 2 characters)')
      .max(50, 'Name must be 50 characters or fewer'),
    email: z.string().email('Enter a valid email').max(255),
    phoneNumber: z
      .string()
      .trim()
      .max(32, 'Phone number must be 32 characters or fewer')
      .optional(),
    password: passwordSchema,
    confirmPassword: z.string().min(1, 'Confirm your password'),
    termsAccepted: z.boolean().refine((value) => value === true, {
      message: 'You must accept the terms to continue',
    }),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  });

export type LoginFormValues = z.infer<typeof loginSchema>;
export type RegisterFormValues = z.infer<typeof registerSchema>;
export type RegisterProfileFormValues = z.infer<typeof registerProfileSchema>;
export type ForgotPasswordFormValues = z.infer<typeof forgotPasswordSchema>;
export type ResetPasswordFormValues = z.infer<typeof resetPasswordSchema>;
export type ChangePasswordFormValues = z.infer<typeof changePasswordSchema>;
