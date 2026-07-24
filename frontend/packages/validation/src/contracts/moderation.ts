import { z } from 'zod';
import {
  APPEAL_NOTE_MAX_LENGTH,
  APPEAL_STATUSES,
  MODERATION_ACTIONS,
  MODERATION_REASONS,
  MODERATION_SEVERITIES,
  MODERATION_STATUSES,
  MODERATION_TARGET_TYPES,
  REPORT_DESCRIPTION_MAX_LENGTH,
  RESOLUTION_NOTE_MAX_LENGTH,
  type CreateAppealRequest,
  type CreateReportRequest,
  type ModerationAppeal,
  type ModerationCase,
  type ModerationReport,
  type ResolveAppealRequest,
  type ResolveCaseRequest,
} from '@parkio/types';
import { instantSchema, nonNegativeIntegerSchema, uuidSchema } from './primitives';

export const createReportRequestSchema = z
  .object({
    targetType: z.enum(MODERATION_TARGET_TYPES),
    targetId: uuidSchema,
    reason: z.enum(MODERATION_REASONS),
    description: z.string().max(REPORT_DESCRIPTION_MAX_LENGTH).nullable().optional(),
  })
  .strict() satisfies z.ZodType<CreateReportRequest>;

export const createAppealRequestSchema = z
  .object({
    caseId: uuidSchema,
    note: z.string().max(APPEAL_NOTE_MAX_LENGTH).nullable().optional(),
  })
  .strict() satisfies z.ZodType<CreateAppealRequest>;

export const resolveCaseRequestSchema = z
  .object({
    action: z.enum(MODERATION_ACTIONS),
    note: z.string().max(RESOLUTION_NOTE_MAX_LENGTH).nullable().optional(),
  })
  .strict() satisfies z.ZodType<ResolveCaseRequest>;

export const resolveAppealRequestSchema = z
  .object({
    accepted: z.boolean(),
    note: z.string().max(RESOLUTION_NOTE_MAX_LENGTH).nullable().optional(),
  })
  .strict() satisfies z.ZodType<ResolveAppealRequest>;

export const moderationReportResponseSchema = z
  .object({
    id: uuidSchema,
    reporterUserId: uuidSchema,
    targetType: z.enum(MODERATION_TARGET_TYPES),
    targetId: uuidSchema,
    reason: z.enum(MODERATION_REASONS),
    description: z.string().nullable(),
    caseId: uuidSchema.nullable(),
    createdAt: instantSchema,
  })
  .strip() satisfies z.ZodType<ModerationReport>;

export const moderationAppealResponseSchema = z
  .object({
    id: uuidSchema,
    appealUserId: uuidSchema,
    caseId: uuidSchema,
    note: z.string().nullable(),
    status: z.enum(APPEAL_STATUSES),
    resolverModeratorId: uuidSchema.nullable(),
    resolutionNote: z.string().nullable(),
    createdAt: instantSchema,
    resolvedAt: instantSchema.nullable(),
  })
  .strip() satisfies z.ZodType<ModerationAppeal>;

export const moderationCaseResponseSchema = z
  .object({
    id: uuidSchema,
    targetType: z.enum(MODERATION_TARGET_TYPES),
    targetId: uuidSchema,
    reason: z.enum(MODERATION_REASONS),
    severity: z.enum(MODERATION_SEVERITIES),
    status: z.enum(MODERATION_STATUSES),
    assignedModeratorId: uuidSchema.nullable(),
    reportCount: nonNegativeIntegerSchema,
    resolutionAction: z.enum(MODERATION_ACTIONS).nullable(),
    resolutionNote: z.string().nullable(),
    openedAt: instantSchema,
    updatedAt: instantSchema,
    resolvedAt: instantSchema.nullable(),
  })
  .strip() satisfies z.ZodType<ModerationCase>;

export const moderationReportListResponseSchema = z.array(moderationReportResponseSchema);
export const moderationAppealListResponseSchema = z.array(moderationAppealResponseSchema);
