import { z } from 'zod';
import {
  WAITLIST_ROLE_VALUES,
  WAITLIST_SOURCE_VALUE,
  type SubmitWaitlistRequest,
  type WaitlistAcceptedResponse,
} from '@parkio/types';
import { instantSchema } from './primitives';

const coordinatesOnlyPattern = /^\s*[+-]?\d+(?:\.\d+)?\s*,\s*[+-]?\d+(?:\.\d+)?\s*$/;

export const submitWaitlistRequestSchema = z
  .object({
    email: z.string().min(1).email().max(254),
    consentTimestamp: instantSchema,
    city: z.string().max(120).refine((value) => !coordinatesOnlyPattern.test(value)).nullable().optional(),
    role: z.enum(WAITLIST_ROLE_VALUES).nullable().optional(),
    source: z.literal(WAITLIST_SOURCE_VALUE),
  })
  .strict() satisfies z.ZodType<SubmitWaitlistRequest>;

export const waitlistAcceptedResponseSchema = z
  .object({ status: z.literal('accepted') })
  .strip() satisfies z.ZodType<WaitlistAcceptedResponse>;
