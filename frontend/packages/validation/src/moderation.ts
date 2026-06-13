import { z } from 'zod';
import {
  APPEAL_NOTE_MAX_LENGTH,
  MODERATION_ACTIONS,
  MODERATION_REASONS,
  MODERATION_TARGET_TYPES,
  REPORT_DESCRIPTION_MAX_LENGTH,
  RESOLUTION_NOTE_MAX_LENGTH,
} from '@parkio/types';

function emptyToUndefined(value: unknown) {
  return typeof value === 'string' && value.trim() === '' ? undefined : value;
}

/**
 * Full create-report request — mirrors `CreateReportRequest` (targetType,
 * targetId and reason required; description optional, max 2000 chars).
 */
export const createReportSchema = z.object({
  targetType: z.enum(MODERATION_TARGET_TYPES),
  targetId: z.string().uuid('targetId must be a UUID'),
  reason: z.preprocess(
    emptyToUndefined,
    z.enum(MODERATION_REASONS, {
      required_error: 'Select a reason',
      invalid_type_error: 'Select a reason',
    }),
  ),
  description: z
    .string()
    .trim()
    .max(
      REPORT_DESCRIPTION_MAX_LENGTH,
      `Description must be at most ${REPORT_DESCRIPTION_MAX_LENGTH} characters`,
    ),
});

/** Report form on the spot detail page — targetType/targetId come from the page. */
export const reportSpotFormSchema = createReportSchema.pick({
  reason: true,
  description: true,
});

export type ReportSpotFormValues = z.infer<typeof reportSpotFormSchema>;

/** Mirrors `CreateAppealRequest` — caseId required; note optional, max 2000 chars. */
export const createAppealSchema = z.object({
  caseId: z
    .string()
    .trim()
    .uuid('Enter a valid case id (UUID)'),
  note: z
    .string()
    .trim()
    .max(APPEAL_NOTE_MAX_LENGTH, `Note must be at most ${APPEAL_NOTE_MAX_LENGTH} characters`),
});

export type CreateAppealFormValues = z.infer<typeof createAppealSchema>;

/** Mirrors `ResolveCaseRequest` — action required, note optional (max 2000). */
export const resolveCaseSchema = z.object({
  action: z.preprocess(
    emptyToUndefined,
    z.enum(MODERATION_ACTIONS, {
      required_error: 'Select an action',
      invalid_type_error: 'Select an action',
    }),
  ),
  note: z
    .string()
    .trim()
    .max(RESOLUTION_NOTE_MAX_LENGTH, `Note must be at most ${RESOLUTION_NOTE_MAX_LENGTH} characters`),
});

export type ResolveCaseFormValues = z.infer<typeof resolveCaseSchema>;

/**
 * Mirrors `ResolveAppealRequest`. Radio inputs submit "true"/"false" strings,
 * so `accepted` is coerced to the backend's boolean.
 */
export const resolveAppealSchema = z.object({
  accepted: z.preprocess(
    (value) => (value === 'true' ? true : value === 'false' ? false : value),
    z.boolean({
      required_error: 'Choose accept or reject',
      invalid_type_error: 'Choose accept or reject',
    }),
  ),
  note: z
    .string()
    .trim()
    .max(RESOLUTION_NOTE_MAX_LENGTH, `Note must be at most ${RESOLUTION_NOTE_MAX_LENGTH} characters`),
});

export type ResolveAppealFormValues = z.infer<typeof resolveAppealSchema>;
