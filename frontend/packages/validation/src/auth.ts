import { z } from 'zod';

export const loginSchema = z.object({
  email: z.string().email('Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
});

export const registerSchema = z.object({
  email: z.string().email('Enter a valid email').max(255),
  password: z.string().min(8, 'Password must be at least 8 characters').max(100),
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
    password: z.string().min(8, 'Password must be at least 8 characters').max(100),
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
