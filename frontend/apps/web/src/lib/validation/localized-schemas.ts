import {
  isStrongPassword,
  passwordRequirementState,
  type ForgotPasswordFormValues,
  type LoginFormValues,
  type RegisterProfileFormValues,
  type ResetPasswordFormValues,
  type ChangePasswordFormValues,
} from '@parkio/validation';
import { z } from 'zod';

/** Minimal t signature used by schema factories (i18next TFunction compatible). */
export type SchemaTranslate = (key: string, options?: Record<string, unknown>) => string;

function createPasswordSchema(t: SchemaTranslate) {
  return z
    .string()
    .min(12, t('validation:password.minLength'))
    .max(100)
    .refine(isStrongPassword, {
      message: t('validation:password.strong'),
    });
}

export function createLoginSchema(t: SchemaTranslate) {
  return z.object({
    email: z.string().email(t('validation:email.invalid')),
    password: z.string().min(1, t('validation:password.required')),
  });
}

export function createForgotPasswordSchema(t: SchemaTranslate) {
  return z.object({
    email: z.string().email(t('validation:email.invalid')).max(255),
  });
}

export function createResetPasswordSchema(t: SchemaTranslate) {
  const passwordSchema = createPasswordSchema(t);
  return z
    .object({
      password: passwordSchema,
      confirmPassword: z.string().min(1, t('validation:password.confirm')),
    })
    .refine((data) => data.password === data.confirmPassword, {
      message: t('validation:password.mismatch'),
      path: ['confirmPassword'],
    });
}

export function createChangePasswordSchema(t: SchemaTranslate) {
  const passwordSchema = createPasswordSchema(t);
  return z
    .object({
      currentPassword: z.string().min(1, t('validation:password.currentRequired')),
      password: passwordSchema,
      confirmPassword: z.string().min(1, t('validation:password.confirm')),
    })
    .refine((data) => data.password === data.confirmPassword, {
      message: t('validation:password.mismatch'),
      path: ['confirmPassword'],
    });
}

export function createRegisterProfileSchema(t: SchemaTranslate) {
  const passwordSchema = createPasswordSchema(t);
  return z
    .object({
      displayName: z
        .string()
        .trim()
        .min(2, t('validation:displayName.min'))
        .max(50, t('validation:displayName.max')),
      email: z.string().email(t('validation:email.invalid')).max(255),
      phoneNumber: z
        .string()
        .trim()
        .max(32, t('validation:phoneNumber.max'))
        .optional(),
      password: passwordSchema,
      confirmPassword: z.string().min(1, t('validation:password.confirm')),
      termsAccepted: z.boolean().refine((value) => value === true, {
        message: t('validation:terms.required'),
      }),
    })
    .refine((data) => data.password === data.confirmPassword, {
      message: t('validation:password.mismatch'),
      path: ['confirmPassword'],
    });
}

export type PasswordRequirementId = keyof ReturnType<typeof passwordRequirementState>;

/** Localized password requirement checklist for register/reset UIs. */
export function getPasswordRequirements(t: SchemaTranslate) {
  return [
    { id: 'length' as const, label: t('validation:password.requirements.length') },
    { id: 'lowercase' as const, label: t('validation:password.requirements.lowercase') },
    { id: 'uppercase' as const, label: t('validation:password.requirements.uppercase') },
    { id: 'digit' as const, label: t('validation:password.requirements.digit') },
    { id: 'notCommon' as const, label: t('validation:password.requirements.notCommon') },
  ];
}

export type {
  LoginFormValues,
  RegisterProfileFormValues,
  ForgotPasswordFormValues,
  ResetPasswordFormValues,
  ChangePasswordFormValues,
};
