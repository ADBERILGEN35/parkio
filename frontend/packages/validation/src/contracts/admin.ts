import { z } from 'zod';
import type {
  AdminAuditEvent,
  AdminAuditListParams,
  AdminDashboard,
  AdminPage,
  AdminReasonBody,
  AdminRoleChangeBody,
  AdminSecuritySummary,
  AdminSession,
  AdminUserDetail,
  AdminUserListParams,
  AdminUserSummary,
} from '@parkio/types';
import {
  finiteNumberSchema,
  instantSchema,
  nonNegativeIntegerSchema,
  uuidSchema,
} from './primitives';

const authUserStatusSchema = z.enum([
  'PENDING_VERIFICATION',
  'ACTIVE',
  'SUSPENDED',
  'BANNED',
]);
const adminRoleNameSchema = z.enum(['USER', 'MODERATOR', 'ADMIN', 'SUPER_ADMIN']);
const adminAuditActionSchema = z.enum([
  'ADMIN_USER_SUSPENDED',
  'ADMIN_USER_REACTIVATED',
  'ADMIN_USER_SESSIONS_REVOKED',
  'ADMIN_USER_ROLE_GRANTED',
  'ADMIN_USER_ROLE_REVOKED',
  'ADMIN_VERIFICATION_RESENT',
  'ADMIN_SESSION_REVOKED',
  'ADMIN_BOOTSTRAP_SUPER_ADMIN',
]);
const adminAuditResultSchema = z.enum(['SUCCESS', 'FAILURE']);
const revocationReasonSchema = z.enum([
  'ROTATED',
  'LOGOUT',
  'REUSE_DETECTED',
  'EXPIRED_CLEANUP',
  'ADMIN_REVOKED',
  'PASSWORD_CHANGED',
]);

export const adminUserListParamsSchema = z
  .object({
    q: z.string().optional(),
    email: z.string().optional(),
    userId: uuidSchema.optional(),
    status: authUserStatusSchema.optional(),
    emailVerified: z.boolean().optional(),
    role: adminRoleNameSchema.optional(),
    createdFrom: instantSchema.optional(),
    createdTo: instantSchema.optional(),
    page: nonNegativeIntegerSchema.optional(),
    size: z.number().int().positive().optional(),
    sort: z.string().optional(),
  })
  .strict() satisfies z.ZodType<AdminUserListParams>;

export const adminAuditListParamsSchema = z
  .object({
    actorUserId: uuidSchema.optional(),
    targetResourceId: uuidSchema.optional(),
    actionType: adminAuditActionSchema.optional(),
    result: adminAuditResultSchema.optional(),
    occurredFrom: instantSchema.optional(),
    occurredTo: instantSchema.optional(),
    page: nonNegativeIntegerSchema.optional(),
    size: z.number().int().positive().optional(),
    sort: z.string().optional(),
  })
  .strict() satisfies z.ZodType<AdminAuditListParams>;

export const adminReasonBodySchema = z
  .object({ reason: z.string().min(1) })
  .strict() satisfies z.ZodType<AdminReasonBody>;

export const adminRoleChangeBodySchema = z
  .object({
    role: adminRoleNameSchema,
    action: z.enum(['GRANT', 'REVOKE']),
    reason: z.string().nullable().optional(),
  })
  .strict() satisfies z.ZodType<AdminRoleChangeBody>;

export const adminDashboardResponseSchema = z
  .object({
    totalUsers: nonNegativeIntegerSchema,
    usersByStatus: z.record(authUserStatusSchema, nonNegativeIntegerSchema),
    verifiedUsers: nonNegativeIntegerSchema,
    unverifiedUsers: nonNegativeIntegerSchema,
    registrationsToday: nonNegativeIntegerSchema,
    registrationsLast7Days: nonNegativeIntegerSchema,
    registrationsLast30Days: nonNegativeIntegerSchema,
    verificationConversionRate: finiteNumberSchema,
    activeSessionCount: nonNegativeIntegerSchema,
  })
  .strip() satisfies z.ZodType<AdminDashboard>;

export const adminUserSummaryResponseSchema = z
  .object({
    id: uuidSchema,
    email: z.string().email(),
    status: authUserStatusSchema,
    emailVerified: z.boolean(),
    roles: z.array(adminRoleNameSchema),
    createdAt: instantSchema,
    activeSessionCount: nonNegativeIntegerSchema,
  })
  .strip() satisfies z.ZodType<AdminUserSummary>;

export const adminSessionResponseSchema = z
  .object({
    sessionId: uuidSchema,
    createdAt: instantSchema,
    revoked: z.boolean(),
    revokedReason: revocationReasonSchema.nullable(),
    expiresAt: instantSchema,
  })
  .strip() satisfies z.ZodType<AdminSession>;

export const adminAuditEventResponseSchema = z
  .object({
    id: uuidSchema,
    occurredAt: instantSchema,
    actorUserId: uuidSchema,
    actorRoles: z.string(),
    actionType: adminAuditActionSchema,
    targetResourceType: z.string(),
    targetResourceId: uuidSchema.nullable(),
    result: adminAuditResultSchema,
    reason: z.string().nullable(),
    correlationId: z.string().nullable(),
  })
  .strip() satisfies z.ZodType<AdminAuditEvent>;

export const adminUserDetailResponseSchema = z
  .object({
    user: adminUserSummaryResponseSchema,
    sessions: z.array(adminSessionResponseSchema),
    recentAuditEvents: z.array(adminAuditEventResponseSchema),
  })
  .strip() satisfies z.ZodType<AdminUserDetail>;

export function adminPageResponseSchema<T>(itemSchema: z.ZodType<T>): z.ZodType<AdminPage<T>> {
  return z
    .object({
      content: z.array(itemSchema),
      page: nonNegativeIntegerSchema,
      size: nonNegativeIntegerSchema,
      totalElements: nonNegativeIntegerSchema,
      totalPages: nonNegativeIntegerSchema,
    })
    .strip();
}

export const adminUserPageResponseSchema = adminPageResponseSchema(adminUserSummaryResponseSchema);
export const adminAuditPageResponseSchema = adminPageResponseSchema(adminAuditEventResponseSchema);

export const adminSessionListResponseSchema = z.array(adminSessionResponseSchema);

export const adminSecuritySummaryResponseSchema = z
  .object({
    suspendedUsers: nonNegativeIntegerSchema,
    pendingVerificationUsers: nonNegativeIntegerSchema,
    activeSessionCount: nonNegativeIntegerSchema,
    reuseDetectedSessionCount: nonNegativeIntegerSchema,
  })
  .strip() satisfies z.ZodType<AdminSecuritySummary>;
